"""CLI behaviour."""

from __future__ import annotations

from pathlib import Path
from xml.etree import ElementTree as ET

import pytest

from diveslate.cli import main


class TestRenderCommand:
    def test_writes_svg(self, ssrf_path: Path, tmp_path: Path) -> None:
        out = tmp_path / "profile.svg"
        assert main(["render", str(ssrf_path), "-o", str(out)]) == 0
        assert out.exists()
        ET.fromstring(out.read_text(encoding="utf-8"))

    def test_size_flags(self, ssrf_path: Path, tmp_path: Path) -> None:
        out = tmp_path / "small.svg"
        main(
            [
                "render",
                str(ssrf_path),
                "-o",
                str(out),
                "--width",
                "640",
                "--height",
                "360",
            ]
        )
        root = ET.fromstring(out.read_text(encoding="utf-8"))
        assert root.get("width") == "640"

    def test_theme_flag(self, ssrf_path: Path, tmp_path: Path) -> None:
        out = tmp_path / "light.svg"
        main(["render", str(ssrf_path), "-o", str(out), "--theme", "light"])
        assert out.exists()

    def test_layer_flags(self, ssrf_path: Path, tmp_path: Path) -> None:
        out = tmp_path / "bare.svg"
        main(
            [
                "render",
                str(ssrf_path),
                "-o",
                str(out),
                "--no-stats",
                "--no-grid",
                "--no-legend",
            ]
        )
        assert "MAX DEPTH" not in out.read_text(encoding="utf-8")

    def test_unsupported_extension(self, ssrf_path: Path, tmp_path: Path) -> None:
        assert main(["render", str(ssrf_path), "-o", str(tmp_path / "x.gif")]) == 2

    def test_missing_file(self, tmp_path: Path) -> None:
        assert (
            main(["render", str(tmp_path / "nope.ssrf"), "-o", str(tmp_path / "x.svg")])
            == 2
        )

    def test_uddf_renders(self, uddf_path: Path, tmp_path: Path) -> None:
        out = tmp_path / "uddf.svg"
        assert main(["render", str(uddf_path), "-o", str(out)]) == 0


class TestInfoCommand:
    def test_reports_key_fields(
        self, ssrf_path: Path, capsys: pytest.CaptureFixture[str]
    ) -> None:
        assert main(["info", str(ssrf_path)]) == 0
        out = capsys.readouterr().out
        assert "Test Wreck" in out
        assert "38.0 m" in out
        assert "EAN50" in out
        assert "deco" in out.lower()


class TestBackendsCommand:
    def test_lists_themes(self, capsys: pytest.CaptureFixture[str]) -> None:
        assert main(["backends"]) == 0
        out = capsys.readouterr().out
        assert "slate" in out
        assert "light" in out
