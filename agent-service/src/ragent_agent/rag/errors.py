from __future__ import annotations


class RagConfigurationError(RuntimeError):
    """Raised when an enabled RAG runtime has no deployable configuration."""


class RagAuthorizationError(RuntimeError):
    """Raised when a request cannot be safely constrained to its tenant scope."""


class RagGenerationError(RuntimeError):
    """Raised when the configured model cannot generate a usable answer."""


class RagRetrievalError(RuntimeError):
    """Raised when authorized knowledge cannot be retrieved from a configured dependency."""
