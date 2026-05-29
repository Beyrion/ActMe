#pragma once

#include <string>
#include "llm/llm.hpp"

namespace mls {

using PromptItem = std::pair<std::string, std::string>;

struct PromptProcessingResult {
    bool has_multimodal{false};
    MNN::Transformer::MultimodalPrompt multimodal_prompt;
    std::string error_message;
};

PromptProcessingResult processMultimodalPrompt(const std::string& prompt_text);

} // namespace mls
