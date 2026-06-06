# Release Notes

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
