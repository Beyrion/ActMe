#include "llm_session.h"

#include <algorithm>
#include <sstream>
#include <utility>

#include "MNN/expr/ExecutorScope.hpp"
#include "mls_log.h"
#include "utf8_stream_processor.hpp"
#include "llm_stream_buffer.hpp"

namespace mls {

namespace {

std::string trimLeadingWhitespace(const std::string& str) {
    auto it = std::find_if(str.begin(), str.end(), [](unsigned char ch) {
        return !std::isspace(ch);
    });
    return {it, str.end()};
}

void restoreAndroidSteppingStatusIfNeeded(Llm* llm) {
    if (llm == nullptr) return;
    auto* context = llm->getContext();
    if (context == nullptr) return;
    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        auto* mutable_context = const_cast<MNN::Transformer::LlmContext*>(context);
        mutable_context->status = MNN::Transformer::LlmStatus::RUNNING;
    }
}

} // namespace

LlmSession::LlmSession(std::string modelPath, json config, json extraConfig,
                       std::vector<std::string> history)
    : modelPath_(std::move(modelPath))
    , config_(std::move(config))
    , extraConfig_(std::move(extraConfig)) {
    maxNewTokens_ = config_.contains("max_new_tokens") ? config_["max_new_tokens"].get<int>() : 2048;
    systemPrompt_ = config_.contains("system_prompt")
                        ? config_["system_prompt"].get<std::string>()
                        : "You are a helpful assistant.";
    history_.emplace_back("system", systemPrompt_);
    if (!history.empty()) {
        for (size_t i = 0; i < history.size(); i++) {
            history_.emplace_back(i % 2 == 0 ? "user" : "assistant", history[i]);
        }
    }
}

LlmSession::~LlmSession() {
    MNN_DEBUG("LlmSession DESTROYED at %p", this);
    delete llm_;
}

bool LlmSession::load() {
    lastLoadError_.clear();
    llm_ = Llm::createLLM(modelPath_ + "/config.json");
    if (llm_ == nullptr) {
        lastLoadError_ = "createLLM failed for config: " + modelPath_;
        MNN_DEBUG("Failed to create LLM: %s", lastLoadError_.c_str());
        return false;
    }

    json config = config_;
    config["use_mmap"] = false;
    if (extraConfig_.contains("mmap_dir") && !extraConfig_["mmap_dir"].get<std::string>().empty()) {
        config["use_mmap"] = true;
        config["tmp_path"] = extraConfig_["mmap_dir"].get<std::string>();
    }
    currentConfig_ = config;
    auto configStr = config.dump();
    MNN_DEBUG("LLM config: %s", configStr.c_str());
    llm_->set_config(configStr);

    modelLoaded_ = llm_->load();
    if (!modelLoaded_) {
        lastLoadError_ = "Module load failed for: " + modelPath_;
        MNN_DEBUG("Model load failed: %s", lastLoadError_.c_str());
    }
    return modelLoaded_;
}

void LlmSession::reset() {
    history_.resize(1);
    if (llm_) {
        llm_->reset();
    }
}

const MNN::Transformer::LlmContext* LlmSession::response(
    const std::string& prompt,
    const std::function<bool(const std::string&, bool isEop)>& onProgress) {
    if (llm_ == nullptr) return nullptr;

    int currentSize = 0;
    stopRequested_ = false;
    generateTextEnd_ = false;
    std::stringstream responseBuffer;

    history_.emplace_back("user", prompt);

    MNN_DEBUG("Response: history=%zu, max_new_tokens=%d",
              history_.size(), maxNewTokens_);

    // Stream callback: accumulate response via utf8 processor -> eop detection
    std::string responseStringForDebug;
    auto onResponseComplete = [&](const std::string& raw) {
        responseStringForDebug = raw;
        history_.emplace_back("assistant", trimLeadingWhitespace(raw));
    };

    mls::Utf8StreamProcessor utf8Processor([&](const std::string& utf8Char) {
        bool isEop = utf8Char.find("<eop>") != std::string::npos;
        if (!isEop) {
            responseBuffer << utf8Char;
            if (onProgress) {
                stopRequested_ = stopRequested_ || onProgress(utf8Char, false);
            }
        } else {
            onResponseComplete(responseBuffer.str());
            if (onProgress) {
                stopRequested_ = stopRequested_ || onProgress("<eop>", true);
            }
            generateTextEnd_ = true;
        }
    });

    LlmStreamBuffer streamBuffer([&utf8Processor](const char* str, size_t len) {
        utf8Processor.processStream(str, len);
    });
    std::ostream outputStream(&streamBuffer);

    restoreAndroidSteppingStatusIfNeeded(llm_);
    // Prefill only — tokenizer_encode (virtual in Omni) handles audio/img tags.
    // Then stepping decode one token at a time for streaming control.
    llm_->response(history_, &outputStream, "<eop>", 0);

    // Stepping decode loop — one token at a time for streaming
    while (!stopRequested_ && !generateTextEnd_ && currentSize < maxNewTokens_) {
        llm_->generate(1);
        currentSize++;
    }

    if (!stopRequested_ && enableAudioOutput_) {
        llm_->generateWavform();
    }

    if (!stopRequested_) {
        llm_->syncPromptCache(history_);
    }

    auto context = llm_->getContext();
    float prefillS = context->prefill_us / 1e6f;
    float decodeS = context->decode_us / 1e6f;
    float prefillTps = (prefillS > 0) ? context->prompt_len / prefillS : 0;
    float decodeTps = (decodeS > 0) ? context->gen_seq_len / decodeS : 0;
    MNN_DEBUG("PERF | prefill: %d tok in %.2fs (%.1f t/s) | decode: %d tok in %.2fs (%.1f t/s)",
              context->prompt_len, prefillS, prefillTps,
              context->gen_seq_len, decodeS, decodeTps);

    return context;
}

const MNN::Transformer::LlmContext* LlmSession::responseRaw(
    const std::string& prompt,
    const std::function<bool(const std::string&, bool isEop)>& onProgress) {
    if (llm_ == nullptr) return nullptr;

    int currentSize = 0;
    stopRequested_ = false;
    generateTextEnd_ = false;
    std::stringstream responseBuffer;

    // ASR is a single-turn task. Reset session state so previous chat history or
    // prompt-cache state cannot contaminate the current transcription.
    llm_->reset();

    MNN_DEBUG("Raw response: prompt_len=%zu, max_new_tokens=%d",
              prompt.size(), maxNewTokens_);

    mls::Utf8StreamProcessor utf8Processor([&](const std::string& utf8Char) {
        bool isEop = utf8Char.find("<eop>") != std::string::npos;
        if (!isEop) {
            responseBuffer << utf8Char;
            if (onProgress) {
                stopRequested_ = stopRequested_ || onProgress(utf8Char, false);
            }
        } else {
            if (onProgress) {
                stopRequested_ = stopRequested_ || onProgress("<eop>", true);
            }
            generateTextEnd_ = true;
        }
    });

    LlmStreamBuffer streamBuffer([&utf8Processor](const char* str, size_t len) {
        utf8Processor.processStream(str, len);
    });
    std::ostream outputStream(&streamBuffer);

    restoreAndroidSteppingStatusIfNeeded(llm_);
    llm_->response(prompt, &outputStream, "<eop>", 0);

    while (!stopRequested_ && !generateTextEnd_ && currentSize < maxNewTokens_) {
        llm_->generate(1);
        currentSize++;
    }

    auto context = llm_->getContext();
    float prefillS = context->prefill_us / 1e6f;
    float decodeS = context->decode_us / 1e6f;
    float prefillTps = (prefillS > 0) ? context->prompt_len / prefillS : 0;
    float decodeTps = (decodeS > 0) ? context->gen_seq_len / decodeS : 0;
    MNN_DEBUG("RAW PERF | prefill: %d tok in %.2fs (%.1f t/s) | decode: %d tok in %.2fs (%.1f t/s)",
              context->prompt_len, prefillS, prefillTps,
              context->gen_seq_len, decodeS, decodeTps);

    return context;
}

void LlmSession::setMaxNewTokens(int maxTokens) {
    maxNewTokens_ = maxTokens;
}

void LlmSession::setSystemPrompt(const std::string& prompt) {
    systemPrompt_ = prompt;
    if (history_.size() > 1) {
        history_.at(0).second = systemPrompt_;
    } else {
        history_.emplace_back("system", systemPrompt_);
    }
}

void LlmSession::enableAudioOutput(bool enable) {
    enableAudioOutput_ = enable;
}

void LlmSession::clearHistory(int numToKeep) {
    if (numToKeep < 0) numToKeep = 0;
    if (history_.size() > static_cast<size_t>(numToKeep)) {
        history_.erase(history_.begin() + numToKeep, history_.end());
    }
    if (llm_) {
        llm_->reset();
    }
}

std::string LlmSession::dumpConfig() const {
    if (llm_ != nullptr) {
        return llm_->dump_config();
    }
    return "{}";
}

} // namespace mls
