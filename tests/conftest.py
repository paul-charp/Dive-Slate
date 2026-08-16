"""Shared fixtures."""

from __future__ import annotations

from pathlib import Path

import pytest

from diveslate import parse_file
from diveslate.core.models import Dive, DiveLog

DATA = Path(__file__).parent / "data"


@pytest.fixture
def ssrf_path() -> Path:
    return DATA / "sample.ssrf"


@pytest.fixture
def uddf_path() -> Path:
    return DATA / "sample.uddf"


@pytest.fixture
def ssrf_log(ssrf_path: Path) -> DiveLog:
    return parse_file(ssrf_path)


@pytest.fixture
def ssrf_dive(ssrf_log: DiveLog) -> Dive:
    return ssrf_log.only()


@pytest.fixture
def uddf_dive(uddf_path: Path) -> Dive:
    return parse_file(uddf_path).only()
