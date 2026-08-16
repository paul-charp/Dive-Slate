"""Generate .claude/codebase-index.md — a compact API map for token-efficient
Claude Code sessions.

Extracts module docstring summaries, __all__, class/method/function signatures
(with annotations and defaults) and docstring first lines via ast. Run from the
repo root after changing public APIs:

    uv run python .claude/generate-index.py
"""

from __future__ import annotations

import ast
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "diveslate"
OUT = ROOT / ".claude" / "codebase-index.md"


def first_line(doc: str | None) -> str:
    if not doc:
        return ""
    line = doc.strip().splitlines()[0].strip()
    return line


def _sig_str(node: ast.FunctionDef | ast.AsyncFunctionDef) -> str:
    a = node.args
    parts: list[str] = []

    def fmt_arg(arg: ast.arg, default: ast.expr | None) -> str:
        s = arg.arg
        if arg.annotation is not None:
            s += f": {ast.unparse(arg.annotation)}"
        if default is not None:
            s += (
                f" = {ast.unparse(default)}"
                if arg.annotation
                else f"={ast.unparse(default)}"
            )
        return s

    pos = a.posonlyargs + a.args
    defaults: list[ast.expr | None] = [None] * (len(pos) - len(a.defaults)) + list(
        a.defaults
    )
    for arg, d in zip(pos, defaults):
        parts.append(fmt_arg(arg, d))
    if a.posonlyargs:
        parts.insert(len(a.posonlyargs), "/")
    if a.vararg:
        parts.append("*" + a.vararg.arg)
    elif a.kwonlyargs:
        parts.append("*")
    for arg, d in zip(a.kwonlyargs, a.kw_defaults):
        parts.append(fmt_arg(arg, d))
    if a.kwarg:
        parts.append("**" + a.kwarg.arg)

    sig = f"({', '.join(parts)})"
    if node.returns is not None:
        sig += f" -> {ast.unparse(node.returns)}"
    return sig


def decorator_names(
    node: ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef,
) -> set[str]:
    names: set[str] = set()
    for dec in node.decorator_list:
        target = dec.func if isinstance(dec, ast.Call) else dec
        names.add(ast.unparse(target).split(".")[-1])
    return names


def emit_function(
    node: ast.FunctionDef | ast.AsyncFunctionDef, lines: list[str], indent: str
) -> None:
    decs = decorator_names(node)
    prefix = ""
    if "property" in decs:
        prefix = "@property "
    elif "cached_property" in decs:
        prefix = "@cached_property "
    elif "classmethod" in decs:
        prefix = "@classmethod "
    elif "staticmethod" in decs:
        prefix = "@staticmethod "
    if "abstractmethod" in decs:
        prefix += "@abstract "
    doc = first_line(ast.get_docstring(node))
    doc_part = f"  — {doc}" if doc else ""
    lines.append(f"{indent}- {prefix}`{node.name}{_sig_str(node)}`{doc_part}")


def emit_class(node: ast.ClassDef, lines: list[str], indent: str = "") -> None:
    bases = ", ".join(ast.unparse(b) for b in node.bases)
    type_params = (
        f"[{', '.join(ast.unparse(t) for t in node.type_params)}]"
        if getattr(node, "type_params", None)
        else ""
    )
    head = (
        f"{indent}### class `{node.name}{type_params}`"
        if not indent
        else f"{indent}- class `{node.name}{type_params}`"
    )
    if bases:
        head += f" ({bases})"
    doc = first_line(ast.get_docstring(node))
    if doc:
        head += f" — {doc}"
    lines.append(head)

    slots = [
        s
        for s in node.body
        if isinstance(s, ast.Assign)
        for t in s.targets
        if isinstance(t, ast.Name) and t.id == "__slots__"
    ]
    if slots:
        lines.append(f"{indent}  `__slots__ = {ast.unparse(slots[0].value)}`")

    enum_members: list[str] = []
    for item in node.body:
        if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if item.name.startswith("__") and item.name not in (
                "__init__",
                "__enter__",
                "__exit__",
            ):
                # dunders: summarize below instead of listing each
                continue
            emit_function(item, lines, indent + "  ")
        elif isinstance(item, ast.ClassDef):
            emit_class(item, lines, indent + "  ")
        elif isinstance(item, ast.Assign):
            for t in item.targets:
                if isinstance(t, ast.Name) and not t.id.startswith("__"):
                    enum_members.append(f"{t.id} = {ast.unparse(item.value)}")
        elif isinstance(item, ast.AnnAssign) and isinstance(item.target, ast.Name):
            name = item.target.id
            if name.startswith("_") and not name.startswith("__"):
                continue
            ann = ast.unparse(item.annotation)
            val = f" = {ast.unparse(item.value)}" if item.value is not None else ""
            enum_members.append(f"{name}: {ann}{val}")

    dunders = sorted(
        item.name
        for item in node.body
        if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef))
        and item.name.startswith("__")
        and item.name not in ("__init__", "__enter__", "__exit__")
    )
    if enum_members:
        lines.append(f"{indent}  attrs: `{'; '.join(enum_members)}`")
    if dunders:
        lines.append(f"{indent}  dunders: `{', '.join(dunders)}`")


def emit_module(path: Path, lines: list[str]) -> None:
    rel = path.relative_to(ROOT).as_posix()
    tree = ast.parse(path.read_text(encoding="utf-8"))
    body = tree.body

    lines.append(f"\n## `{rel}`")
    doc = first_line(ast.get_docstring(tree))
    if doc:
        lines.append(f"{doc}")

    for item in body:
        if isinstance(item, ast.Assign):
            for t in item.targets:
                if isinstance(t, ast.Name) and t.id == "__all__":
                    lines.append(f"`__all__ = {ast.unparse(item.value)}`")
                elif (
                    isinstance(t, ast.Name)
                    and not t.id.startswith("_")
                    and t.id != "__all__"
                ):
                    lines.append(f"- const `{t.id} = {ast.unparse(item.value)}`")
        elif isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
            emit_function(item, lines, "")
        elif isinstance(item, ast.ClassDef):
            emit_class(item, lines)


def main() -> None:
    generated = datetime.now(UTC).date().isoformat()
    lines: list[str] = [
        "# diveslate codebase index",
        "",
        (
            "Auto-generated API map (regenerate: "
            f"`uv run python .claude/generate-index.py`). Generated {generated}."
        ),
        "",
        (
            "Read this instead of source files when you only need "
            "signatures/structure. Read the actual source before *editing* "
            "anything listed here."
        ),
    ]

    for path in sorted(SRC.rglob("*.py")):
        if path.stat().st_size == 0:
            rel = path.relative_to(ROOT).as_posix()
            lines.append(f"\n## `{rel}`\n(empty stub)")
            continue
        emit_module(path, lines)

    # Test files: one line each, class names only.
    lines.append("\n# Tests (tests/)")
    for path in sorted((ROOT / "tests").glob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        classes = [n.name for n in tree.body if isinstance(n, ast.ClassDef)]
        n_tests = sum(
            isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
            and n.name.startswith("test_")
            for cls in tree.body
            if isinstance(cls, ast.ClassDef)
            for n in cls.body
        ) + sum(
            isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
            and n.name.startswith("test_")
            for n in tree.body
        )
        summary = f" — {', '.join(classes)}" if classes else ""
        lines.append(f"- `{path.name}` ({n_tests} tests){summary}")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {OUT.relative_to(ROOT)} ({len(lines)} lines)")


if __name__ == "__main__":
    main()
