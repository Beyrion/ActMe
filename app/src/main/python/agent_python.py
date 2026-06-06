import builtins
import contextlib
import io
import json
import os
import py_compile
import sys
import time
import traceback
import ctypes
import multiprocessing
import subprocess
from os.path import abspath, commonpath, isabs, join, realpath


DENIED_IMPORT_ROOTS = {
    "ensurepip",
    "pip",
    "venv",
    "webbrowser",
}


def run_code(code, input_text="", timeout_ms=3000, workspace_dir=""):
    start = time.monotonic()
    deadline = start + max(1, min(int(timeout_ms or 3000), 10000)) / 1000.0
    stdout = io.StringIO()
    stderr = io.StringIO()
    state = {"result": None, "has_result": False}

    def check_deadline():
        if time.monotonic() > deadline:
            raise TimeoutError("python_exec exceeded timeout")

    def trace_func(frame, event, arg):
        check_deadline()
        return trace_func

    original_import = builtins.__import__

    def safe_import(name, globals=None, locals=None, fromlist=(), level=0):
        root = name.split(".", 1)[0]
        if root in DENIED_IMPORT_ROOTS:
            raise ImportError("module is not allowed: " + name)
        return original_import(name, globals, locals, fromlist, level)

    def set_result(value):
        state["result"] = value
        state["has_result"] = True

    workspace_root = realpath(workspace_dir) if workspace_dir else ""
    if workspace_root:
        os.environ.setdefault("HOME", workspace_root)
        os.environ.setdefault("MPLCONFIGDIR", join(workspace_root, ".matplotlib"))
        os.environ.setdefault("XDG_CACHE_HOME", join(workspace_root, ".cache"))
        os.makedirs(os.environ["MPLCONFIGDIR"], exist_ok=True)
        os.makedirs(os.environ["XDG_CACHE_HOME"], exist_ok=True)
    before_files = _workspace_snapshot(workspace_root)

    def validate_workspace_path(path):
        if not path:
            raise PermissionError("empty path")
        raw_path = str(path)
        if workspace_root and not isabs(raw_path):
            raw_path = join(workspace_root, raw_path)
        full = realpath(abspath(raw_path))
        if not workspace_root or commonpath([workspace_root, full]) != workspace_root:
            raise PermissionError("path is outside python workspace: " + str(path))
        return full

    def safe_open(file, mode="r", *args, **kwargs):
        return open(validate_workspace_path(file), mode, *args, **kwargs)

    def read_excel(path, max_rows=200, max_sheets=10, values_only=True):
        full_path = validate_workspace_path(path)
        return _read_excel(full_path, max_rows=max_rows, max_sheets=max_sheets, values_only=values_only)

    def write_excel(filename, sheets):
        full_path = validate_workspace_path(filename)
        return _write_excel(full_path, sheets)

    def script_path(name):
        safe_name = _safe_script_name(name)
        script_dir = validate_workspace_path("python")
        os.makedirs(script_dir, exist_ok=True)
        return validate_workspace_path(join("python", safe_name))

    def save_script(name, source):
        full_path = script_path(name)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(str(source or ""))
        return {"path": full_path, "bytes": len(str(source or "").encode("utf-8"))}

    def load_script(name):
        full_path = script_path(name)
        with open(full_path, "r", encoding="utf-8") as f:
            return {"path": full_path, "source": f.read()}

    def list_scripts():
        script_dir = validate_workspace_path("python")
        os.makedirs(script_dir, exist_ok=True)
        scripts = []
        for root, _, files in os.walk(script_dir):
            for filename in files:
                if not filename.endswith(".py"):
                    continue
                full_path = join(root, filename)
                rel = os.path.relpath(full_path, script_dir).replace("\\", "/")
                scripts.append({"name": rel, "path": full_path, "bytes": os.path.getsize(full_path)})
        return sorted(scripts, key=lambda item: item["name"])

    def run_script(name):
        full_path = script_path(name)
        with open(full_path, "r", encoding="utf-8") as f:
            source = f.read()
        compiled_script = compile(source, full_path, "exec")
        exec(compiled_script, globals_dict, globals_dict)
        return state["result"] if state["has_result"] else globals_dict.get("result", None)

    def compile_script(name):
        full_path = script_path(name)
        try:
            py_compile.compile(full_path, doraise=True)
            return {"ok": True, "path": full_path, "error": ""}
        except py_compile.PyCompileError as exc:
            return {"ok": False, "path": full_path, "error": str(exc)}

    safe_builtins = {
        "abs": abs,
        "all": all,
        "any": any,
        "bool": bool,
        "bytes": bytes,
        "chr": chr,
        "dict": dict,
        "divmod": divmod,
        "enumerate": enumerate,
        "filter": filter,
        "float": float,
        "format": format,
        "hash": hash,
        "hex": hex,
        "int": int,
        "isinstance": isinstance,
        "issubclass": issubclass,
        "len": len,
        "list": list,
        "map": map,
        "max": max,
        "min": min,
        "next": next,
        "ord": ord,
        "pow": pow,
        "print": print,
        "range": range,
        "repr": repr,
        "reversed": reversed,
        "round": round,
        "set": set,
        "slice": slice,
        "sorted": sorted,
        "str": str,
        "sum": sum,
        "tuple": tuple,
        "zip": zip,
        "Exception": Exception,
        "ValueError": ValueError,
        "TypeError": TypeError,
        "RuntimeError": RuntimeError,
        "TimeoutError": TimeoutError,
        "__import__": safe_import,
        "open": safe_open,
    }

    globals_dict = {
        "__builtins__": safe_builtins,
        "__name__": "__agent_python__",
        "input_text": input_text or "",
        "workspace_dir": workspace_root,
        "read_excel": read_excel,
        "write_excel": write_excel,
        "save_script": save_script,
        "load_script": load_script,
        "list_scripts": list_scripts,
        "run_script": run_script,
        "compile_script": compile_script,
        "emit": set_result,
        "set_result": set_result,
    }

    try:
        globals_dict["input_json"] = json.loads(input_text) if input_text else None
    except Exception:
        globals_dict["input_json"] = None

    ok = True
    error = ""
    restore_os = _patch_os_for_sandbox(workspace_root, validate_workspace_path)
    restore_system_modules = _patch_system_modules_for_sandbox()
    sys.settrace(trace_func)
    try:
        compiled = compile(code or "", "<agent_python>", "exec")
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exec(compiled, globals_dict, globals_dict)
    except Exception:
        ok = False
        error = traceback.format_exc(limit=6)
    finally:
        sys.settrace(None)
        restore_system_modules()
        restore_os()

    result = state["result"] if state["has_result"] else globals_dict.get("result", None)
    output_files = _workspace_changed_files(workspace_root, before_files)
    return json.dumps(
        {
            "ok": ok,
            "stdout": stdout.getvalue(),
            "stderr": stderr.getvalue(),
            "error": error,
            "result": _json_safe(result),
            "elapsed_ms": int((time.monotonic() - start) * 1000),
            "output_files": output_files,
        },
        ensure_ascii=False,
    )


def _patch_os_for_sandbox(workspace_root, validate_workspace_path):
    patched = {}

    def remember(name):
        if hasattr(os, name):
            patched[name] = getattr(os, name)

    def deny(name):
        def denied(*args, **kwargs):
            raise PermissionError("os." + name + " is not allowed in python sandbox")
        return denied

    def workspace_path_call(name):
        original = getattr(os, name)

        def wrapped(path, *args, **kwargs):
            return original(validate_workspace_path(path), *args, **kwargs)

        return wrapped

    def workspace_two_path_call(name):
        original = getattr(os, name)

        def wrapped(src, dst, *args, **kwargs):
            return original(validate_workspace_path(src), validate_workspace_path(dst), *args, **kwargs)

        return wrapped

    denied_names = [
        "chdir",
        "chmod",
        "chown",
        "execv",
        "execve",
        "fork",
        "kill",
        "killpg",
        "popen",
        "spawnl",
        "spawnle",
        "spawnlp",
        "spawnlpe",
        "spawnv",
        "spawnve",
        "spawnvp",
        "spawnvpe",
        "startfile",
        "system",
    ]
    one_path_names = ["makedirs", "mkdir", "remove", "rmdir", "unlink"]
    two_path_names = ["replace", "rename"]

    for name in denied_names + one_path_names + two_path_names:
        remember(name)
    for name in denied_names:
        if hasattr(os, name):
            setattr(os, name, deny(name))
    for name in one_path_names:
        if hasattr(os, name):
            setattr(os, name, workspace_path_call(name))
    for name in two_path_names:
        if hasattr(os, name):
            setattr(os, name, workspace_two_path_call(name))

    def restore():
        for name, original in patched.items():
            setattr(os, name, original)

    return restore


def _patch_system_modules_for_sandbox():
    patched = []

    def remember(module, name):
        if hasattr(module, name):
            patched.append((module, name, getattr(module, name)))

    def deny(label):
        def denied(*args, **kwargs):
            raise PermissionError(label + " is not allowed in python sandbox")
        return denied

    for module, names in [
        (subprocess, ["Popen", "call", "check_call", "check_output", "run"]),
        (ctypes, ["CDLL", "PyDLL", "WinDLL", "OleDLL", "LibraryLoader"]),
        (multiprocessing, ["Process", "Pool", "Manager"]),
    ]:
        for name in names:
            remember(module, name)
            if hasattr(module, name):
                setattr(module, name, deny(module.__name__ + "." + name))

    def restore():
        for module, name, original in patched:
            setattr(module, name, original)

    return restore


def _workspace_snapshot(workspace_root):
    if not workspace_root or not os.path.isdir(workspace_root):
        return {}
    snapshot = {}
    for root, _, files in os.walk(workspace_root):
        for filename in files:
            full = join(root, filename)
            try:
                rel = os.path.relpath(full, workspace_root).replace("\\", "/")
                snapshot[rel] = (os.path.getmtime(full), os.path.getsize(full))
            except Exception:
                pass
    return snapshot


def _workspace_changed_files(workspace_root, before):
    if not workspace_root or not os.path.isdir(workspace_root):
        return []
    after = _workspace_snapshot(workspace_root)
    changed = []
    for rel, stat in after.items():
        if rel.startswith("python/"):
            continue
        if before.get(rel) != stat:
            changed.append(rel)
    return sorted(changed)


def _json_safe(value):
    try:
        json.dumps(value)
        return value
    except Exception:
        return repr(value)


def _read_excel(path, max_rows=200, max_sheets=10, values_only=True):
    from datetime import date, datetime, time as dt_time
    from decimal import Decimal
    from openpyxl import load_workbook

    workbook = load_workbook(path, read_only=True, data_only=True)
    try:
        sheets = []
        for sheet_name in workbook.sheetnames[: max(1, int(max_sheets or 10))]:
            ws = workbook[sheet_name]
            rows = []
            for row_index, row in enumerate(ws.iter_rows(values_only=values_only), start=1):
                if row_index > max(1, int(max_rows or 200)):
                    break
                rows.append([_cell_value(v) for v in row])
            sheets.append(
                {
                    "name": sheet_name,
                    "max_row": ws.max_row,
                    "max_column": ws.max_column,
                    "rows_returned": len(rows),
                    "rows": rows,
                }
            )
        return {"path": path, "sheet_count": len(workbook.sheetnames), "sheets": sheets}
    finally:
        workbook.close()


def _cell_value(value):
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Decimal):
        return float(value)
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _safe_script_name(name):
    raw = str(name or "").strip().replace("\\", "/")
    if not raw:
        raise ValueError("script name is empty")
    parts = [part for part in raw.split("/") if part not in ("", ".", "..")]
    safe_parts = []
    for part in parts:
        cleaned = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in part)
        if cleaned:
            safe_parts.append(cleaned)
    if not safe_parts:
        raise ValueError("invalid script name")
    filename = "/".join(safe_parts)
    if not filename.endswith(".py"):
        filename += ".py"
    return filename


def _write_excel(path, sheets):
    from openpyxl import Workbook

    workbook = Workbook()
    default_sheet = workbook.active
    workbook.remove(default_sheet)

    if isinstance(sheets, dict):
        iterable = sheets.items()
    elif isinstance(sheets, list):
        iterable = []
        for index, item in enumerate(sheets, start=1):
            if isinstance(item, dict):
                iterable.append((item.get("name") or ("Sheet" + str(index)), item.get("rows") or []))
            else:
                iterable.append(("Sheet" + str(index), item))
    else:
        raise TypeError("sheets must be a dict or list")

    sheet_count = 0
    row_count = 0
    for raw_name, rows in iterable:
        name = str(raw_name or "Sheet")[:31]
        ws = workbook.create_sheet(title=name)
        sheet_count += 1
        for row in rows or []:
            if isinstance(row, dict):
                values = list(row.values())
            elif isinstance(row, (list, tuple)):
                values = list(row)
            else:
                values = [row]
            ws.append([_cell_value(v) for v in values])
            row_count += 1

    if sheet_count == 0:
        workbook.create_sheet(title="Sheet1")
        sheet_count = 1

    workbook.save(path)
    workbook.close()
    return {"path": path, "sheet_count": sheet_count, "row_count": row_count}
