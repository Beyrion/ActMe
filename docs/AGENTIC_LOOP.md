# Agentic Loop 设计

ActMe 的 Agentic Loop 是让 Agent 在一次用户请求中多轮调用工具、观察结果、继续决策的执行框架。它的目标是支持复杂任务，而不是只做单轮问答。

## 目标

Agentic Loop 解决的问题：

- 用户的问题可能需要多步完成。
- 联网结果需要继续打开网页验证。
- 网页文本需要 Python 清洗和计算。
- ADB 操作需要观察当前 UI，再执行点击/输入，再继续观察。
- 工具调用失败时需要降级或换路径。

因此 ActMe 允许 Agent 在一次回复过程中反复执行：

```text
思考 -> system_calls -> App 执行工具 -> 观察结果 -> 继续思考 -> 更多工具或最终回复
```

## 核心流程

主要代码位置：

```text
app/src/main/java/com/actme/app/data/repo/ActMeRepository.kt
app/src/main/java/com/actme/app/data/agent/ActMeAgent.kt
app/src/main/java/com/actme/app/data/agent/SystemSkillExecutor.kt
```

职责划分：

- `ActMeAgent`：构造提示词、调用 LLM、解析 JSON 输出和 system_calls。
- `ActMeRepository`：驱动多步循环、管理工具预算、写入聊天消息和步骤展示。
- `SystemSkillExecutor`：执行具体工具，包括浏览器、Python、ADB、时间等。

## Agent 输出协议

Agent 每轮输出 JSON：

```json
{
  "reply": "",
  "memory_updates": [],
  "schedule_updates": [],
  "skill_updates": [],
  "system_calls": [
    {
      "type": "web_search",
      "query": "搜索内容"
    }
  ]
}
```

如果 `system_calls` 非空，`reply` 可以为空。App 会先执行工具，再把结果作为下一轮输入交给模型。

## 多步执行

一次用户消息可能触发多个 pass：

1. 第一轮模型判断需要工具。
2. App 执行工具。
3. App 把工具结果拼入 continuation input。
4. 模型基于工具结果继续输出：
   - 继续调用更多工具。
   - 或生成最终回复。
5. 达到工具预算或信息足够后停止。

工具结果会用分隔符拼接，避免不同工具输出混淆。

## 工具预算

为了防止无限循环，ActMe 对工具调用做预算控制：

- 总工具调用数。
- 搜索调用数。
- 浏览调用数。
- 多步 pass 上限。

当预算耗尽时，App 会暂停工具循环，让 Agent 基于已有结果给出当前最好回答。若还有后续工具请求，用户可以发送“继续”让任务继续执行。

## 去重策略

ActMe 会避免重复执行明显相同的工具调用：

- 相同 `web_search.query` 不重复搜索。
- 相同 `browse_url.url` 不重复浏览。
- 相同 Python 代码和输入不重复执行。
- 相同 ADB 命令不重复执行，除非 Agent 给出新命令或用户继续要求。

去重的目标是减少死循环和无效消耗。

## 可见执行步骤

Agentic Loop 的每一步都会进入聊天 UI 的工具执行气泡：

- 工具开始。
- 工具完成。
- 工具失败。
- 工具被跳过。
- 工具循环暂停。

这让用户能看到 Agent 正在做什么，也方便调试。

## 可中止

用户可以在执行过程中中止当前任务。中止后：

- 当前 coroutine 会取消。
- 工具执行气泡显示已中止。
- 不再继续调用后续工具。

这对联网、浏览、Python 和 ADB 都很重要，因为这些操作可能耗时或产生副作用。

## 文件输出收集

从 1.2.0 开始，Agentic Loop 把生成文件作为一等输出处理。任意 pass 的任意工具都可能产生文件，尤其是 `python_exec`。

处理策略：

- `SystemSkillExecutor` 合并模型声明的 `output_files` / `generated_files` / `expected_outputs` / `files`。
- Python 执行器会自动检测 `agent_workspace` 中新增或修改的文件。
- `ActMeRepository` 在每一轮工具执行结束后立即收集文件引用。
- 最终回复前再次扫描累计工具结果和最终 reply。
- 使用有序集合去重，避免重名路径重复显示。
- Chat UI 会从消息正文和隐藏的工具结果中识别文件，并显示打开按钮。

这意味着即使 Agent 没有在最终自然语言回复中提到某个文件，只要工具真实生成并返回到 observation，最终聊天气泡也应显示文件入口。

## 失败降级

工具失败时，不应该把内部 JSON 或异常堆栈直接暴露给用户。ActMe 的策略是：

- 工具返回结构化错误。
- Agent 观察错误后尝试换工具、换来源或降级回答。
- 最终回复中只说明用户需要知道的失败原因。

例如：

- Bing HTML 失败，可以尝试 Bing Gecko 或其他搜索后端。
- GeckoView 读不到正文，可以尝试 HTTP 文本抽取。
- Python 报语法错误，可以修改代码后再次执行。
- ADB 连接失败，提示用户重新测试并保存连接端口。

## 与三驾马车的关系

Agentic Loop 是三驾马车的调度层：

- 内置浏览器负责信息获取。
- 内置 Python 负责确定性处理。
- 内置 ADB 负责本机观察和操作。

Loop 让它们可以组合成连续工作流，而不是孤立工具。

## 设计边界

当前实现是低配版 agentic workflow，不是完整任务队列系统：

- 没有跨进程任务恢复。
- 没有长期后台无人值守执行。
- 工具预算较保守。
- 高风险操作仍需要用户确认和可见步骤。

这个设计适合移动端个人 Agent：可见、可控、可暂停、可继续。
