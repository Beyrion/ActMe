# Release Notes

## 1.0.1 - 2026-06-01

### Agent

- Added visible multi-step Agent execution, allowing the Agent to search, browse pages, observe results, and continue within one reply.
- Added stop control for an in-progress Agent run.
- Added tool budgets and duplicate query/URL handling to keep long-running tasks controllable.
- Relaxed tool-calling guidance so the Agent can choose search and browsing depth based on task complexity and user intent.
- Improved JSON parsing fallback to avoid exposing raw JSON dictionaries when model output is malformed.
- Cleaned assistant history before sending it back to the model, so execution progress text does not pollute later turns.

### Web Search And Browsing

- Added `browse_url` / `browser_url` support through the built-in GeckoView browser.
- Added aliases for browser calls: `browser_url`, `web_browse`, and `open_url`.
- Added expandable panels for search results, web page reading content, and mixed online materials.
- Improved Bing/Gecko search debugging with visible request and rendered page behavior in logcat.

### Chat UI

- Added API token usage display for assistant messages.
- Hid token counts for user input messages.
- Added clearer labels for expanded online materials: search results, page reading content, or mixed online materials.
- Removed debug URL display from the chat UI in favor of logcat diagnostics.

### Data And API

- Added Room fields and migration for token usage: input, output, total, and source.
- Added OpenAI-compatible streaming usage support with fallback when `stream_options.include_usage` is unsupported.
- Added Anthropic-compatible usage extraction from streaming events.

### Documentation

- Rewrote `README.md` with updated features, build instructions, project structure, and debugging notes.
- Added `Agent.md` with detailed Agent architecture, tool protocol, multi-step workflow, browser reading, parsing fallback, and limitations.
