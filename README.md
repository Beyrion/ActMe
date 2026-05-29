# ActMe (Android, 中文)

ActMe 是一个离线本地优先（除 OpenAI API 调用外）的 Android 应用，包含：

- ActMe Agent 聊天
- 个人记忆分类管理（短期目标/长期目标/焦虑/烦恼/喜好/人际关系等）
- 日历日程与定时提醒弹窗
- 推送详情页（提醒完整内容 + Agent 补充信息）
- 本地 Skills（可预置，也可由 Agent 在对话中新增）

## 本地构建

### 前置条件

1. Android Studio（支持 AGP 8.5.2+ / JDK 17）
2. NDK 27.2.12479018（通过 SDK Manager → SDK Tools → NDK 安装）

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

1. **`importCodexSkills`**：从 `~/.codex/skills/` 扫描 SKILL.md 文件 → 导入到 `assets/skills/codex_import/`
2. **`buildMnn`**：用 CMake + NDK 交叉编译 MNN（LLM + Omni + OpenCL + Diffussion + OpenCV）→ 输出 `libMNN.so` 到 `jniLibs/arm64-v8a/`

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
