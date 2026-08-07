from __future__ import annotations

from fastapi import Request
from fastapi.responses import JSONResponse

from ragent_agent.contracts.models import Problem


class ProblemError(Exception):
    def __init__(self, status: int, title: str, code: str, detail: str | None = None) -> None:
        self.status = status
        self.title = title
        self.code = code
        self.detail = detail
        super().__init__(detail or title)


async def problem_error_handler(request: Request, error: ProblemError) -> JSONResponse:
    problem = Problem(
        title=error.title,
        status=error.status,
        code=error.code,
        detail=error.detail,
        traceId=request.headers.get("traceparent"),
    )
    return JSONResponse(
        status_code=error.status,
        content=problem.model_dump(by_alias=True, mode="json", exclude_none=True),
        media_type="application/problem+json",
    )
