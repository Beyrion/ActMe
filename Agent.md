# ActMe Agent 设计说明

本文档描述 ActMe Agent 的目标、输入输出协议、工具执行、多步工作流、联网搜索、网页浏览、JSON 解析兜底、token usage 统计和 UI 展示策略。

## 目标

ActMe Agent 是 App 内置的行动型中文 Agent。它不是单纯聊天机器人，而是连接以下能力的中枢：

- 和用户自然语言对话。
- 读取用户记忆、系统记忆、当前日程和启用的 Skills。
- 判断是否需要写入记忆。
- 判断是否需要创建日程。
- 判断是否需要新增本地 Skill。
- 按需调用系统工具，例如搜索、浏览网页、获取当前时间。
- 在一次回复中多轮执行工具，并把每一步展示给用户。
- 在工具失败、JSON 不标准、网页无法读取等情况下尽量降级处理，而不是把内部结构暴露给用户。

## 主要代码位置

```text
app/src/main/java/com/actme/app/data/agent/ActMeAgent.kt
  构造系统提示、组装 LLM messages、解析 Agent JSON、流式抽取 reply。

app/src/main/java/com/actme/app/data/agent/SystemSkillExecutor.kt
  执行 system_calls，包括 get_current_time、web_search、browse_url。

app/src/main/java/com/actme/app/data/agent/GeckoSearchEngine.kt
  使用 GeckoView + WebExtension 读取渲染后的页面文本。

app/src/main/java/com/actme/app/data/remote/OpenAiResponsesClient.kt
  OpenAI-compatible / Anthropic-compatible API 客户端，支持 streaming 和 token usage。

app/src/main/java/com/actme/app/data/repo/ActMeRepository.kt
  对话主流程、多步工具循环、可见执行步骤、取消控制、结果落库。

app/src/main/java/com/actme/app/ui/chat/ChatScreen.kt
  聊天 UI、消息气泡、资料展开面板、token 展示、停止按钮。
```

## Agent 输入上下文

每轮对话会构造一组 messages，主要包含：

- system prompt
- 历史聊天消息
- 当前用户输入
- 可选图片输入
- 用户记忆
- 系统记忆
- 当前日程
- 启用 Skills
- 已执行的联网搜索/网页浏览结果

历史消息在进入模型前会做清洗：

- assistant 消息中的“执行过程”只保留最终回复部分。
- 去掉“展开搜索结果 / 展开网页阅读内容 / 展开联网资料”的内部链接。
- 避免上一轮 UI 过程日志污染下一轮 Agent 判断。

## Agent 输出协议

Agent 被要求只输出 JSON：

```json
{
  "reply": "给用户的中文回复",
  "memory_updates": [
    {
      "category": "短期目标",
      "content": "..."
    }
  ],
  "schedule_updates": [
    {
      "title": "...",
      "detail": "...",
      "start_at": 0,
      "reminder_at": 0,
      "repeat_type": "NONE",
      "repeat_days_of_week": [],
      "repeat_day_of_month": null,
      "reminder_time": "12:00"
    }
  ],
  "skill_updates": [
    {
      "name": "...",
      "description": "...",
      "trigger_keywords": ["..."],
      "action_template": "..."
    }
  ],
  "system_calls": [
    {
      "type": "web_search",
      "query": "搜索内容"
    }
  ]
}
```

字段说明：

- `reply`：最终给用户看的中文回复。若本轮需要先调用工具，可为空。
- `memory_updates`：写入个人记忆库。
- `schedule_updates`：日程候选；保存前还会经日程子 Agent 二次结构化。
- `skill_updates`：新增或更新本地 Skill。
- `system_calls`：请求 App 执行系统工具。

## 系统工具

### get_current_time

获取设备当前时间、时区、星期和 Unix 毫秒时间戳。

示例：

```json
{
  "type": "get_current_time"
}
```

### web_search

联网搜索最新信息。

示例：

```json
{
  "type": "web_search",
  "query": "中国银行积存金价格 2026年6月1日"
}
```

搜索后端由 `SystemSkillExecutor` 管理。当前顺序包括：

1. Bing Gecko
2. Bing HTML
3. DuckDuckGo API
4. DuckDuckGo HTML
5. Baidu
6. SearXNG

Bing Gecko 会使用内置 GeckoView 打开 Bing 搜索页并返回渲染文本。搜索结果可能是整页文本，不一定是结构化链接列表，因此 Agent 会被提示在必要时从搜索结果文本中提取或还原 URL，再继续浏览。

### browse_url / browser_url

用内置浏览器打开指定 URL，并读取渲染后的页面文本。

示例：

```json
{
  "type": "browse_url",
  "url": "https://www.boc.cn/fimarkets"
}
```

兼容别名：

- `browser_url`
- `web_browse`
- `open_url`

`browser_url` 是为模型可能产生的命名偏差预留的别名，执行逻辑与 `browse_url` 相同。

## 内置浏览器读取机制

网页阅读由 `GeckoSearchEngine` 实现：

1. App 启动时初始化 Gecko runtime。
2. 执行浏览时创建 `GeckoSession`。
3. 安装内置 WebExtension。
4. 打开 URL。
5. WebExtension 在页面 ready 后读取页面标题和正文文本。
6. 通过 native messaging 返回给 App。
7. App 将结果包装为：

```text
[BROWSE_RESULT]
页面标题：...
页面文本：
...
```

失败时返回：

```text
[BROWSE_ERROR] ...
```

如果 GeckoView 读取为空，`SystemSkillExecutor` 会尝试 HTTP 文本提取作为 fallback。

## 多步执行工作流

一次用户回复可能包含多轮：

1. LLM 先生成 `system_calls`。
2. Repository 展示“Agent planning pass N”。
3. SystemSkillExecutor 执行工具。
4. 每个工具发出可见 step event：
   - `STARTED`
   - `FINISHED`
   - `FAILED`
5. Repository 将工具结果追加为 `searchResults`。
6. Repository 把工具结果作为 continuation 输入，再次调用 Agent。
7. Agent 可继续请求更多工具，也可生成最终 reply。
8. 工具预算耗尽或模型不再请求工具时结束。

当前预算：

- `maxPasses = 6`
- `maxToolCalls = 12`
- `maxSearchCalls = 4`
- `maxBrowseCalls = 8`

这些预算在 `ActMeRepository.ToolBudget` 中定义。

## 可见、可继续、可控、可中止

聊天气泡中会展示执行过程，例如：

```text
执行过程：
1. [OK] Agent planning pass 1 - 2 tool call(s) requested
2. [OK] 联网搜索 - 搜索完成，...
3. [OK] 打开网页 - 网页内容已读取，...
4. [OK] Agent observes results - reply=..., next tools=...

---
最终回复...
```

控制策略：

- 发送中按钮变成停止按钮。
- 点击停止会 cancel 当前 coroutine job。
- 工具执行循环检查 cancellation。
- 被取消时消息显示“已中止。”。
- 若预算耗尽但仍有后续工具请求，消息显示暂停原因，并提示用户可发送“继续”。

## 工具调用自由度

Agent 有较高自由度：

- 可以决定是否搜索。
- 可以决定是否继续浏览网页。
- 可以决定调用几个工具、按什么顺序调用。
- 简单问题可以直接回答。
- 复杂、近期、不确定或需要来源支撑的问题，可以多次搜索和浏览多个不重复页面。

系统提示会建议优先使用官网、一手来源、公告、产品说明、新闻原文、文档、价格页等更可靠来源，但不会把所有任务都强制成固定流程。

## 搜索结果和网页阅读展示

工具结果会存入 `chat_messages.searchResult`，UI 根据内容类型显示不同入口：

- 只有搜索：`🔍 展开搜索结果`
- 只有网页阅读：`📖 展开网页阅读内容`
- 搜索和网页阅读都有：`🌐 展开联网资料`

弹窗中会将内部标记转换为用户可读标题：

- `[BROWSE_RESULT]` -> `【网页阅读内容】`
- `[BROWSE_ERROR]` -> `【网页阅读失败】`

## JSON 解析兜底

模型有时会输出非严格 JSON，例如：

- `reply` 内出现未转义引号。
- 输出包含 Markdown code fence。
- `system_call` 写成单对象而不是数组。
- 使用 `tool_calls`、`tool_call`、`calls` 等别名。
- 输出 OpenAI tool call 风格的 `function.arguments`。

`ActMeAgent.parseRaw` 的处理顺序：

1. 严格 JSON 解析。
2. 宽松解析候选：
   - 提取 JSON 主体。
   - 提取 code fence。
   - 宽松抽取 `reply` 字段。
   - 宽松抽取 system calls。
3. 仍失败时，才把 raw 文本作为普通 reply。

`ReplyExtractor` 负责流式阶段从 JSON 中增量抽取 `reply`。它对 `reply` 内部未转义引号做了容错，只有当引号后面看起来是下一个 JSON 字段或对象结束时，才认为 reply 结束。

## Token usage

`OpenAiResponsesClient` 支持 API usage：

- OpenAI-compatible streaming 请求会尝试加入 `stream_options.include_usage=true`。
- 如果兼容接口不支持该参数，会自动重试不带 usage，避免聊天失败。
- Anthropic-compatible streaming 会从 message usage 事件读取 `input_tokens` / `output_tokens`。
- 多步执行中，每一次 LLM 调用的 usage 会累加到最终 assistant 消息。

Room 中相关字段：

- `tokenInput`
- `tokenOutput`
- `tokenTotal`
- `tokenSource`

UI 只在 assistant 消息下显示 token，不显示用户输入消息 token。

## 记忆更新

Agent 可通过 `memory_updates` 写入个人记忆。记忆分类来自 `MemoryCategories.writable`：

- 短期目标
- 长期目标
- 个人焦虑
- 近期烦恼
- 个人喜好
- 人际关系
- 健康状态
- 学习工作

系统分类不允许由 Agent 写入。

## 日程更新

Agent 可通过 `schedule_updates` 产生日程候选。为了降低错误创建提醒的风险，Repository 会把候选和用户原始需求交给日程子 Agent，再生成最终结构：

- `repeat_type`: `NONE` / `DAILY` / `WEEKLY` / `MONTHLY`
- 一次性提醒需要日期和时间。
- 每日提醒需要时间。
- 每周提醒需要星期和时间。
- 每月提醒需要日期和时间。

保存后由 `ReminderScheduler` 注册 Android 闹钟提醒。开机后 `BootReceiver` 会恢复提醒。

## Skills

Skills 来源：

- 预置 assets。
- 构建时从 `~/.codex/skills/` 导入。
- Agent 在对话中通过 `skill_updates` 新增。

Skills 会作为上下文注入 Agent，帮助它形成可复用行为。

## 失败和降级策略

- 单个工具失败不会直接终止整轮 Agent，而是返回 `[TOOL_ERROR]` 或 `[BROWSE_ERROR]`，让模型基于已有信息继续。
- 网页读取失败会尝试 HTTP fallback。
- 搜索后端按顺序尝试，前一个不可用时尝试后一个。
- JSON 解析失败会进行宽松解析。
- usage 获取失败不会影响聊天。

## 调试

常用 logcat 过滤：

```powershell
adb logcat -v time | Select-String -Pattern "ActMe|SystemSkillExecutor|GeckoSearchEngine|BROWSE|SEARCH|system_calls|parseRaw"
```

重点日志：

- `ActMeRepository`: 对话开始、Agent 结果、pass 结果、任务完成。
- `ActMeAgent`: JSON 解析失败、宽松解析。
- `SystemSkillExecutor`: 工具执行、搜索后端、浏览 URL。
- `GeckoSearchEngine`: GeckoView 加载、扩展消息、渲染文本长度。
- `ActMeLlmClient`: LLM 请求、stream usage fallback。

## 已知限制

- 一些网站要求登录、App 内访问或强风控，公开网页无法读取完整内容。
- Bing Gecko 返回的是搜索页渲染文本，模型需要自行抽取链接或还原面包屑 URL。
- OpenAI-compatible 网关不一定支持 streaming usage，App 会自动重试但无法拿到精确 token。
- 当前工具预算是静态配置，尚未按任务复杂度动态调整。
- Agent 仍依赖模型遵循 JSON 协议，尽管已有多层解析兜底。
