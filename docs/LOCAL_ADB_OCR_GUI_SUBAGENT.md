# 本地 ADB + OCR + GUI Sub-Agent 链路

这条链路是 ActMe 在端侧 Agent 执行能力上的一次探索：让云端主 Agent 负责意图理解、任务拆解和失败后的修正，让端侧 App 负责设备连接、截图感知、视觉判断和真实 UI 操作。它不是把手机变成一个简单的 `adb shell` 终端，而是把无线 ADB、端侧 OCR、端侧视觉模型和 Agentic Loop 串成一个可见、可中止、可复盘的执行闭环。

## 设计目标

- 降低无线 ADB 配对门槛：用户只需要在系统无线调试页面截图，App 监听图库新增图片并尝试自动识别配对信息。
- 把 OCR 留在端侧：GLM-OCR-MNN 只做图片文字转写，规则代码再从文本中解析设备名、IP、连接端口、配对端口和配对码。
- 区分规划与执行：云端主 Agent 不直接看屏幕，只写 `gui_agent.plan`、`target_text` 和必要的 `guidance`；本地 GUI 子 Agent 看截图并执行当前一步。
- 避免手写 ADB 点控链：第三方 App 操作必须走 `gui_agent`，`adb_shell` 主要用于诊断、只读观察、日志、包名、截图和用户明确要求的低层命令。
- 保留端侧安全边界：执行过程显示 GUI 悬浮提示，步骤进入聊天工具气泡，失败结果返回主 Agent 继续修正，而不是静默乱点。

## 总体链路

```text
用户提出手机 GUI 任务
  -> 云端主 Agent 判断需要 gui_agent
  -> 写入 command / plan / target_text / guidance
  -> SystemSkillExecutor 调用 GuiSubAgent
  -> GuiSubAgent 检查无线 ADB 是否可用
      -> 不可用：启动截图监听，打开开发者设置，引导用户截图无线调试配对页
      -> 可用：截图、调用端侧 GUI-Owl、解析动作、执行 ADB 操作
  -> 返回 [GUI_AGENT_RESULT] / [GUI_AGENT_ACTION_ERROR] / [GUI_AGENT_NEEDS_ADB]
  -> 主 Agent 根据观察结果继续规划或给出最终回复
```

这条链路的关键不在于某一次点击成功，而在于把“看见手机屏幕 - 决定下一步 - 执行动作 - 再观察”的循环放进移动端 App 内部。ActMe 因此开始具备端侧 Agent 的基础形态：本地有感知、本地有执行、本地有可见状态，云端只承担语言层面的计划和纠错。

## 无线 ADB 截图配对

相关代码：

```text
app/src/main/java/com/actme/app/data/agent/AdbPairingScreenshotService.kt
app/src/main/java/com/actme/app/data/agent/AdbPairingScreenshotWatcher.kt
app/src/main/java/com/actme/app/image/LocalImageImportManager.kt
app/src/main/java/com/actme/app/data/agent/AdbSkillEngine.kt
app/src/main/java/com/actme/app/ui/settings/SettingsScreen.kt
```

设置页的“内置 ADB”不再只弹出手动配对窗口，而是启动图库新增截图监听，并尝试打开系统开发者设置。用户在无线调试配对详情页截图后，`AdbPairingScreenshotWatcher` 会：

1. 记录启动时最新图片作为 baseline。
2. 通过 `MediaStore` observer 和轮询检测新的图片。
3. 把新图复制到 App 私有目录，并按 OCR 模型输入限制缩放。
4. 调用 `LocalImageImportManager.extractText()` 使用 GLM-OCR-MNN 做端侧 OCR。
5. 用确定性规则解析 ADB 连接信息。
6. 调用 `AdbSkillEngine.pair()` 完成配对，再调用 `testConnection()` 测试并保存连接端口。

OCR 模型不负责“理解 ADB 配对流程”。它只输出可见文字；设备名称、地址、端口和配对码由规则解析。这一点很重要：端侧视觉模型负责感知，确定性代码负责高风险连接参数提取，减少模型幻觉进入 ADB 连接层的机会。

## GUI Sub-Agent 执行器

相关代码：

```text
app/src/main/java/com/actme/app/data/agent/GuiSubAgent.kt
app/src/main/java/com/actme/app/ui/GuiAgentOverlayService.kt
app/src/main/java/com/actme/app/data/agent/AdbKeyboardInput.kt
app/src/main/java/com/actme/app/data/agent/SystemSkillExecutor.kt
```

`GuiSubAgent` 是本地视觉执行器。它接收主 Agent 的任务、计划、固定输入文本和纠错指导，然后在端侧循环执行：

```text
检查 ADB
  -> 必要时预启动目标 App
  -> ADB 截图到本地文件
  -> 构造带截图的 GUI-Owl prompt
  -> 模型返回单个 JSON 动作
  -> 归一化坐标映射到真实像素
  -> ADB 执行 tap / swipe / keyevent / 输入
  -> 记录结果并进入下一步
```

当前允许的动作包括 `open_app`、`click`、`long_press`、`swipe`、`type`、`system_button`、`wait`、`answer` 和 `terminate`。每一轮只允许一个动作，避免模型一次性输出一串不可验证的操作。

输入文本是单独的安全通道。主 Agent 必须把目的地、搜索词、消息内容等写入 `target_text`；本地 GUI 子 Agent 在执行 `type` 时会强制使用 `target_text`，即使视觉模型输出了不同文本也会覆盖。对于中文或复杂文本，`AdbKeyboardInput` 会优先尝试普通 `input text`，失败后安装并切换随包携带的 ADBKeyboard，通过 base64 broadcast 输入，再恢复原输入法。

## 主 Agent 协议变化

`SystemCall` 新增字段：

```json
{
  "type": "gui_agent",
  "command": "Open Amap and navigate to destination",
  "plan": "1. Open package com.autonavi.minimap from launcher.\n2. Tap the search box.\n3. Type target_text exactly.",
  "target_text": "华东师范大学普陀校区",
  "guidance": "",
  "timeout_ms": 120000
}
```

`ActMeAgent` 的系统提示中明确了分工：

- `adb_shell` 只做诊断、只读观察、日志、包列表、设置查询、截图，或者用户明确要求的低层 ADB 命令。
- 任何操作第三方 App UI 的任务都必须走 `gui_agent`。
- 主 Agent 是 planner，不能假装看见屏幕；本地 GUI 子 Agent 是 visual executor，负责根据截图执行当前一步。
- GUI 子 Agent 失败后，主 Agent 应阅读返回的 `[GUI_AGENT_ACTION_ERROR]` 或 `[GUI_AGENT_ERROR]`，更新 plan/guidance 后再次调用，而不是直接放弃。

为了提高启动成功率，主 Agent 在检测到“打开、进入、点击、导航、搜索、发消息、设置、地图、微信、支付宝”等 GUI 意图时，会注入常见国产 App 包名参考，要求 `gui_agent.plan` 的第一步写清楚目标包名。端侧执行器也会从任务和计划中推断包名并预启动目标 App。

## 可见性与调试

这条链路强调可观察，而不是把自动化藏起来：

- ADB 配对截图监听以前台服务通知展示。
- GUI 执行时右上角显示 `ActMe GUI running` / `ActMe GUI step N` 悬浮提示。
- `SystemSkillExecutor` 在聊天气泡里显示 `Run GUI Agent`。
- `GuiSubAgent` 记录模型输入、模型输出、截图尺寸、坐标缩放、执行结果和错误。
- 坐标既支持 0-1000 归一化，也支持绝对像素；执行前统一映射到真实截图尺寸并写日志。

这些日志让端侧 Agent 的失败可复盘：到底是主 Agent 计划错、视觉模型看错、坐标映射错、ADB 操作失败，还是目标 App 状态变化导致流程卡住。

## 端侧 Agent 探索意义

当前链路的探索性体现在四个层面：

1. 感知本地化：截图 OCR 和 GUI 视觉判断发生在手机端，用户屏幕不必作为原始图像交给云端主 Agent。
2. 执行本地化：点击、输入、返回、截图和应用启动通过本地无线 ADB 完成，形成真正的端侧执行通道。
3. 规划分层：云端模型做语言规划和失败修正，端侧模型做视觉一步执行，降低单个模型同时承担“理解需求 + 看图 + 控制设备”的复杂度。
4. 安全收敛：高权限 ADB 被约束在工具层，第三方 App GUI 操作被收敛到 `gui_agent`，输入文本被 `target_text` 固定，失败返回结构化观察供下一轮修正。

因此，这不是传统脚本自动化，也不是纯云端 mobile-use。它更接近一个移动端个人 Agent 的早期原型：在本机拥有感知、连接、执行、提示和日志，能够围绕真实 App 状态做多步闭环。

## 当前边界

- 无线 ADB 仍依赖用户主动开启无线调试、授予图片读取权限，并在配对页截图。
- OCR 对系统页面布局、截图清晰度和端口文本可见性敏感；规则解析会拒绝缺失字段或配对端口等于连接端口的异常结果。
- GUI-Owl 每轮只执行一步，复杂任务需要多轮截图和模型调用，速度不是脚本级别。
- 坐标点击仍可能受弹窗、动画、输入法、系统权限页遮挡影响。
- 高风险 ADB 命令仍应保留用户确认，不应让 GUI Agent 绕过用户授权边界。

这些边界是当前设计刻意保留的约束。ActMe 现阶段追求的是可控的端侧 Agent 链路，而不是无人值守、不可解释的全自动手机控制。
