from __future__ import annotations

from typing import Protocol

from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Counter, Gauge, Histogram, generate_latest


class RunMetrics(Protocol):
    def started(self, agent_type: str) -> None: ...

    def finished(self, agent_type: str, status: str, duration_seconds: float) -> None: ...


class NoOpRunMetrics:
    def started(self, agent_type: str) -> None:
        pass

    def finished(self, agent_type: str, status: str, duration_seconds: float) -> None:
        pass


class PrometheusRunMetrics:
    def __init__(self) -> None:
        self._registry = CollectorRegistry()
        self._runs = Counter(
            "ragent_agent_runs",
            "Total Python Agent runs by terminal status.",
            ("agent_type", "status"),
            registry=self._registry,
        )
        self._active = Gauge(
            "ragent_agent_active_runs",
            "Current Python Agent runs.",
            ("agent_type",),
            registry=self._registry,
        )
        self._duration = Histogram(
            "ragent_agent_run_duration_seconds",
            "Python Agent run duration by terminal status.",
            ("agent_type", "status"),
            registry=self._registry,
            buckets=(0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300),
        )

    def started(self, agent_type: str) -> None:
        self._active.labels(agent_type=agent_type).inc()

    def finished(self, agent_type: str, status: str, duration_seconds: float) -> None:
        self._runs.labels(agent_type=agent_type, status=status).inc()
        self._active.labels(agent_type=agent_type).dec()
        self._duration.labels(agent_type=agent_type, status=status).observe(duration_seconds)

    def render(self) -> tuple[bytes, str]:
        return generate_latest(self._registry), CONTENT_TYPE_LATEST
