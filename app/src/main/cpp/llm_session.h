#pragma once

#include <vector>
#include <string>
#include <functional>
#include "json.hpp"
#include "llm/llm.hpp"

using nlohmann::json;
using MNN::Transformer::Llm;

namespace mls {

using PromptItem = std::pair<std::string, std::string>;

class LlmSession {
public:
    LlmSession(std::string modelPath, json config, json extraConfig,
               std::vector<std::string> stringHistory);
    ~LlmSession();

    bool load();
    bool isModelReady() const { return llm_ != nullptr && modelLoaded_; }
    const std::string& getLastLoadError() const { return lastLoadError_; }

    void reset();

    const MNN::Transformer::LlmContext* response(
        const std::string& prompt,
        const std::function<bool(const std::string&, bool isEop)>& onProgress);

    void setMaxNewTokens(int maxTokens);
    void setSystemPrompt(const std::string& prompt);
    void enableAudioOutput(bool enable);
    void clearHistory(int numToKeep = 1);

    std::string dumpConfig() const;

private:
    std::string modelPath_;
    std::vector<PromptItem> history_;
    json config_;
    json extraConfig_;
    json currentConfig_;
    Llm* llm_ = nullptr;
    bool modelLoaded_ = false;
    int maxNewTokens_ = 2048;
    std::string systemPrompt_;
    bool enableAudioOutput_ = false;
    std::string lastLoadError_;
    bool stopRequested_ = false;
    bool generateTextEnd_ = false;
};

} // namespace mls
