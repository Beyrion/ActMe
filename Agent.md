# ActMe Agent 设计说明

ActMe Agent 是 App 内置的中文行动型 Agent。它不是单轮聊天机器人，而是一个连接记忆、日程、联网、代码执行、文件处理和本机 Android 自动化能力的移动端执行代理。

本文档描述 Agent 的整体架构、输入输出协议、系统工具、多步执行、三驾马车、Skill/Memory、UI 展示和调试策略。更细的专题文档见 `docs/`。

## 核心定位

ActMe Agent 的目标是帮助用户把自然语言请求转化为可执行流程：

- 读取上下文：聊天历史、用户记忆、系统记忆、当前日程、启用 Skills。
- 判断写入：是否需要更新 memory、schedule 或 skill。
- 调用工具：联网搜索、网页浏览、Python、Excel、ADB、当前时间。
- 多步执行：观察工具结果后继续搜索、浏览、计算或操作。
- 可见可控：每一步工具执行都在聊天中展示，用户可以中止。
- 降级处理：工具失败或模型 JSON 不标准时尽量恢复，而不是把内部结构暴露给用户。

## 主要代码位置

```text
app/src/main/java/com/actme/app/data/agent/ActMeAgent.kt
  构造 system prompt、组装 LLM messages、解析 Agent JSON、流式抽取 reply。

app/src/main/java/com/actme/app/data/agent/SystemSkillExecutor.kt
  执行 system_calls，包括 time、web_search、browse_url、python_exec、adb_shell。

app/src/main/java/com/actme/app/data/repo/ActMeRepository.kt
  对话主流程、多步工具循环、工具预算、可见步骤、取消控制、结果落库。

app/src/main/java/com/actme/app/data/agent/GeckoSearchEngine.kt
  使用 GeckoView 进行搜索页渲染和网页文本读取。

app/src/main/java/com/actme/app/data/agent/PythonSkillEngine.kt
  初始化 Chaquopy Python runtime，调用 Python 沙箱。

app/src/main/python/agent_python.py
  Python 沙箱、Excel 读写、脚本保存/编译/执行。

app/src/main/java/com/actme/app/data/agent/AdbSkillEngine.kt
  ADB 配对、连接配置保存、shell 命令执行。

app/src/main/java/com/actme/app/ui/AdbOverlayService.kt
  显示在其他应用上方的 ADB 配对悬浮窗。

app/src/main/java/com/actme/app/ui/chat/ChatScreen.kt
  聊天 UI、工具执行气泡、资料展开、Excel 文件按钮、token 展示、停止按钮。
```

## 三驾马车

ActMe 的执行层由三类内置能力组成：

1. **内置浏览器**：联网搜索、打开网页、读取渲染后的页面文本。
2. **内置 Python**：确定性计算、数据清洗、Excel 读写、脚本复用。
3. **内置 ADB**：用户授权后观察和操作本机 Android 环境。

三者的关系：

```text
大模型负责规划和总结
App 内置能力负责真实执行
Agentic Loop 负责调度和继续
UI 负责展示、控制和中止
```

详见：

- [docs/BUILTIN_CAPABILITIES.md](docs/BUILTIN_CAPABILITIES.md)
- [docs/BUILTIN_BROWSER.md](docs/BUILTIN_BROWSER.md)
- [docs/BUILTIN_PYTHON.md](docs/BUILTIN_PYTHON.md)
- [docs/BUILTIN_ADB.md](docs/BUILTIN_ADB.md)

## Agent 输入上下文

每轮对话进入模型前会构造 messages，主要包含：

- system prompt
- 当前用户输入
- 可选图片输入
- 历史聊天消息
- 用户记忆
- 系统记忆
- 当前日程
- 启用 Skills
- 已执行的工具结果

历史消息会被清洗：

- `tool_execution` 消息不作为普通对话注入。
- assistant 消息中的 UI 展示文本会尽量剥离。
- 搜索结果、网页阅读内容、工具执行日志不会污染下一轮自然语言判断。

## Agent 输出协议

Agent 需要输出 JSON：

```json
{
  "reply": "给用户看的中文回复",
  "memory_updates": [
    {
      "category": "长期目标",
      "content": "用户希望长期提升英语口语能力。"
    }
  ],
  "schedule_updates": [
    {
      "title": "复习英语",
      "detail": "背单词并做阅读训练",
      "start_at": 0,
      "reminder_at": 0,
      "repeat_type": "DAILY",
      "repeat_days_of_week": [],
      "repeat_day_of_month": null,
      "reminder_time": "20:00"
    }
  ],
  "skill_updates": [
    {
      "name": "考试复习计划",
      "description": "当用户提到考试和复习时，生成分阶段计划。",
      "trigger_keywords": ["考试", "复习", "备考"],
      "action_template": "先确认考试日期和科目，再拆分每日任务，并设置提醒。"
    }
  ],
  "system_calls": [
    {
      "type": "python_exec",
      "query": "",
      "url": "",
      "code": "emit({'answer': 2 + 2})",
      "command": "",
      "input": "",
      "timeout_ms": 3000,
      "output_files": [],
      "generated_files": [],
      "expected_outputs": [],
      "files": []
    }
  ]
}
```

字段说明：

- `reply`：最终给用户看的中文回复；如果需要先调用工具，可以为空。
- `memory_updates`：写入个人记忆。
- `schedule_updates`：生成日程候选，保存前会再经过日程子 Agent 结构化。
- `skill_updates`：新增或更新本地 Skill。
- `system_calls`：请求 App 执行系统工具。
- `output_files` / `generated_files` / `expected_outputs` / `files`：工具可能生成的工作区文件列表；执行器也会自动检测 Python 运行前后的文件变化。

## System Calls

### get_current_time

获取设备当前时间、时区、星期和 Unix 毫秒时间戳。

```json
{
  "type": "get_current_time"
}
```

### web_search

联网搜索最新信息。

```json
{
  "type": "web_search",
  "query": "中国银行 积存金 价格 2026年"
}
```

搜索后端由 `SystemSkillExecutor` 管理，包含 Bing Gecko、Bing HTML、DuckDuckGo、Baidu、SearXNG 等降级路径。

### browse_url / browser_url

使用内置 GeckoView 浏览器打开 URL，并返回渲染后的页面文本。

```json
{
  "type": "browse_url",
  "url": "https://www.boc.cn/fimarkets/"
}
```

兼容别名：

- `browser_url`
- `web_browse`
- `open_url`

### python_exec

运行内置 Python 沙箱。

```json
{
  "type": "python_exec",
  "code": "values = input_json\nemit({'sum': sum(values)})",
  "input": "[1, 2, 3]",
  "timeout_ms": 3000,
  "output_files": []
}
```

适合：

- 计算和统计。
- JSON/CSV/表格处理。
- 正则提取和文本清洗。
- Excel 读取、分析、生成。
- PDF、CSV、图片、JSON、Markdown、文本等通用文件生成。
- 保存和复用 Python 脚本。
- 使用标准库和已安装 Python 包完成确定性处理。

可用 helper：

```python
input_text
input_json
emit(value)
set_result(value)
result
workspace_dir
report_font_dir
read_excel(path, max_rows=200, max_sheets=10)
write_excel(filename, sheets)
write_report(markdown_text, base_name="report", title=None, make_pdf=True)
save_script(name, source)
load_script(name)
list_scripts()
compile_script(name)
run_script(name)
```

文件输出字段：

```json
{
  "type": "python_exec",
  "code": "path = write_excel('report.xlsx', {'Sheet1': [['name', 'score'], ['A', 95]]}); emit({'file': path})",
  "output_files": ["report.xlsx"]
}
```

从 1.2.0 开始，Python 执行器会在每次运行前后扫描 `agent_workspace`，自动收集新增或修改文件，并把它们写入工具 observation。Repository 会在 Agent loop 的每一轮收集文件，最终统一去重显示在聊天中。

### adb_shell

通过已配对的内置 ADB 执行 shell 命令。

```json
{
  "type": "adb_shell",
  "command": "dumpsys window | head -50",
  "timeout_ms": 15000
}
```

兼容别名：

- `adb`
- `run_adb`

执行器会自动去掉命令前缀中的 `adb shell` 或 `shell`。

ADB 是高权限能力，Agent 默认应优先使用只读命令。删除、卸载、清数据、改权限、改系统设置等高风险命令必须等待用户明确确认。

## Agentic Loop

ActMe 支持一次用户请求中的多步工具执行：

```text
用户请求
-> LLM 输出 system_calls
-> App 执行工具
-> 工具结果返回给 LLM
-> LLM 继续调用工具或生成最终 reply
```

Repository 负责：

- 多 pass 循环。
- 工具预算。
- 搜索/浏览/Python/ADB 去重。
- 可见步骤展示。
- 中止控制。
- 暂停后允许用户发送“继续”。

详见 [docs/AGENTIC_LOOP.md](docs/AGENTIC_LOOP.md)。

## 工具预算与去重

为避免无限循环，ActMe 对工具调用做预算控制：

- 总工具调用数。
- 搜索调用数。
- 浏览调用数。
- 多步 pass 数。

去重策略：

- 相同搜索 query 不重复执行。
- 相同 URL 不重复浏览。
- 相同 Python code + input 不重复执行。
- 相同 ADB command 不重复执行。

当预算耗尽但 Agent 仍请求工具时，聊天中会显示暂停原因，并提示用户可以发送“继续”。

## 可见、可继续、可控、可中止

工具执行会进入聊天 UI 的工具执行气泡。典型步骤：

```text
[RUNNING] 联网搜索 - 中国银行 积存金 价格
[OK] 打开网页 - https://www.boc.cn/fimarkets/
[OK] Run Python - parse table
[OK] Run ADB - dumpsys window ...
```

用户可以中止执行。中止后：

- 当前 coroutine 取消。
- 工具循环停止。
- UI 显示“已中止”。

## 内置浏览器工作流

浏览器能力负责联网事实确认：

1. `web_search` 发现来源。
2. `browse_url` 打开官网、公告、新闻、文档或价格页。
3. Agent 根据页面文本判断是否继续浏览。
4. 必要时交给 Python 做提取和计算。

常见用途：

- 查最新信息。
- 验证官网数据。
- 多来源交叉确认。
- 阅读动态网页正文。

详见 [docs/BUILTIN_BROWSER.md](docs/BUILTIN_BROWSER.md)。

## 内置 Python 工作流

Python 能力负责确定性处理：

1. 简单任务直接在 `python_exec.code` 中写代码。
2. 复杂任务先 `save_script`。
3. 调用 `compile_script` 检查语法。
4. 出错则修复后再编译。
5. 编译通过后 `run_script`。

Excel 工作流：

- 外部 App 可以把 Excel 分享/打开到 ActMe。
- 聊天输入区可以选择 `.xlsx/.xlsm`。
- Agent 可调用 `read_excel(path)` 读取表格。
- Agent 可调用 `write_excel(filename, sheets)` 生成文件并返回聊天。
- Agent 可生成 PDF、HTML、CSV、图片、JSON、Markdown、文本等文件；只要文件位于 `agent_workspace`，聊天气泡就会显示打开按钮。
- 报告类任务优先调用 `write_report(markdown_text, "reports/name", title="...")`，一次生成 Markdown、HTML 和 PDF。

Python 沙箱边界：

- 默认允许导入标准库和已安装包，例如 `struct`、`numpy`、`pandas`、`openpyxl`、`PIL`、`matplotlib`、`reportlab`、`markdown`、`fpdf`、`fontTools`。
- Python 层不额外拦截文件读写删改，实际能否成功交给 Android App 沙箱和系统权限决定。
- Prompt 仍要求模型把需要在聊天中稳定展示打开按钮的生成文件写到 `agent_workspace` 相对路径。
- `report_font_dir` 是 App 打包字体拷贝出来的目录，可能在 `agent_workspace` 外；它是运行时资源，不用于写生成文件。
- 进程、native code、包安装和系统 shell 能力受限，例如 `subprocess`、`ctypes`、`multiprocessing`、`pip`、`venv`、`os.system`。
- 常见 cache/config 目录会重定向到 workspace，便于 matplotlib 等库正常工作。

详见 [docs/BUILTIN_PYTHON.md](docs/BUILTIN_PYTHON.md)。

## 内置 ADB 工作流

ADB 能力负责本机 Android 观察和自动化。

配对流程：

1. 设置页点击“内置 ADB”。
2. App 检查悬浮窗权限。
3. App 启动 `AdbOverlayService`，并打开开发者选项。
4. 用户进入无线调试，点击“使用配对码配对设备”。
5. 用户保持系统配对码弹窗不关闭，在 ActMe 悬浮窗输入配对端口和验证码。
6. 配对成功后，输入无线调试主页面的连接端口，点击“测试并保存连接”。

连接成功后：

- App 保存 `host:port`。
- 悬浮窗可以关闭。
- Agent 后续 `adb_shell` 会读取保存配置，临时连接并执行命令。

限制：

- 无线调试端口可能变化，变化后需要重新保存连接。
- 部分系统要求开启“允许在设置上重叠显示”。
- ADB 高风险命令需要用户确认。

详见 [docs/BUILTIN_ADB.md](docs/BUILTIN_ADB.md)。

## Memory

Memory 记录“关于用户的长期事实”。

示例：

```text
[长期目标] 用户希望长期提升英语口语能力。
[个人偏好] 用户喜欢表格式计划。
[学习工作] 用户正在准备期末考试。
```

Agent 可通过 `memory_updates` 写入。系统分类不允许 Agent 随意写入。

## Skill

Skill 记录“可复用做事方法”。

示例：

```text
考试复习计划：先确认考试日期和科目，再拆分每日任务，并设置提醒。
论文资料整理：先联网搜索多个来源，再按主题提炼观点和引用链接。
Excel 数据分析：先读取表格结构，再用 Python 汇总统计，最后生成结果文件。
```

当前 Skill 是轻量结构：

- name
- description
- triggerKeywords
- actionTemplate
- enabled

Claude/Codex 风格的目录型 skill 可以通过 `tools/adapt_claude_skills.py` 转换为当前 App 可识别的轻量 preload，同时保留完整 `SKILL.md` 和资源。

详见 [docs/SKILL_MEMORY_DESIGN.md](docs/SKILL_MEMORY_DESIGN.md)。

## JSON 解析兜底

模型可能输出非严格 JSON，例如：

- 包含 Markdown code fence。
- `system_call` 写成单对象。
- 使用 `tool_calls` / `tool_call` / `calls` 等别名。
- `reply` 中出现未转义引号。
- OpenAI tool call 风格的 `function.arguments`。

`ActMeAgent.parseRaw` 会尝试：

1. 严格 JSON 解析。
2. 提取 code fence。
3. 提取 JSON 主体。
4. 宽松解析 `reply`。
5. 宽松解析 system calls。
6. 仍失败时把 raw 文本作为普通 reply。

目标是避免把 system_call 字典或内部 JSON 原样回复给用户。

## Token Usage

`OpenAiResponsesClient` 会尽量读取 API usage：

- OpenAI-compatible streaming 尝试使用 `stream_options.include_usage=true`。
- 不支持该参数时自动重试。
- Anthropic-compatible streaming 从 message usage 事件读取 token。
- 多步执行会累计每次 LLM 调用的 usage。

UI 只在 assistant 消息下方显示 token，不显示用户输入 token。

## 失败与降级

单个工具失败不会直接终止整轮 Agent。常见降级：

- 搜索后端失败，尝试下一个后端。
- GeckoView 读取为空，尝试 HTTP 文本提取。
- Python 语法错误，使用 `compile_script` 检查并修复。
- ADB 连接失败，提示重新测试并保存端口。
- JSON 解析失败，进入宽松解析。
- usage 获取失败，不影响聊天主流程。

## 调试

常用 logcat 过滤：

```powershell
adb logcat -v time | Select-String -Pattern "ActMe|SystemSkillExecutor|GeckoSearchEngine|PythonSkillEngine|AdbSkillEngine|BROWSE|SEARCH|PYTHON|ADB|system_calls|parseRaw"
```

重点日志：

- `ActMeRepository`：多步循环、工具预算、pass 结果。
- `ActMeAgent`：JSON 解析、宽松解析、system calls。
- `SystemSkillExecutor`：工具执行、搜索、浏览、Python、ADB。
- `GeckoSearchEngine`：GeckoView 加载和网页文本读取。
- `PythonSkillEngine`：Python runtime 初始化和执行失败。
- `AdbSkillEngine`：ADB 配对、连接、shell 执行。

## 已知限制

- 部分网站需要登录、验证码或强风控，无法稳定读取。
- 搜索页文本需要 Agent 自行提取有效链接或还原面包屑 URL。
- Python 沙箱限制系统命令、进程控制、native code、包安装和工作区外写入。
- Excel 当前重点支持 `.xlsx/.xlsm`。
- ADB 连接依赖无线调试状态，端口可能变化。
- ADB 悬浮窗依赖系统 overlay 权限，部分系统还需要允许覆盖设置页。
- 当前工具预算仍是静态策略，尚未按任务复杂度动态调整。
- Agent 仍依赖模型遵守 JSON 协议，虽然已有多层兜底。

## docs 目录

专题文档：

- [docs/BUILTIN_CAPABILITIES.md](docs/BUILTIN_CAPABILITIES.md)
- [docs/BUILTIN_BROWSER.md](docs/BUILTIN_BROWSER.md)
- [docs/BUILTIN_PYTHON.md](docs/BUILTIN_PYTHON.md)
- [docs/BUILTIN_ADB.md](docs/BUILTIN_ADB.md)
- [docs/AGENTIC_LOOP.md](docs/AGENTIC_LOOP.md)
- [docs/SKILL_MEMORY_DESIGN.md](docs/SKILL_MEMORY_DESIGN.md)
