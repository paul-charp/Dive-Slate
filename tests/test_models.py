"""Core model behaviour that does not depend on a parser."""

from __future__ import annotations

import pytest

from diveslate.core.models import AIR, Cylinder, Dive, DiveLog, GasMix, Sample


class TestGasMix:
    @pytest.mark.parametrize(
        ("mix", "expected"),
        [
            (GasMix(0.21, 0.0), "Air"),
            (GasMix(0.32, 0.0), "EAN32"),
            (GasMix(0.99, 0.0), "O2"),
            (GasMix(1.0, 0.0), "O2"),
            (GasMix(0.18, 0.45), "Tx18/45"),
        ],
    )
    def test_names(self, mix: GasMix, expected: str) -> None:
        assert mix.name == expected

    def test_air_tolerance(self) -> None:
        # Computers round-trip 21% through decimal strings.
        assert GasMix(0.209).is_air
        assert not GasMix(0.32).is_air

    def test_n2_balance(self) -> None:
        assert GasMix(0.18, 0.45).n2 == pytest.approx(0.37)

    def test_mod(self) -> None:
        assert AIR.mod_m(1.4) == pytest.approx(56.67, abs=0.01)


def _dive(flags: list[bool]) -> Dive:
    return Dive(
        samples=tuple(
            Sample(time_s=float(i * 60), depth_m=10.0, in_deco=flag)
            for i, flag in enumerate(flags)
        )
    )


class TestDecoSpans:
    def test_no_deco(self) -> None:
        assert _dive([False, False, False]).deco_spans() == ()

    def test_single_span(self) -> None:
        spans = _dive([False, True, True, False]).deco_spans()
        assert len(spans) == 1
        assert (spans[0].start_s, spans[0].end_s) == (60.0, 180.0)

    def test_two_spans(self) -> None:
        spans = _dive([True, False, True, False]).deco_spans()
        assert len(spans) == 2

    def test_span_open_at_end_is_closed_at_last_sample(self) -> None:
        """A dive that surfaces still in deco must still yield a closed span."""
        spans = _dive([False, True, True]).deco_spans()
        assert len(spans) == 1
        assert spans[0].end_s == 120.0

    def test_duration(self) -> None:
        span = _dive([False, True, True, False]).deco_spans()[0]
        assert span.duration_s == 120.0


class TestDerivedDepth:
    def test_mean_depth_prefers_logged_value(self) -> None:
        dive = Dive(
            samples=(Sample(0, 0.0), Sample(60, 20.0)),
            mean_depth_m=15.0,
        )
        assert dive.computed_mean_depth_m == 15.0

    def test_mean_depth_computed_time_weighted(self) -> None:
        dive = Dive(samples=(Sample(0, 0.0), Sample(60, 20.0)))
        assert dive.computed_mean_depth_m == pytest.approx(10.0)

    def test_mean_depth_none_without_enough_samples(self) -> None:
        assert Dive(samples=(Sample(0, 5.0),)).computed_mean_depth_m is None

    def test_max_depth_falls_back_to_summary(self) -> None:
        assert Dive(max_depth_m=31.0).computed_max_depth_m == 31.0


class TestDiveLog:
    def test_only_rejects_multiple(self) -> None:
        log = DiveLog(dives=(Dive(), Dive()))
        with pytest.raises(ValueError, match="expected exactly one dive"):
            log.only()

    def test_len_and_index(self) -> None:
        log = DiveLog(dives=(Dive(number=1), Dive(number=2)))
        assert len(log) == 2
        assert log[1].number == 2


class TestTitle:
    def test_number_and_site(self) -> None:
        assert Dive(number=7, site="Blue Hole").title == "#7 · Blue Hole"

    def test_fallback(self) -> None:
        assert Dive().title == "Dive"


class TestDecoTime:
    """Deco *time* is the hang, not the whole span of owing deco."""

    def _dive(self) -> Dive:
        # Deep and in deco from 5 min with a 6 m ceiling, but only reaches that
        # ceiling at 20 min; obligation clears at 30 min.
        samples = []
        for minute in range(41):
            t = minute * 60.0
            if minute < 5:
                depth, deco, ceiling = 40.0, False, None
            elif minute < 20:
                depth, deco, ceiling = 40.0, True, 6.0
            elif minute < 30:
                depth, deco, ceiling = 6.0, True, 6.0
            else:
                depth, deco, ceiling = 3.0, False, None
            samples.append(Sample(t, depth, in_deco=deco, stop_depth_m=ceiling))
        return Dive(samples=tuple(samples))

    def test_measures_the_hang_not_the_obligation(self) -> None:
        dive = self._dive()
        obligation = sum(s.duration_s for s in dive.deco_spans())
        assert obligation == 25 * 60  # 5 min -> 30 min, mostly on the bottom
        assert dive.deco_time_s() == 10 * 60  # 20 min -> 30 min, the actual stops

    def test_none_without_deco(self) -> None:
        dive = Dive(samples=(Sample(0, 0.0), Sample(60, 10.0)))
        assert dive.deco_time_s() is None

    def test_none_when_the_ceiling_is_never_reached(self) -> None:
        """Surfacing still in deco means no hang was served."""
        dive = Dive(
            samples=(
                Sample(0, 40.0, in_deco=True, stop_depth_m=6.0),
                Sample(60, 40.0, in_deco=True, stop_depth_m=6.0),
            )
        )
        assert dive.deco_time_s() is None

    def test_tolerance_is_configurable(self) -> None:
        dive = self._dive()
        assert dive.deco_time_s(tolerance_m=0.0) == 10 * 60

    def _reincurred(self) -> Dive:
        # Clears deco at 30 min, drops back down, and incurs it again at 40 min.
        samples = []
        for minute in range(66):
            t = minute * 60.0
            if minute < 5:
                depth, deco, ceiling = 40.0, False, None
            elif minute < 20:
                depth, deco, ceiling = 40.0, True, 6.0
            elif minute < 30:
                depth, deco, ceiling = 6.0, True, 6.0
            elif minute < 40:
                depth, deco, ceiling = 5.0, False, None
            elif minute < 50:
                depth, deco, ceiling = 30.0, True, 9.0
            elif minute < 60:
                depth, deco, ceiling = 9.0, True, 9.0
            else:
                depth, deco, ceiling = 3.0, False, None
            samples.append(Sample(t, depth, in_deco=deco, stop_depth_m=ceiling))
        return Dive(samples=tuple(samples))

    def test_reincurred_deco_counts_each_hang_separately(self) -> None:
        """The cleared interval between two obligations is not deco time."""
        dive = self._reincurred()
        assert len(dive.deco_spans()) == 2
        # Ten minutes hung twice — not the 40 min spanning both plus the gap.
        assert dive.deco_time_s() == 20 * 60

    def test_unserved_span_does_not_void_a_served_one(self) -> None:
        """Surfacing in deco after an earlier hang still reports that hang."""
        samples = [Sample(0, 40.0, in_deco=True, stop_depth_m=6.0)]
        samples += [
            Sample(m * 60.0, 6.0, in_deco=True, stop_depth_m=6.0) for m in range(1, 11)
        ]
        samples.append(Sample(660.0, 3.0))
        # Second obligation, never served: straight back down and the log ends.
        samples += [
            Sample(m * 60.0, 30.0, in_deco=True, stop_depth_m=9.0)
            for m in range(12, 20)
        ]
        # Ceiling reached at 1 min, obligation clears at 11 min: a 10 min hang.
        assert Dive(samples=tuple(samples)).deco_time_s() == 10 * 60


class TestGradientFactors:
    """GFs live in a free-text deco-model label, so they are matched, not parsed."""

    @pytest.mark.parametrize(
        ("label", "expected"),
        [
            ("GF 70/80", (70, 80)),
            ("ZHL16C GF30/85", (30, 85)),
            ("Buhlmann ZH-L16C + GF 30/85", (30, 85)),
            ("GF 100/100", (100, 100)),
        ],
    )
    def test_recovered(self, label: str, expected: tuple[int, int]) -> None:
        assert Dive(deco_model=label).gradient_factors == expected

    @pytest.mark.parametrize(
        "label",
        [
            "VPM-B +3",  # no gradient factors exist for VPM
            "ZH-L16C",
            "GF 120/50",  # low above high, and out of range
            "",
        ],
    )
    def test_rejected_rather_than_guessed(self, label: str) -> None:
        assert Dive(deco_model=label).gradient_factors is None

    def test_none_without_a_model(self) -> None:
        assert Dive().gradient_factors is None


class TestGasUsed:
    def test_sums_across_cylinders(self) -> None:
        dive = Dive(
            cylinders=(
                Cylinder(size_l=24.0, start_bar=200.0, end_bar=75.0),  # 3000 L
                Cylinder(size_l=5.5, start_bar=90.0, end_bar=20.0),  #  385 L
            )
        )
        assert dive.gas_used_l == pytest.approx(3385.0)

    def test_none_without_enough_data(self) -> None:
        assert Dive(cylinders=(Cylinder(size_l=12.0),)).gas_used_l is None

    def test_refilled_cylinder_is_dropped_not_subtracted(self) -> None:
        """A tank that came back fuller is a typo, not negative consumption."""
        dive = Dive(
            cylinders=(
                Cylinder(size_l=24.0, start_bar=200.0, end_bar=75.0),
                Cylinder(size_l=10.0, start_bar=50.0, end_bar=200.0),
            )
        )
        assert dive.gas_used_l == pytest.approx(3000.0)
