# Release Notes

## 1.4.0 - 2026-06-23

### GUI Agent

- Added a minimal mobile GUI sub-agent for operating other Android apps through ActMe's normal main-agent planning workflow.
- Added planner/executor separation: the cloud/main agent writes the GUI plan and guidance, while the local visual executor reads screenshots and performs one GUI action per step.
- Added full `[ActMe]` logcat diagnostics for GUI model prompts, model outputs, parsed actions, coordinates, ADB commands, execution results, and terminal states.
- Added a persistent top-right overlay while GUI execution is running to reduce background-kill risk and make the active GUI task visible over other apps.
- Added a GUI completion overlay with a dedicated model-result bubble, close button, return-to-ActMe button, text input, and local voice input.
- Follow-up text from the GUI result overlay now returns through the normal ActMe conversation path, preserving the full main-agent planning workflow before any further GUI operation.

### Wireless ADB Pairing And Input

- Added screenshot-based wireless debugging pairing: ActMe watches for a new Android Settings screenshot, copies it into app storage, OCRs the pairing information, and attempts pair/connect automatically.
- Added automatic cleanup of the captured wireless-debugging screenshot after ADB is connected.
- Added connection reuse for saved wireless ADB sessions to avoid repeatedly showing the wireless-debugging connection prompt when an existing connection is still valid.
- Added ADB Keyboard integration for robust non-English text entry during GUI automation.
- Improved ADB command/process logging so expanded GUI execution steps show the full command and output path.

### Local OCR And Vision Models

- Switched the default local visual model reference to `MNN/GUI-Owl-1.5-2B-Instruct-MNN` for GUI screenshots.
- Added `MNN/GLM-OCR-MNN` as a dedicated local OCR model for wireless-debugging screenshot parsing and OCR-heavy image tasks.
- Improved OCR screenshot preprocessing limits for pairing screenshots, preserving higher resolution while bounding image size for mobile inference.
- Added rule-based ADB pairing information extraction on top of OCR output, so the local model only needs to recognize text and the app extracts host, ports, and pairing code deterministically.

### Local ASR And Overlay UX

- Reused ActMe's local `Qwen3-ASR-0.6B-INT8-MNN` ASR path in the GUI result overlay instead of Android system speech recognition.
- Updated the overlay voice button to record with ActMe's recorder, stop on the second tap, run local ASR, and insert the transcription into the overlay input box.
- Fixed result-overlay input focus so tapping the input box no longer submits or restarts a GUI task.
- Localized the overlay action buttons to `关闭`, `返回ActMe`, and `输入`, with a microphone button above the input box.

### Build And Runtime Fixes

- Updated the Android app version to `1.4.0`.
- Fixed OpenAI-compatible chat completion URL construction and Anthropic request defaults after provider retry-path cleanup.
- Fixed model-list fetching to use the intended provider endpoint instead of a stale config reference.
- Fixed overlay EditText single-line configuration for release Kotlin compilation.

## 1.3.1 - 2026-06-22

### Build And MNN Compatibility

- Updated the Android app version to `1.3.1`.
- Synchronized the documented MNN version/build requirements with the current native runtime integration.
- Clarified that release builds should use the matching MNN prebuilt/runtime output for the current app branch to avoid ABI, packaging, or chat-template mismatches.

## 1.3.0 - 2026-06-07

### Report And File Generation

- Added `write_report(markdown_text, base_name, title)` as the preferred report-generation helper for Agent Python.
- Added Markdown + HTML report output flow, with generated files collected into the final chat bubble.
- Added `html_to_pdf` system call, which renders workspace HTML files to PDF through Android WebView.
- Added `fpdf2`, `fonttools`, `markdown`, and supporting XML/font packages to the packaged Python runtime.
- Added app-packaged static Chinese report fonts for fallback/manual rendering paths, while the primary report PDF path now uses Android WebView.
- Added `report_font_dir` to the Python execution environment so Agent code can see the read-only report font directory when needed.
- Clarified that generated files should prefer relative paths under `agent_workspace` when they need to be shown in chat; the app maps those files to displayable file buttons.

### Python Sandbox And Diagnostics

- Clarified the Python sandbox model: file read/write/delete/rename access is not additionally blocked at the Python layer; Android's app sandbox and system permissions decide what succeeds.
- Kept process control, native-code loading, package installation, virtual environments, and system shell calls restricted.
- Improved Python stdout, stderr, PDF-generation errors, and traceback logging so logcat can show report failures without silent fallback.
- Added stronger `[ActMe]`-prefixed diagnostics for Python execution, file collection, and report generation.
- Increased Agent loop capacity for longer tool workflows while still keeping duplicate-tool and budget guards.
- Added empty-completion recovery so a pass with no user-visible reply and no output file continues automatically instead of requiring the user to say “继续”.

### Agent Prompt And Reliability

- Updated the Agent prompt to prefer `write_report` for Markdown/HTML reports and `html_to_pdf` for PDF output, avoiding manual ReportLab font registration.
- Updated the prompt to avoid `/system/fonts`, which may be blocked by Android SELinux.
- Updated file-output guidance so any pass in the Agent loop can produce files and the final response should surface them.
- Improved JSON/tool-call parsing resilience for malformed or partially wrapped tool calls.
- Prevented local Skill templates from being appended to user-visible chat replies, including model-request failure replies.
- Added full chat-bubble content logging through `[ActMe]` / `ChatOutput` so displayed replies can be matched exactly from logcat.

### Provider Models

- Added an optional default model field to model providers.
- When a provider has a default model, the app uses it directly instead of fetching the provider's model list.
- Added a Room migration for the provider default-model field.
- Added an empty-model guard so the app reports a local configuration error instead of sending `model: ""` and surfacing an HTTP 400 response.

## 1.2.0 - 2026-06-06

### Agent Loop And Tool Output

- Improved Agent JSON parsing for malformed, fenced, nested, or single-call tool outputs.
- Added user-visible sanitization so internal `system_calls` / `tool_calls` dictionaries are not shown as chat replies.
- Added generated-file tracking across every Agent loop pass, not just the final reply.
- Added `output_files`, `generated_files`, `expected_outputs`, and `files` fields to tool calls.
- Added automatic Python workspace file-change detection and propagation into tool observations.
- Added generic generated-file buttons in chat for PDF, Excel, CSV, images, JSON, Markdown, text, and other files.
- Added `AgentFile` logcat diagnostics for Python output files, repository collection, and chat bubble recognition.

### Python Sandbox

- Relaxed Python imports from an allowlist model to a denylist model, so standard-library modules such as `struct` and installed packages can be imported.
- Allowed installed packages such as `numpy`, `pandas`, `openpyxl`, `matplotlib`, and PDF/image libraries when available.
- Kept process, native-code, package-installation, and system-shell capabilities restricted.
- Limited file write/delete/rename operations to `agent_workspace` while allowing packages to read their own resources and system fonts.
- Redirected common cache/config paths such as `HOME`, `MPLCONFIGDIR`, and `XDG_CACHE_HOME` into the workspace.
- Kept Python syntax checking through on-device `compile_script`.

### Built-In ADB

- Added lightweight in-app ADB pairing and connection management.
- Added an overlay-based pairing flow so users can pair while Android wireless-debugging dialogs remain open.
- Added collapsible overlay UI, visible status, error toasts, and connection failure feedback.
- Added `adb_shell` execution for the Agent after a connection is tested and saved.
- Updated ADB dependency/build compatibility work for the current Android build stack.

### UI And Sessions

- Added a new-chat welcome card and preserved session tab state.
- Fixed session-history and Agent-session context issues.
- Fixed accidental new-session creation from the new-session page.
- Improved token-cost display and message tag layout.
- Fixed model selector layout so long model names do not cover the send button.
- Auto-collapsed the keyboard after sending.

### Build, Docs, And Maintenance

- Updated Android Gradle Plugin / Kotlin build configuration used by the app.
- Added design documents for built-in browser, built-in Python, built-in ADB, Agentic Loop, and Skill/Memory.
- Updated `Agent.md` with the current multi-tool architecture and runtime behavior.
- Added a Claude-style skill adapter under `tools/`.
- Updated architecture diagrams and documentation for the three built-in capability pillars.

## 1.1.0 - 2026-06-02

### Agent

- Added a native Python workflow for the Agent through `python_exec`.
- Updated the Agent prompt so it knows when to write Python, how to save reusable scripts, run `compile_script`, fix syntax errors, and execute scripts with `run_script`.
- Added runtime-maintained Python helper scripts under the app workspace.
- Added Python tool result visibility in the existing multi-step execution UI.

### Python Sandbox

- Added Chaquopy-based Python 3.11 runtime.
- Added sandbox helpers: `input_text`, `input_json`, `emit`, `set_result`, `workspace_dir`, and `result`.
- Added workspace-only file access and kept network/process/system access disabled.
- Added script helpers: `save_script`, `load_script`, `list_scripts`, `compile_script`, and `run_script`.
- Added `py_compile`-based syntax checking on-device through `compile_script`.

### Excel

- Added `openpyxl` support for Excel reading and writing.
- Added `read_excel(path, max_rows=200, max_sheets=10)` for uploaded `.xlsx/.xlsm` workbooks.
- Added `write_excel(filename, sheets)` so the Agent can generate Excel files.
- Added chat input support for selecting Excel files.
- Registered ActMe as an Android target for opening or sharing Excel files from other apps.
- Added generated Excel file buttons in chat, opened through a FileProvider.

### UI And Files

- Added Excel attachment preview in the chat input area.
- Added workspace file handling for incoming and generated Excel files.
- Added `agent_workspace` FileProvider paths for safely opening generated files in external apps.

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
