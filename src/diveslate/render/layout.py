"""Plot geometry: margins, scales, and tick selection.

The depth axis is inverted — depth grows *downward*, which is both physically
intuitive and what every dive computer draws — and it is always anchored at the
surface. A profile is read against the surface line, so a depth axis that started
at the shallowest sample would make a 30–40 m dive and a 3–13 m dive look
identical. Zero is not negotiable here even though most charting advice about
"don't truncate the axis" is aimed at bar charts.
"""

from __future__ import annotations

from dataclasses import dataclass

__all__ = ["Layout", "Margins", "Scale", "nice_step"]

# Tick steps divers read naturally. Depth in metres, time in seconds — no
# generated 1-2-5 decades, because 7.5 m and 2.5 min are not useful gradations.
_DEPTH_STEPS = (1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 50.0)
_TIME_STEPS = (30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0)


def nice_step(span: float, target_ticks: int, steps: tuple[float, ...]) -> float:
    """Pick the step from ``steps`` giving a tick count closest to ``target_ticks``."""
    if span <= 0:
        return steps[0]
    best = steps[-1]
    best_error = float("inf")
    for step in steps:
        error = abs(span / step - target_ticks)
        if error < best_error:
            best, best_error = step, error
    return best


@dataclass(frozen=True, slots=True)
class Margins:
    top: float
    right: float
    bottom: float
    left: float


@dataclass(frozen=True, slots=True)
class Scale:
    """A linear mapping from data units to pixels."""

    domain_min: float
    domain_max: float
    range_min: float
    range_max: float

    def __call__(self, value: float) -> float:
        span = self.domain_max - self.domain_min
        if span == 0:
            return self.range_min
        t = (value - self.domain_min) / span
        return self.range_min + t * (self.range_max - self.range_min)

    def ticks(self, step: float) -> list[float]:
        """Tick values at multiples of ``step`` across the domain."""
        if step <= 0:
            return []
        values: list[float] = []
        start = int(self.domain_min / step)
        value = start * step
        # Guard against a pathological step producing an unbounded loop.
        for _ in range(1024):
            if value > self.domain_max + 1e-9:
                break
            if value >= self.domain_min - 1e-9:
                values.append(value)
            value += step
        return values


@dataclass(frozen=True, slots=True)
class Layout:
    """Everything the drawing code needs to place a mark."""

    width: float
    height: float
    margins: Margins
    x: Scale
    y: Scale
    time_step: float
    depth_step: float

    @property
    def plot_left(self) -> float:
        return self.margins.left

    @property
    def plot_right(self) -> float:
        return self.width - self.margins.right

    @property
    def plot_top(self) -> float:
        return self.margins.top

    @property
    def plot_bottom(self) -> float:
        return self.height - self.margins.bottom

    @property
    def plot_width(self) -> float:
        return max(0.0, self.plot_right - self.plot_left)

    @property
    def plot_height(self) -> float:
        return max(0.0, self.plot_bottom - self.plot_top)

    @classmethod
    def build(
        cls,
        *,
        width: float,
        height: float,
        duration_s: float,
        max_depth_m: float,
        margins: Margins,
        target_time_ticks: int = 8,
        target_depth_ticks: int = 6,
    ) -> Layout:
        duration_s = max(duration_s, 1.0)
        max_depth_m = max(max_depth_m, 1.0)

        depth_step = nice_step(max_depth_m, target_depth_ticks, _DEPTH_STEPS)
        # Extend the axis to the next whole step so the deepest point sits inside
        # the plot rather than exactly on its edge.
        depth_max = (int(max_depth_m / depth_step) + 1) * depth_step

        time_step = nice_step(duration_s, target_time_ticks, _TIME_STEPS)

        return cls(
            width=width,
            height=height,
            margins=margins,
            x=Scale(0.0, duration_s, margins.left, width - margins.right),
            # Inverted: depth 0 (surface) at the top of the plot.
            y=Scale(0.0, depth_max, margins.top, height - margins.bottom),
            time_step=time_step,
            depth_step=depth_step,
        )
