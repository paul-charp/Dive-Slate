"""Make stdout able to carry the characters these scripts actually print.

Windows consoles default to a legacy code page — cp1252 on the machine this was
written on — and the palette summaries print `ΔE`, `°` and en-dashes, because
those are the units the gates are expressed in.

The failure this prevents is worse than a mangled character. Both exporters
write their output file first and print the summary afterwards, so an encoding
error killed the script *after* it had done its job: exit 1 on a run that
actually succeeded. In a `&&` chain that silently skipped the following step,
which is how `generate_kotlin_themes.py` came to be quietly not running after
`export_theme_tokens.py` — the themes were regenerated and the Kotlin was not.

`errors="replace"` rather than strict: a summary line is a progress report, and
losing a glyph from it should never be able to fail a build.
"""

from __future__ import annotations

import io
import sys


def use_utf8_stdout() -> None:
    """Reconfigure stdout to UTF-8, where the stream supports it."""
    stream = sys.stdout
    if isinstance(stream, io.TextIOWrapper):
        stream.reconfigure(encoding="utf-8", errors="replace")
