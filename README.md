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

## 本地构建

### 前置条件

1. Android Studio（支持 AGP 8.5.2+ / JDK 17）
2. NDK 27.2.12479018（通过 SDK Manager → SDK Tools → NDK 安装）
3. `~/.codex/auth.json`，内容示例：

```json
{
  “OPENAI_API_KEY”: “sk-your-key-here”
}
```

4. 项目根目录 `local.properties` 中设置打包密钥（推荐）：

```properties
actme.packKey=请替换为你自己的高强度打包口令
```

也可使用环境变量 `ACTME_PACK_KEY`。

### 克隆项目

```bash
git clone --recursive git@github.com:huangzhengxiang/ActMe.git
```

### 构建 APK

```bash
# Debug 构建（自动执行 Key 加密 + Codex Skills 导入）
./gradlew assembleDebug

# 或只构建 MNN 原生库（CMake + NDK 交叉编译 arm64-v8a）
./gradlew buildMnn
```

### 推送 ASR 模型到设备

```bash
./gradlew pushAsrModel
```

模型也可在 App 内通过设置页面下载。

### 构建流程说明

构建时 `app/build.gradle.kts` 会自动执行：

1. **`bundleCodexAuth`**：从 `~/.codex/auth.json` 读取 API Key → PBKDF2 + AES-CBC 加密 → 输出到 `assets/secure/bundled_auth.enc`
2. **`importCodexSkills`**：从 `~/.codex/skills/` 扫描 SKILL.md 文件 → 导入到 `assets/skills/codex_import/`
3. **`buildMnn`**：用 CMake + NDK 交叉编译 MNN（LLM + Omni + OpenCL + Diffussion + OpenCV）→ 输出 `libMNN.so` 到 `jniLibs/arm64-v8a/`

这些 task 通过 `preBuild` 依赖自动串联。

## 运行说明

- 首次启动会自动导入预置 skills（`app/src/main/assets/skills/preload_skills.json`）。
- 聊天时 Agent 会按需返回：
  - 回复文本
  - `memory_updates` — 自动写入记忆库
  - `schedule_updates` — 经子Agent 二次结构化后创建提醒
  - `skill_updates` — Agent 在对话中新增 Skill
- 新建日程后自动注册精确闹钟提醒；到点弹全屏通知，点击进入推送详情页。
- 语音输入需先下载 ASR 模型（约 1.3 GB），下载后完全离线运行。
- 开机自启动恢复所有提醒（`BootReceiver`）。

## 安全提示

- “加密打包入 App”只能提升静态提取门槛，无法等同服务端保密。
- 生产环境建议改为：首次启动让用户输入/扫码注入 Key，且不在包体内放置可还原凭据。
