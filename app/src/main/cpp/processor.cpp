#include "processor.h"
#include "mls_log.h"

namespace mls {

PromptProcessingResult processMultimodalPrompt(const std::string& prompt_text) {
    PromptProcessingResult result;
    result.multimodal_prompt.prompt_template = prompt_text;

    if (prompt_text.find("<audio>") != std::string::npos) {
        result.has_multimodal = true;
        MNN_DEBUG("Detected audio tag in prompt, LLM engine will handle audio loading");
    }
    if (prompt_text.find("<img>") != std::string::npos ||
        prompt_text.find("<video>") != std::string::npos) {
        result.has_multimodal = true;
        MNN_DEBUG("Detected image/video tag in prompt");
    }

    return result;
}

} // namespace mls
