# ActMe

ActMe 是一个面向中文个人行动管理的 Android 应用。它把聊天 Agent、个人记忆、日程提醒、本地语音识别、Skills 和联网浏览能力放在同一个移动端工作流里，目标是让用户用自然语言完成记录、查询、规划和执行。

项目以本地优先为主：记忆、日程、Skills、聊天记录、提醒和 ASR 都保存在本机；LLM 对话通过用户配置的 OpenAI-compatible 或 Anthropic-compatible API 完成；联网搜索和网页阅读由系统工具按需执行。

## 核心功能

- **ActMe Agent 聊天**：中文对话入口，支持文本、图片、语音输入和 Markdown 展示。
- **多步工具执行**：Agent 可以在一次回复中多轮调用系统能力，观察结果后继续搜索、浏览网页或获取时间。
- **联网搜索与网页阅读**：支持 `web_search` 搜索，以及 `browse_url` / `browser_url` 使用内置 GeckoView 浏览器读取网页渲染文本。
- **可见、可控、可中止**：工具步骤在聊天中可见，发送中可点击停止按钮中止当前任务。
- **联网资料展示**：搜索结果和网页阅读内容会作为可展开资料显示，区分“搜索结果”“网页阅读内容”和“联网资料”。
- **Token 用量展示**：assistant 消息下方显示 API 返回的输入、输出、总 token；API 不返回 usage 时退回本地估算。
- **个人记忆管理**：支持短期目标、长期目标、个人焦虑、近期烦恼、喜好、人际关系、健康状态、学习工作等分类。
- **日程与提醒**：Agent 可生成日程候选，经子 Agent 二次结构化后保存，并注册 Android 提醒。
- **本地 Skills**：预置 Skills 会在首次启动时导入；构建时也可从本机 Codex Skills 导入。
- **本地 ASR**：使用 Qwen3-ASR MNN 模型进行离线语音识别，模型可通过 App 下载或 Gradle task 推送。
- **内置浏览器调试入口**：设置页提供“内置浏览器”入口，用于直接验证 GeckoView 页面加载和渲染文本读取。

## 文档

- [Agent.md](Agent.md)：ActMe Agent 的详细设计，包括系统提示、工具协议、多步执行、联网搜索、内置浏览器、JSON 解析兜底、token usage 和 UI 展示。

## 技术栈

- Kotlin 1.9.24
- Android Gradle Plugin 8.5.2
- Jetpack Compose + Material 3
- Room
- Kotlin Coroutines / Flow
- Kotlinx Serialization
- OkHttp
- GeckoView
- MNN / Qwen3-ASR
- Markdown Renderer for Compose

## 项目结构

```text
app/src/main/java/com/actme/app/
  data/
    agent/            ActMeAgent、系统工具执行器、GeckoView 搜索/浏览封装
    local/            Room entities、DAO、database migrations
    remote/           OpenAI-compatible / Anthropic-compatible API 客户端
    repo/             ActMeRepository，连接 UI、Agent、数据库和提醒系统
  ui/
    chat/             聊天主界面、消息气泡、语音输入、资料展开面板
    settings/         模型、API provider、ASR、内置浏览器入口
    memory/           记忆管理
    schedule/         日程管理
  audio/              本地 ASR 管理
  mnn/                MNN 模型加载和推理封装
  notifications/      提醒调度、通知和开机恢复
```

## 本地构建

### 前置条件

1. Android Studio，JDK 17。
2. Android SDK 34。
3. NDK `27.2.12479018`。
4. CMake `3.22.1` 或 Android SDK 中可用的 CMake。
5. Git submodule 已初始化，尤其是 `MNN`。

### 克隆项目

```bash
git clone --recursive git@github.com:huangzhengxiang/ActMe.git
cd ActMe
```

如果已经克隆但没有拉取 submodule：

```bash
git submodule update --init --recursive
```

### 构建 APK

仓库当前未提交 Gradle wrapper 时，可以使用本机 Gradle：

```bash
gradle assembleDebug
```

如果你在本地添加了 Gradle wrapper，也可以使用：

```bash
./gradlew assembleDebug
```

### 构建 MNN 原生库

```bash
gradle buildMnn
```

`buildMnn` 会用 CMake + NDK 交叉编译 arm64-v8a 的 `libMNN.so`，并复制到 App 的 `jniLibs/arm64-v8a/`。

### 推送 ASR 模型到设备

```bash
gradle pushAsrModel
```

模型路径：

```text
model/Qwen3-ASR-0.6B-INT8-MNN
```

设备路径：

```text
/sdcard/actme/models/Qwen3-ASR-0.6B-INT8-MNN
```

也可以在 App 设置页中下载模型。

## 构建流程说明

`app/build.gradle.kts` 定义了两个 ActMe 相关 task：

- `importCodexSkills`：从 `~/.codex/skills/` 扫描 `SKILL.md`，导入到构建生成的 assets。
- `buildMnn`：编译 MNN 原生库。

`preBuild` 会自动依赖 `importCodexSkills`。`buildMnn` 可单独执行；如需要强制重建 MNN，可结合项目已有 Gradle 参数或清理 MNN build 目录。

## 运行配置

首次启动后，在设置页配置 API provider：

- Provider format: `openai` 或 `anthropic`
- Endpoint: 例如 `https://api.openai.com/v1`
- API Key
- Model

OpenAI-compatible provider 使用 `/chat/completions`；Anthropic-compatible provider 使用 `/messages`。

## Agent 工作流概览

聊天发起后，Repository 会把当前输入、历史消息、记忆、日程、Skills 和系统提示传给 ActMeAgent。Agent 输出 JSON，可能包含：

```json
{
  "reply": "给用户的中文回复",
  "memory_updates": [],
  "schedule_updates": [],
  "skill_updates": [],
  "system_calls": []
}
```

当 `system_calls` 非空时，App 会执行系统工具，并把结果返回给 Agent 继续生成下一步。当前系统能力包括：

- `get_current_time`
- `web_search`
- `browse_url`
- `browser_url`，作为 `browse_url` 的兼容别名
- `web_browse` / `open_url`，作为浏览网页兼容别名

详细设计见 [Agent.md](Agent.md)。

## 调试建议

常用 logcat 过滤：

```bash
adb logcat -v time | grep -E "ActMe|SystemSkillExecutor|GeckoSearchEngine|BROWSE|SEARCH|system_calls"
```

Windows PowerShell：

```powershell
adb logcat -v time | Select-String -Pattern "ActMe|SystemSkillExecutor|GeckoSearchEngine|BROWSE|SEARCH|system_calls"
```

设置页的“内置浏览器”入口可用于验证 GeckoView 是否能加载网页、安装 WebExtension、返回渲染文本。

## 注意事项

- 金融、银行、价格、政策等信息应优先让 Agent 搜索并浏览权威来源；但 Agent 会根据任务复杂度和用户意图自主决定工具调用数量。
- 搜索和网页阅读结果是辅助资料，不应直接等同于最终事实；最终回复由 Agent 综合工具结果生成。
- 部分网页需要登录、强风控或 App 内渠道，内置浏览器可能只能读取公开页面文本。
- Token usage 优先使用 API 返回值；不支持 usage 的兼容接口会退回估算。
- 本地 ASR 模型体积较大，首次下载或推送需要较长时间。
