"""The release pipeline has two halves that must agree, and nothing else checks.

The workflow writes ``update.json``; the app reads it and decides from that
whether to download and install an APK. They are in different languages, in
different directories, and neither breaks the other's build — so a renamed field
would ship, and the symptom would be an installed app that has quietly stopped
noticing updates. There is no failing test in the ordinary sense: the update
simply never arrives, on someone else's phone, silently.

Design-time only, like the rest of ``tools/``. Nothing here runs on a device.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
UPDATE_CHECK = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "kotlin"
    / "io"
    / "github"
    / "paulcharp"
    / "diveslate"
    / "UpdateCheck.kt"
)
PYPROJECT = ROOT / "pyproject.toml"


def _required_keys() -> set[str]:
    """Fields the app pulls out of the manifest, from the source itself.

    ``getInt``/``getString``/``getLong`` throw on a missing key, deliberately —
    a manifest without a checksum cannot be acted on — so every one of these is
    load-bearing.
    """
    source = UPDATE_CHECK.read_text(encoding="utf-8")
    return set(re.findall(r'json\.get(?:Int|String|Long)\("([^"]+)"\)', source))


def _emitted_keys() -> set[str]:
    """Fields the workflow puts in the manifest, from its ``jq -n`` call."""
    source = WORKFLOW.read_text(encoding="utf-8")
    block = source[source.index("jq -n") : source.index("'$ARGS.named'")]
    return set(re.findall(r"--arg(?:json)?\s+(\w+)", block))


def test_manifest_carries_every_field_the_app_requires() -> None:
    required = _required_keys()
    # If this is empty the regex has stopped matching the Kotlin, which would
    # make the assertion below pass for the wrong reason.
    assert required, "found no manifest fields in UpdateCheck.kt"

    missing = required - _emitted_keys()
    assert not missing, (
        f"release.yml does not emit {sorted(missing)}, which UpdateCheck.kt "
        "requires — the app would reject every release as unreadable"
    )


def test_app_checks_the_repository_it_is_released_from() -> None:
    """The manifest URL has to name this repo, not the one it was forked from.

    A fork that leaves the constant alone builds and runs perfectly, and updates
    itself to the upstream's releases — signed with a different key, so the
    install fails on the phone, where the reason is invisible.
    """
    source = UPDATE_CHECK.read_text(encoding="utf-8")
    match = re.search(r'"(https://github\.com/[^"]+)/releases/latest/', source)
    assert match, "no /releases/latest/ manifest URL found in UpdateCheck.kt"

    declared = re.search(
        r'^Repository = "(?P<url>[^"]+)"',
        PYPROJECT.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    assert declared, "pyproject.toml declares no Repository URL"
    assert match.group(1) == declared.group("url").rstrip("/"), (
        f"the app updates from {match.group(1)} but this project is "
        f"{declared.group('url')}"
    )
