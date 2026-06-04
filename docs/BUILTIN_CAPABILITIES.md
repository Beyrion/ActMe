# ActMe 内置能力总览：三驾马车

ActMe 的 Agent 能力不是只依赖大模型本身，而是由三类 App 内置执行能力共同支撑：

1. 内置浏览器：让 Agent 能联网搜索、打开网页、读取渲染后的页面内容。
2. 内置 Python：让 Agent 能做确定性计算、数据处理、Excel 读写和脚本复用。
3. 内置 ADB：让 Agent 在用户授权后具备观察和操作本机 Android 环境的能力。

这三类能力构成 ActMe 的执行层。大模型负责规划、判断、生成工具调用和总结结果；App 负责真实执行、展示步骤、返回观察结果，并允许用户中止。

## 设计目标

ActMe 的目标是把移动端 Agent 从“会聊天”推进到“能执行”：

- 遇到最新信息、事实核验、网页资料时，调用内置浏览器。
- 遇到计算、解析、表格、Excel、文件生成时，调用内置 Python。
- 遇到本机 App 状态、系统页面、UI 自动化、日志/截图/输入操作时，调用内置 ADB。

三者组合后，Agent 可以完成更长链路任务，例如：

- 搜索多个网页，浏览权威来源，用 Python 清洗和汇总数据，生成 Excel。
- 浏览官网和公告，提取关键文本，用 Python 比对表格，再给出结论。
- 使用 ADB 读取当前手机界面状态，执行输入/点击，再观察结果继续操作。

## System Calls

Agent 通过 `system_calls` 请求 App 执行工具。典型格式：

```json
{
  "system_calls": [
    {
      "type": "web_search",
      "query": "中国银行 积存金 价格"
    },
    {
      "type": "browse_url",
      "url": "https://www.boc.cn/fimarkets/"
    },
    {
      "type": "python_exec",
      "code": "emit({'answer': 2 + 2})",
      "input": "",
      "timeout_ms": 3000
    },
    {
      "type": "adb_shell",
      "command": "dumpsys window | head -50",
      "timeout_ms": 15000
    }
  ]
}
```

App 执行后会把结果写回多步执行循环，Agent 可以观察结果后继续调用更多工具，直到信息足够或工具预算耗尽。

## 用户可见性

三驾马车不是后台黑盒。ActMe 会在聊天中展示工具执行步骤：

- 正在搜索什么。
- 正在打开哪个网页。
- 正在运行 Python。
- 正在执行 ADB 命令。
- 哪一步成功、失败或被跳过。

用户可以在执行过程中中止当前任务。

## 能力边界

三驾马车提升的是执行能力，不等于完全自动自治：

- 浏览器读取网页可能受登录、反爬、地区、动态渲染和网站结构影响。
- Python 沙箱默认无网络，主要用于本地计算和文件处理。
- ADB 需要用户手动授权无线调试和悬浮窗权限，且连接端口可能随系统状态变化。
- 高风险操作需要谨慎，尤其是 ADB 的删除、卸载、清数据、权限修改等命令。

## 文档入口

- [内置浏览器](BUILTIN_BROWSER.md)
- [内置 Python](BUILTIN_PYTHON.md)
- [内置 ADB](BUILTIN_ADB.md)
