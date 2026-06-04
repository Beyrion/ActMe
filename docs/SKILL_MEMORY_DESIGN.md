# Skill 与 Memory 设计

ActMe 的 Skill 与 Memory 是 Agent 个性化和长期可用性的基础。Memory 负责记录用户长期信息，Skill 负责沉淀可复用做事方法。

## 设计目标

Memory 与 Skill 分别解决不同问题：

- Memory：用户是谁、有什么偏好、目标、关系、健康状态、近期事项。
- Skill：遇到某类任务时，Agent 应该采用什么固定流程、检查表或行动模板。

二者共同让 ActMe 从“每轮重新理解用户”变成“持续适应用户”的个人 Agent。

## 数据位置

主要代码位置：

```text
app/src/main/java/com/actme/app/data/local/Entities.kt
app/src/main/java/com/actme/app/data/local/Daos.kt
app/src/main/java/com/actme/app/skills/MemorySeeder.kt
app/src/main/java/com/actme/app/skills/SkillSeeder.kt
app/src/main/java/com/actme/app/data/agent/ActMeAgent.kt
app/src/main/java/com/actme/app/data/repo/ActMeRepository.kt
```

当前 Memory 和 Skill 都存储在本地 Room 数据库。

## Memory 数据模型

Memory 使用 `MemoryItemEntity`：

```kotlin
data class MemoryItemEntity(
    val id: Long,
    val category: String,
    val content: String,
    val source: String,
    val updatedAt: Long
)
```

典型分类包括：

- 系统
- 短期目标
- 长期目标
- 个人焦虑
- 近期烦恼
- 个人偏好
- 人际关系
- 健康状态
- 学习工作

其中“系统”类 memory 用于 App 身份和固定能力说明，一般不允许 Agent 随意写入。

## Memory 写入

Agent 可以在输出 JSON 中写入：

```json
{
  "memory_updates": [
    {
      "category": "长期目标",
      "content": "用户希望长期提升英语口语能力。"
    }
  ]
}
```

Repository 接收后写入本地数据库。

Memory 写入原则：

- 只记录长期有用的信息。
- 不把一次性闲聊全部写入。
- 不写入过于敏感或不确定的信息。
- 不重复记录已存在的明显相同内容。

## Memory 注入

每轮对话前，ActMe 会把部分 memory 注入 system prompt：

- 系统 memory 优先注入。
- 用户 memory 取最近或最相关的一部分。
- 注入内容控制数量，避免 prompt 过长。

注入格式类似：

```text
用户记忆：
- [长期目标] 用户希望长期提升英语口语能力。
- [个人偏好] 用户喜欢直接、可执行的建议。
```

## Skill 数据模型

Skill 使用 `SkillEntity`：

```kotlin
data class SkillEntity(
    val id: Long,
    val name: String,
    val description: String,
    val triggerKeywords: String,
    val actionTemplate: String,
    val enabled: Boolean,
    val createdAt: Long
)
```

当前 Skill 是轻量结构：

- `name`：技能名。
- `description`：用途描述。
- `triggerKeywords`：触发关键词，JSON 字符串数组。
- `actionTemplate`：触发后追加给用户或 Agent 的行动模板。
- `enabled`：是否启用。

## Skill 写入

Agent 可以输出：

```json
{
  "skill_updates": [
    {
      "name": "考试复习计划",
      "description": "当用户提到考试和复习时，生成分阶段计划。",
      "trigger_keywords": ["考试", "复习", "备考"],
      "action_template": "先确认考试日期和科目，再拆分每日复习任务，并设置提醒。"
    }
  ]
}
```

Repository 将其保存为本地 Skill。

## Skill 注入

每轮对话前，ActMe 会注入启用的 Skill 列表：

```text
当前技能：
- 考试复习计划: ["考试","复习","备考"]
```

此外，Repository 会根据用户输入匹配关键词，触发本地 skill hint，把 `actionTemplate` 追加到最终回复或过程上下文。

## 与 Claude/Codex Skill 的关系

当前 Skill 模型是轻量版，不等于 Claude/Codex 的目录型 skill。

Claude/Codex 风格 skill 通常包含：

- `SKILL.md`
- scripts
- references
- templates
- assets

ActMe 当前已有工具 `tools/adapt_claude_skills.py`，可以下载 Claude-style skill 并转换为当前 App 能识别的轻量 preload JSON，同时保留完整 skill 内容到 assets。

未来更完整的方案应该扩展 Skill schema：

- `source`
- `content`
- `resourcePath`
- `version`
- `format`
- `enabled`

并在 prompt 构造时按任务相关性注入完整 `SKILL.md` 或摘要。

## Skill 与 Memory 的区别

Memory 记录“关于用户的事实”：

```text
用户每周三晚上有英语课。
用户喜欢表格式计划。
用户近期在准备期末考试。
```

Skill 记录“做事方法”：

```text
当用户要求备考计划时，先确认考试日期，再拆每日任务，再设置提醒。
```

简单判断：

- 这是用户个人信息 -> Memory。
- 这是可复用流程 -> Skill。

## 与 Agentic Loop 的关系

Memory 和 Skill 是 Agentic Loop 的上下文层：

- Memory 告诉 Agent 用户是谁、偏好什么、当前目标是什么。
- Skill 告诉 Agent 遇到某类任务时应该如何行动。
- Agentic Loop 根据这些上下文决定是否调用浏览器、Python、ADB 或日程能力。

## 学生场景示例

Memory：

```text
[长期目标] 用户准备考研英语。
[学习工作] 用户每周一、三、五晚上复习数学。
[个人偏好] 用户希望计划以每日 checklist 展示。
```

Skill：

```text
考试复习计划：先确认考试日期、科目、当前水平，再生成每日计划和提醒。
论文资料整理：先联网搜索多个来源，再按主题提炼观点和引用链接。
Excel 数据分析：先读取表格结构，再用 Python 汇总统计，最后生成结果文件。
```

## 风险与边界

- Memory 不应过度记录敏感信息。
- Skill 不应沉淀错误流程。
- 用户应该能查看、删除和禁用 memory/skill。
- 长期应支持去重、合并、过期和可信来源标记。

Memory 和 Skill 的核心价值是让 Agent 更像“长期助手”，而不是一次性问答模型。
