"""The contract every log parser implements."""

from __future__ import annotations

from pathlib import Path
from typing import ClassVar, Protocol, runtime_checkable

from diveslate.core.models import DiveLog

__all__ = ["ParseError", "Parser"]


class ParseError(ValueError):
    """Raised when a file is the right format but its content is unusable."""


@runtime_checkable
class Parser(Protocol):
    """A dive log reader.

    Implementations are registered through the ``diveslate.parsers`` entry-point
    group and selected by :func:`diveslate.parsers.detect.sniff`, which relies on
    :meth:`sniff` rather than the file extension — dive logs are routinely
    renamed, and both Subsurface and UDDF files turn up as plain ``.xml``.
    """

    #: Extensions this parser claims, used only to order sniffing attempts.
    extensions: ClassVar[tuple[str, ...]]

    #: Human-readable format name, shown in errors and CLI output.
    format_name: ClassVar[str]

    @classmethod
    def sniff(cls, text: str) -> bool:
        """Whether ``text`` (the head of a file) looks like this format."""
        ...

    @classmethod
    def parse(cls, text: str, *, source: str | None = None) -> DiveLog:
        """Parse whole-file ``text`` into a :class:`DiveLog`."""
        ...

    @classmethod
    def parse_file(cls, path: str | Path) -> DiveLog:
        """Parse the file at ``path``."""
        ...
