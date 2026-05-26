# ActMe (Android, 中文)

ActMe 是一个离线本地优先（除 OpenAI API 调用外）的 Android 应用，包含：

- ActMe Agent 聊天
- 个人记忆分类管理（短期目标/长期目标/焦虑/烦恼/喜好/人际关系等）
- 日历日程与定时提醒弹窗
- 推送详情页（提醒完整内容 + Agent 补充信息）
- 本地 Skills（可预置，也可由 Agent 在对话中新增）

## 关键实现点

- 模型配置已写入 `BuildConfig`：
  - `model_provider = "Model_Studio_Token_Plan"`
  - `model = "qwen3.6-plus"`
  - `model_reasoning_effort = "medium"`
  - `disable_response_storage = true`
  - `preferred_auth_method = "apikey"`
  - `base_url = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"`
  - `wire_api = "responses"`
  - `requires_openai_auth = false`
- 构建时从 `~/.codex/auth.json` 读取 `OPENAI_API_KEY` 并加密打包进 `assets/secure/bundled_auth.enc`。
- App 运行后会解密并保存到 `EncryptedSharedPreferences`，后续 API 调用只走本地密钥。

## 本地构建前准备

1. 安装 Android Studio（建议新版，支持 AGP 8.6+ / JDK 17）。
2. 确保存在 `~/.codex/auth.json`，示例字段：
   - `OPENAI_API_KEY`
3. 在项目根目录 `local.properties` 增加（推荐）：

```properties
actme.packKey=请替换为你自己的高强度打包口令
```

也可使用环境变量 `ACTME_PACK_KEY`。

## 运行说明

- 首次启动会自动导入预置 skills（`app/src/main/assets/skills/preload_skills.json`）。
- 聊天时 Agent 会按需返回：
  - 回复文本
  - `memory_updates`
  - `schedule_updates`
  - `skill_updates`
- 新建日程后会自动注册精确提醒；提醒到点弹窗通知，点击进入推送详情页。

## 安全提示

- “加密打包入 App”只能提升静态提取门槛，无法等同服务端保密。
- 生产环境建议改为：首次启动让用户输入/扫码注入 Key，且不在包体内放置可还原凭据。
