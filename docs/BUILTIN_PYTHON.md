# 内置 Python

内置 Python 是 ActMe 的确定性计算和文件处理能力。它让 Agent 不只依赖语言模型推理，而是可以运行代码完成计算、解析、表格处理、Excel 读写、通用文件生成和脚本复用。

## 目标

内置 Python 主要解决这些问题：

- 数学和数据计算需要确定性。
- 网页或文本提取后需要清洗、排序、去重、统计。
- Excel、PDF、CSV、图片、JSON、文本等文件需要读取、分析或生成。
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
- `agent_python.py`：提供沙箱执行、输入输出、Excel 读写、通用文件跟踪、脚本保存和编译。
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
report_font_dir
read_excel(path, max_rows=200, max_sheets=10)
write_excel(filename, sheets)
write_report(markdown_text, base_name="report", title=None)
save_script(name, source)
load_script(name)
list_scripts()
compile_script(name)
run_script(name)
```

从 1.2.0 开始，Python import 策略从“允许列表”改为“默认允许已安装包和标准库，少数危险能力受限”。当前随 APK 打包的常用库包括 `numpy`、`pandas`、`openpyxl`、`Pillow`、`matplotlib`、`reportlab`、`markdown`、`fpdf2` 和 `fonttools`，因此表格分析、Excel、图片、Markdown 和 HTML 生成可以直接走 `python_exec`。PDF 报告优先走 Android 侧 `html_to_pdf`。

## Excel 能力

ActMe 已内置 `openpyxl`、`pandas` 和 `numpy`，支持：

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

## 通用文件输出

Python 执行前后会扫描 `agent_workspace`。只要脚本在工作区内生成或修改文件，执行器会自动把文件加入 `output_files`。

Agent 也可以显式声明输出文件：

```json
{
  "type": "python_exec",
  "code": "open('summary.txt', 'w', encoding='utf-8').write('done')",
  "output_files": ["summary.txt"]
}
```

Repository 会在 Agent loop 的每一轮工具执行后收集文件，并在最终聊天消息中去重显示。Chat UI 会为存在的文件显示打开按钮。

当前通用打开支持：

- Excel: `.xlsx`, `.xlsm`, `.xls`
- 文本和结构化数据: `.txt`, `.md`, `.csv`, `.json`
- 文档: `.pdf`
- 图片: `.png`, `.jpg`, `.jpeg`
- 其他文件回退到系统通用打开器

## 报告生成

从 1.3.0 开始，报告类任务优先使用 `write_report`，而不是让 Agent 手写 ReportLab 或 FPDF 样板代码：

```python
markdown_text = """# 示例报告

## 摘要
这是中文报告内容。
"""
files = write_report(markdown_text, "reports/example_report", title="示例报告")
emit(files)
```

`write_report` 会：

- 生成 UTF-8 BOM 编码的 Markdown 文件，方便 Windows 编辑器正确识别中文。
- 把 Markdown 转成 HTML。
- 返回 `markdown`、`html` 和 `output_files`，执行器也会自动扫描工作区新增文件。

如果用户需要 PDF，Agent 应在 `write_report` 生成 HTML 后继续调用 `html_to_pdf`：

```json
{
  "type": "html_to_pdf",
  "url": "reports/example_report.html",
  "output_files": ["reports/example_report.pdf"]
}
```

`html_to_pdf` 使用 Android WebView 渲染 HTML 并通过系统打印管线写出 PDF，避免在 Python/Chaquopy 中维护复杂的 HTML-to-PDF native 依赖链。

`report_font_dir` 是 App 拷贝出来的字体目录，可能位于 `agent_workspace` 外。它是运行时资源，不是生成文件目录。Agent 不需要把字体路径写进 `output_files`。为了让文件按钮稳定显示，模型应把报告、表格、图片等用户可见产物写入 `agent_workspace` 下的相对路径。

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

- 默认允许导入标准库和已安装包。
- Python 层不额外拦截文件写入、删除、重命名；实际能否成功交给 Android App 沙箱和系统权限决定。Prompt 仍要求 Agent 把用户可见的生成文件写到 `agent_workspace` 相对路径，便于 App 收集并显示打开按钮。
- 读取类操作尽量放开，以便第三方库读取自身资源、App 打包资源和用户可读文件。
- 禁止或限制包安装、进程控制、native code、系统 shell 等危险能力，例如 `pip`、`venv`、`subprocess`、`ctypes`、`multiprocessing`、`os.system`。
- 常见 cache/config 路径会指向工作区，例如 `HOME`、`MPLCONFIGDIR`、`XDG_CACHE_HOME`。

## 与其他能力配合

典型链路：

1. 内置浏览器搜索和读取网页。
2. Python 解析文本、清洗数据、计算结果。
3. Python 清洗数据、计算结果或生成 Excel/PDF/CSV/图片等文件。
4. Agent 把结论和文件按钮返回给用户。
