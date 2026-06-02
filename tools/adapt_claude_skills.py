#!/usr/bin/env python3
"""
Download Claude-style skills and adapt them into ActMe app assets.

Examples:
  python tools/adapt_claude_skills.py https://github.com/anthropics/financial-services ^
    --plugin plugins/vertical-plugins/financial-analysis

  python tools/adapt_claude_skills.py https://github.com/anthropics/financial-services/tree/main/plugins/vertical-plugins/equity-research

  python tools/adapt_claude_skills.py C:\\work\\financial-services\\plugins\\vertical-plugins\\financial-analysis

The tool writes two outputs by default:
  app/src/main/assets/skills/claude_import/
    Full copied SKILL.md files, resource files, and manifest.json.

  app/src/main/assets/skills/preload_claude_skills.json
    A lightweight list matching the current SkillSeeder preload schema.

Use --merge-preload to append the generated lightweight skills into the app's
current preload_skills.json. The existing file must be valid JSON.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_IMPORT_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "skills" / "claude_import"
DEFAULT_PRELOAD_OUTPUT = REPO_ROOT / "app" / "src" / "main" / "assets" / "skills" / "preload_claude_skills.json"
DEFAULT_PRELOAD_MERGE_TARGET = REPO_ROOT / "app" / "src" / "main" / "assets" / "skills" / "preload_skills.json"

STOP_WORDS = {
    "about",
    "after",
    "agent",
    "analysis",
    "and",
    "are",
    "before",
    "claude",
    "data",
    "from",
    "have",
    "into",
    "model",
    "must",
    "notes",
    "only",
    "output",
    "process",
    "report",
    "skill",
    "skills",
    "that",
    "the",
    "this",
    "use",
    "using",
    "when",
    "with",
    "workflow",
    "other",
    "selected",
    "step",
    "their",
    "there",
    "these",
    "user",
    "users",
    "you",
    "your",
}

FINANCE_KEYWORDS = [
    "audit",
    "banking",
    "bond",
    "buyer",
    "capital",
    "catalyst",
    "cim",
    "comparable",
    "comps",
    "dcf",
    "deck",
    "diligence",
    "earnings",
    "ebitda",
    "equity",
    "excel",
    "financial",
    "forecast",
    "fund",
    "irr",
    "kyc",
    "lbo",
    "market",
    "merger",
    "model",
    "multiple",
    "pitch",
    "portfolio",
    "reconcile",
    "research",
    "revenue",
    "screening",
    "statement",
    "valuation",
    "wealth",
    "xlsx",
]


@dataclass
class SourceSpec:
    kind: str
    source: str
    ref: str | None = None
    subpath: str | None = None


@dataclass
class AdaptedSkill:
    slug: str
    name: str
    description: str
    trigger_keywords: list[str]
    action_template: str
    source_path: str
    content_file: str
    resource_dir: str | None
    resource_files: list[str]

    def preload_entry(self) -> dict[str, object]:
        return {
            "name": self.name,
            "description": self.description,
            "trigger_keywords": self.trigger_keywords,
            "action_template": self.action_template,
        }

    def manifest_entry(self) -> dict[str, object]:
        return {
            "slug": self.slug,
            "name": self.name,
            "description": self.description,
            "trigger_keywords": self.trigger_keywords,
            "source_path": self.source_path,
            "content_file": self.content_file,
            "resource_dir": self.resource_dir,
            "resource_files": self.resource_files,
        }


def main() -> int:
    parser = argparse.ArgumentParser(description="Adapt Claude SKILL.md directories for the ActMe app.")
    parser.add_argument("source", help="Local path, GitHub repo URL, GitHub tree URL, or zip URL.")
    parser.add_argument("--plugin", help="Optional subdirectory to scan inside the source.")
    parser.add_argument("--ref", default="main", help="GitHub ref to download when source is a repo URL.")
    parser.add_argument("--output", type=Path, default=DEFAULT_IMPORT_DIR, help="Full adapted asset output directory.")
    parser.add_argument(
        "--preload-output",
        type=Path,
        default=DEFAULT_PRELOAD_OUTPUT,
        help="Write lightweight current-app preload JSON here.",
    )
    parser.add_argument(
        "--merge-preload",
        nargs="?",
        const=str(DEFAULT_PRELOAD_MERGE_TARGET),
        help="Append lightweight skills into preload_skills.json. Optional path may be provided.",
    )
    parser.add_argument(
        "--max-template-chars",
        type=int,
        default=2600,
        help="Max characters copied from each SKILL.md into action_template.",
    )
    parser.add_argument(
        "--max-resource-mb",
        type=int,
        default=20,
        help="Skip individual resource files larger than this size.",
    )
    parser.add_argument("--clean", action="store_true", help="Delete the output import directory before writing.")
    args = parser.parse_args()

    source_spec = parse_source(args.source, args.ref)
    with materialize_source(source_spec) as source_root:
        scan_root = source_root
        subpath = normalize_subpath(args.plugin or source_spec.subpath)
        if subpath:
            scan_root = source_root / subpath
        if not scan_root.exists():
            raise SystemExit(f"scan path does not exist: {scan_root}")

        skills = adapt_skills(
            source_root=source_root,
            scan_root=scan_root,
            output_dir=args.output,
            source_label=args.source,
            plugin_path=subpath,
            max_template_chars=args.max_template_chars,
            max_resource_mb=args.max_resource_mb,
            clean=args.clean,
        )

    if not skills:
        raise SystemExit("no SKILL.md files found")

    write_json(args.preload_output, [skill.preload_entry() for skill in skills])

    if args.merge_preload:
        merge_target = Path(args.merge_preload)
        merge_preload(merge_target, skills)

    print(f"adapted {len(skills)} skills")
    print(f"full assets: {args.output}")
    print(f"current-app preload: {args.preload_output}")
    if args.merge_preload:
        print(f"merged preload: {Path(args.merge_preload)}")
    return 0


def parse_source(raw: str, default_ref: str) -> SourceSpec:
    path = Path(raw)
    if path.exists():
        return SourceSpec(kind="local", source=str(path.resolve()))

    parsed = urllib.parse.urlparse(raw)
    if parsed.scheme in {"http", "https"}:
        if parsed.netloc.lower() == "github.com":
            parts = [part for part in parsed.path.split("/") if part]
            if len(parts) >= 2:
                owner, repo = parts[0], parts[1]
                ref = default_ref
                subpath = None
                if len(parts) >= 4 and parts[2] == "tree":
                    ref = parts[3]
                    subpath = "/".join(parts[4:]) or None
                return SourceSpec(
                    kind="github",
                    source=f"{owner}/{repo.removesuffix('.git')}",
                    ref=ref,
                    subpath=subpath,
                )
        if parsed.path.lower().endswith(".zip"):
            return SourceSpec(kind="zip", source=raw)

    raise SystemExit(f"unsupported source: {raw}")


def materialize_source(spec: SourceSpec):
    class SourceContext:
        def __enter__(self) -> Path:
            self.tmp = tempfile.TemporaryDirectory(prefix="actme_claude_skills_")
            tmp_path = Path(self.tmp.name)
            if spec.kind == "local":
                self.tmp.cleanup()
                self.tmp = None
                return Path(spec.source)

            zip_path = tmp_path / "source.zip"
            if spec.kind == "github":
                url = f"https://github.com/{spec.source}/archive/refs/heads/{spec.ref}.zip"
            elif spec.kind == "zip":
                url = spec.source
            else:
                raise SystemExit(f"unsupported source kind: {spec.kind}")

            download(url, zip_path)
            extract_dir = tmp_path / "extracted"
            extract_zip(zip_path, extract_dir)
            roots = [p for p in extract_dir.iterdir() if p.is_dir()]
            return roots[0] if len(roots) == 1 else extract_dir

        def __exit__(self, exc_type, exc, tb) -> None:
            tmp = getattr(self, "tmp", None)
            if tmp is not None:
                tmp.cleanup()

    return SourceContext()


def download(url: str, target: Path) -> None:
    print(f"downloading {url}")
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "ActMeSkillAdapter/1.0",
            "Accept": "application/zip,application/octet-stream,*/*",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        target.write_bytes(response.read())


def extract_zip(zip_path: Path, target_dir: Path) -> None:
    target_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        for member in archive.infolist():
            member_path = Path(member.filename)
            if member_path.is_absolute() or ".." in member_path.parts:
                raise SystemExit(f"unsafe zip member: {member.filename}")
            archive.extract(member, target_dir)


def adapt_skills(
    source_root: Path,
    scan_root: Path,
    output_dir: Path,
    source_label: str,
    plugin_path: str | None,
    max_template_chars: int,
    max_resource_mb: int,
    clean: bool,
) -> list[AdaptedSkill]:
    if clean and output_dir.exists():
        shutil.rmtree(output_dir)

    content_dir = output_dir / "content"
    resource_root = output_dir / "resources"
    content_dir.mkdir(parents=True, exist_ok=True)
    resource_root.mkdir(parents=True, exist_ok=True)

    skill_files = sorted(scan_root.rglob("SKILL.md"))
    adapted: list[AdaptedSkill] = []
    used_slugs: set[str] = set()

    for skill_file in skill_files:
        raw = skill_file.read_text(encoding="utf-8", errors="replace")
        frontmatter, body = split_frontmatter(raw)
        rel_path = skill_file.relative_to(source_root).as_posix()
        slug = unique_slug(slugify(skill_file.parent.name), used_slugs)
        name = extract_name(frontmatter, body, skill_file.parent.name)
        description = extract_description(frontmatter, body)
        keywords = extract_keywords(frontmatter, body, name, rel_path, slug)
        template = build_action_template(name, description, rel_path, body, max_template_chars)

        content_file = f"{slug}.md"
        (content_dir / content_file).write_text(raw, encoding="utf-8")

        resource_dir, resource_files = copy_resources(
            skill_file.parent,
            resource_root / slug,
            source_root,
            max_resource_mb=max_resource_mb,
        )

        adapted.append(
            AdaptedSkill(
                slug=slug,
                name=name,
                description=description,
                trigger_keywords=keywords,
                action_template=template,
                source_path=rel_path,
                content_file=f"content/{content_file}",
                resource_dir=f"resources/{slug}" if resource_dir else None,
                resource_files=resource_files,
            )
        )

    manifest = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": source_label,
        "plugin_path": plugin_path,
        "format": "actme_claude_skill_import_v1",
        "note": "Full SKILL.md content is preserved here. preload_claude_skills.json is a lossy compatibility export for the current app schema.",
        "skills": [skill.manifest_entry() for skill in adapted],
    }
    write_json(output_dir / "manifest.json", manifest)
    write_json(output_dir / "skills.json", [skill.__dict__ for skill in adapted])
    return adapted


def split_frontmatter(text: str) -> tuple[dict[str, object], str]:
    if not text.startswith("---"):
        return {}, text
    match = re.match(r"^---\s*\n(.*?)\n---\s*\n?", text, flags=re.S)
    if not match:
        return {}, text
    frontmatter_text = match.group(1)
    body = text[match.end() :]
    return parse_simple_yaml(frontmatter_text), body


def parse_simple_yaml(text: str) -> dict[str, object]:
    result: dict[str, object] = {}
    current_key: str | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("- ") and current_key:
            value = stripped[2:].strip().strip("\"'")
            existing = result.setdefault(current_key, [])
            if isinstance(existing, list):
                existing.append(value)
            continue
        if ":" in stripped:
            key, value = stripped.split(":", 1)
            key = key.strip()
            value = value.strip()
            current_key = key
            if value in {"", "|", ">"}:
                result[key] = []
            elif value.startswith("[") and value.endswith("]"):
                result[key] = [item.strip().strip("\"'") for item in value[1:-1].split(",") if item.strip()]
            else:
                result[key] = value.strip("\"'")
    return result


def extract_name(frontmatter: dict[str, object], body: str, fallback: str) -> str:
    for key in ("name", "title"):
        value = frontmatter.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    match = re.search(r"^\s*#\s+(.+?)\s*$", body, flags=re.M)
    if match:
        return strip_markdown(match.group(1)).strip()
    return fallback.replace("-", " ").replace("_", " ").title()


def extract_description(frontmatter: dict[str, object], body: str) -> str:
    for key in ("description", "summary"):
        value = frontmatter.get(key)
        if isinstance(value, str) and value.strip():
            return one_line(strip_markdown(value), 240)

    for block in re.split(r"\n\s*\n", body):
        cleaned = strip_markdown(block).strip()
        if not cleaned or cleaned.startswith("#"):
            continue
        if len(cleaned) >= 24:
            return one_line(cleaned, 240)
    return "Claude-imported skill."


def extract_keywords(frontmatter: dict[str, object], body: str, name: str, rel_path: str, slug: str) -> list[str]:
    keywords: list[str] = []
    for key in ("keywords", "triggers", "trigger_keywords"):
        value = frontmatter.get(key)
        if isinstance(value, list):
            keywords.extend(str(item) for item in value)
        elif isinstance(value, str):
            keywords.extend(re.split(r"[,;]\s*", value))

    keywords.extend(split_identifier(slug))
    keywords.extend(split_identifier(Path(rel_path).parent.name))
    keywords.extend(word_tokens(name))
    keywords.extend(command_tokens(body))
    keywords.extend(word for word in FINANCE_KEYWORDS if re.search(rf"\b{re.escape(word)}\b", body, re.I))

    scored: dict[str, int] = {}
    for token in word_tokens(strip_markdown(body)):
        if token in STOP_WORDS or len(token) < 4:
            continue
        scored[token] = scored.get(token, 0) + 1
    keywords.extend(token for token, _ in sorted(scored.items(), key=lambda item: item[1], reverse=True)[:12])

    deduped: list[str] = []
    seen: set[str] = set()
    for keyword in keywords:
        cleaned = keyword.strip().lower().replace("_", "-")
        if not cleaned or cleaned.isdigit() or cleaned in STOP_WORDS or cleaned in seen:
            continue
        seen.add(cleaned)
        deduped.append(cleaned)
        if len(deduped) >= 24:
            break
    return deduped or [slug]


def command_tokens(text: str) -> list[str]:
    return [match.group(1).lower() for match in re.finditer(r"`?/([a-z][a-z0-9_-]{2,})`?", text, flags=re.I)]


def split_identifier(value: str) -> list[str]:
    return [part for part in re.split(r"[-_\s/]+", value.lower()) if part and part not in STOP_WORDS]


def word_tokens(text: str) -> list[str]:
    return [token.lower() for token in re.findall(r"[a-z][a-z0-9-]{2,}", text, flags=re.I)]


def build_action_template(name: str, description: str, rel_path: str, body: str, max_chars: int) -> str:
    content = strip_frontmatter_noise(body).strip()
    if len(content) > max_chars:
        content = content[:max_chars].rstrip() + "\n..."
    return (
        f"Claude skill: {name}\n"
        f"Source: {rel_path}\n"
        f"Use when relevant: {description}\n"
        "Important: treat financial output as draft analysis for user review, not investment, legal, tax, or accounting advice.\n"
        "Skill instructions:\n"
        f"{content}"
    )


def strip_frontmatter_noise(text: str) -> str:
    return re.sub(r"\n{3,}", "\n\n", text)


def copy_resources(skill_dir: Path, target_dir: Path, source_root: Path, max_resource_mb: int) -> tuple[bool, list[str]]:
    files: list[str] = []
    max_bytes = max_resource_mb * 1024 * 1024
    for src in sorted(skill_dir.rglob("*")):
        if not src.is_file() or src.name == "SKILL.md":
            continue
        if src.stat().st_size > max_bytes:
            continue
        rel = src.relative_to(skill_dir)
        dest = target_dir / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        files.append(src.relative_to(source_root).as_posix())
    return bool(files), files


def merge_preload(target: Path, skills: list[AdaptedSkill]) -> None:
    if target.exists():
        try:
            existing = json.loads(target.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"cannot merge because {target} is not valid JSON: {exc}") from exc
        if not isinstance(existing, list):
            raise SystemExit(f"cannot merge because {target} does not contain a JSON list")
    else:
        existing = []

    by_name = {item.get("name"): item for item in existing if isinstance(item, dict)}
    for skill in skills:
        by_name[skill.name] = skill.preload_entry()
    write_json(target, list(by_name.values()))


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize_subpath(value: str | None) -> str | None:
    if not value:
        return None
    return value.replace("\\", "/").strip("/")


def slugify(value: str) -> str:
    lowered = value.lower().strip()
    slug = re.sub(r"[^a-z0-9._-]+", "-", lowered).strip("-")
    return slug or "skill"


def unique_slug(base: str, used: set[str]) -> str:
    slug = base
    index = 2
    while slug in used:
        slug = f"{base}-{index}"
        index += 1
    used.add(slug)
    return slug


def strip_markdown(text: str) -> str:
    text = re.sub(r"```.*?```", " ", text, flags=re.S)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    text = re.sub(r"!\[[^\]]*\]\([^)]+\)", " ", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"^[#>*\-\s]+", "", text, flags=re.M)
    text = re.sub(r"[*_~]+", "", text)
    return text


def one_line(text: str, limit: int) -> str:
    collapsed = re.sub(r"\s+", " ", text).strip()
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[: limit - 3].rstrip() + "..."


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
