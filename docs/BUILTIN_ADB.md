# 内置 ADB

内置 ADB 是 ActMe 的本机 Android 环境操作能力。它让 Agent 在用户授权后，可以通过无线调试连接本机 `adbd`，执行 `adb shell` 命令，用于观察系统状态、读取 UI 信息、查看日志和进行受控输入操作。

## 目标

内置 ADB 的目标不是替代完整桌面 adb，而是给移动端 Agent 一个轻量执行通道：

- 查看当前窗口、Activity、输入法、屏幕状态。
- 读取 logcat。
- 使用 `uiautomator dump` 获取 UI 层级。
- 执行 `input tap`、`input text`、`input keyevent` 等简单 GUI 操作。
- 配合多步 Agent 循环，实现“观察 -> 决策 -> 操作 -> 再观察”。

## 主要实现

核心代码位置：

```text
app/src/main/java/com/actme/app/data/agent/AdbSkillEngine.kt
app/src/main/java/com/actme/app/data/agent/SystemSkillExecutor.kt
app/src/main/java/com/actme/app/ui/AdbOverlayService.kt
```

实现分层：

- `AdbSkillEngine`：负责保存 host/port、配对、测试连接、执行 shell。
- `SystemSkillExecutor`：接收 Agent 的 `adb_shell` system call。
- `AdbOverlayService`：系统悬浮窗，用于在无线调试设置页上方完成配对输入。

## 为什么需要悬浮窗

Android 无线调试配对时，系统会显示配对码弹窗。这个弹窗离开或关闭后，配对端口和验证码可能失效。

因此 ActMe 不能把配对表单放在普通 App 页面里。正确方式是：

1. 用户打开 ActMe 的“内置 ADB”。
2. ActMe 启动悬浮窗。
3. ActMe 打开系统开发者选项。
4. 用户进入无线调试并点击“使用配对码配对设备”。
5. 用户保持系统配对码弹窗不关闭，在 ActMe 悬浮窗里输入配对端口和验证码。

部分系统还要求开启：

- 显示在其他应用上层。
- 允许在“设置”上重叠显示。

否则悬浮窗可能不能显示在系统设置页面上方。

## 悬浮窗交互

悬浮窗默认是小窗，避免挡住系统设置页面：

- 标题：内置 ADB。
- 按钮：设置、展开、关闭。
- 状态区：显示当前提示或错误。

点击“展开”后显示滚动表单：

- Host
- 配对端口
- 验证码
- 连接端口
- shell 测试命令

展开区域高度固定，内容通过滚动查看。

连接、配对或 shell 失败时：

- 状态区显示红字错误。
- 同时弹出 Toast。

## Agent 调用示例

```json
{
  "type": "adb_shell",
  "command": "dumpsys window | head -50",
  "timeout_ms": 15000
}
```

兼容别名：

- `adb_shell`
- `adb`
- `run_adb`

命令可以写成：

```text
dumpsys window | head -50
```

也可以写成：

```text
adb shell dumpsys window | head -50
```

执行器会自动去掉 `adb shell` 前缀。

## 连接持续性

点击“测试并保存连接”成功后，ActMe 会保存当前 `host:port`。

之后悬浮窗可以关闭，Agent 仍可继续调用 `adb_shell`。每次调用时，App 会读取保存的连接配置，并临时建立 ADB 连接执行命令。

需要注意：

- 无线调试连接端口可能在关闭/重开无线调试、重启手机后变化。
- 端口变化后需要重新测试并保存连接。
- 配对关系通常会保留，除非用户撤销配对、清除应用数据或系统重置无线调试状态。

## 安全边界

ADB 是高权限能力，必须谨慎使用：

- 默认优先执行只读命令，例如 `dumpsys`、`settings get`、`pm list`、`logcat -d`、`uiautomator dump`。
- 只有用户明确要求时才执行 GUI 操作，例如点击、输入、返回键。
- 不应擅自执行删除、卸载、清数据、改权限、改系统设置等破坏性命令。

建议 Agent 遵循：

1. 先观察。
2. 再解释准备执行什么。
3. 对高风险命令等待用户确认。
4. 执行后再次观察结果。

## 与其他能力配合

内置 ADB 可以和浏览器、Python 组合：

- ADB 获取当前 UI XML。
- Python 解析 XML，找出控件坐标。
- ADB 执行点击/输入。
- ADB 再次 dump UI 验证结果。

这使 ActMe 具备低配版 GUI 自动化能力。
