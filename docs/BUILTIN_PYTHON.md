# 内置 Python

内置 Python 是 ActMe 的确定性计算和文件处理能力。它让 Agent 不只依赖语言模型推理，而是可以运行代码完成计算、解析、表格处理、Excel 读写和脚本复用。

## 目标

内置 Python 主要解决这些问题：

- 数学和数据计算需要确定性。
- 网页或文本提取后需要清洗、排序、去重、统计。
- Excel 文件需要读取、分析、生成。
- 复杂任务需要可复用脚本，而不是每次重新生成一段代码。

## 主要实现

核心代码位置：

```text
app/src/main/java/com/actme/app/data/agent/PythonSkillEngine.kt
app/src/main/python/agent_python.py
app/src/main/java/com/actme/app/data/agent/SystemSkillExecutor.kt
```

实现分层：

- `PythonSkillEngine`：初始化 Chaquopy Python runtime，并从 Kotlin 调用 Python。
- `agent_python.py`：提供沙箱执行、输入输出、Excel 读写、脚本保存和编译。
- `SystemSkillExecutor`：接收 Agent 的 `python_exec` system call，执行后把结果返回给多步循环。

## Agent 调用示例

```json
{
  "type": "python_exec",
  "code": "emit({'answer': 2 + 2})",
  "input": "",
  "timeout_ms": 3000
}
```

Python 代码里可以使用：

```python
input_text
input_json
emit(value)
set_result(value)
result
workspace_dir
read_excel(path, max_rows=200, max_sheets=10)
write_excel(filename, sheets)
save_script(name, source)
load_script(name)
list_scripts()
compile_script(name)
run_script(name)
```

## Excel 能力

ActMe 已内置 `openpyxl`，支持：

- 读取 `.xlsx`
- 读取 `.xlsm`
- 生成 `.xlsx`
- 从聊天中返回生成文件路径
- 从系统“打开方式/分享”接收 Excel 文件并载入当前会话

示例：

```json
{
  "type": "python_exec",
  "code": "data = read_excel('/path/to/file.xlsx')\nemit(data)",
  "input": "",
  "timeout_ms": 10000
}
```

生成 Excel：

```json
{
  "type": "python_exec",
  "code": "path = write_excel('result.xlsx', {'Sheet1': [['name', 'score'], ['A', 95]]})\nemit({'file': path})",
  "input": "",
  "timeout_ms": 10000
}
```

## 运行期脚本

Agent 可以保存脚本：

```python
save_script("tools/analyze.py", source)
```

然后编译：

```python
compile_script("tools/analyze.py")
```

再执行：

```python
run_script("tools/analyze.py")
```

这让 Agent 可以逐步构建自己的轻量工具库。

## 安全边界

内置 Python 是沙箱式能力：

- 默认无网络访问。
- 用于本地计算和文件处理。
- 不应使用 `os`、`socket`、`subprocess`、`ctypes` 等危险能力。
- 需要联网时，应先调用内置浏览器，再把文本交给 Python 处理。

## 与其他能力配合

典型链路：

1. 内置浏览器搜索和读取网页。
2. Python 解析文本、清洗数据、计算结果。
3. Python 生成 Excel。
4. Agent 把结论和文件返回给用户。

未来如果加入 PDF 生成，也可以把 Python 作为数据准备层，PDF 渲染由 Android 原生或 Python 库完成。
