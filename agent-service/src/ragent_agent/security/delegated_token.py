from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import jwt
from jwt import InvalidTokenError

from ragent_agent.api.errors import ProblemError
from ragent_agent.config import AgentServiceSettings
from ragent_agent.contracts.models import AccessContext


@dataclass(frozen=True)
class DelegatedPrincipal:
    user_id: str
    tenant_id: str | None
    roles: frozenset[str]
    allowed_knowledge_base_ids: frozenset[str]
    tool_scopes: frozenset[str]


class DelegatedTokenVerifier:
    def __init__(self, settings: AgentServiceSettings) -> None:
        self._secret = settings.delegated_token_hmac_secret
        self._issuer = settings.delegated_token_issuer
        self._audience = settings.delegated_token_audience

    def verify(self, authorization: str | None) -> DelegatedPrincipal:
        if authorization is None:
            raise ProblemError(401, "Unauthorized", "missing_delegated_token")

        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise ProblemError(401, "Unauthorized", "invalid_delegated_token")

        try:
            claims = jwt.decode(
                token,
                self._secret,
                algorithms=["HS256"],
                audience=self._audience,
                issuer=self._issuer,
                options={"require": ["sub", "iat", "exp"]},
            )
        except InvalidTokenError as error:
            raise ProblemError(401, "Unauthorized", "invalid_delegated_token") from error

        return DelegatedPrincipal(
            user_id=self._required_string(claims, "sub"),
            tenant_id=self._optional_string(claims, "tenantId"),
            roles=self._string_set(claims, "roles"),
            allowed_knowledge_base_ids=self._string_set(claims, "allowedKnowledgeBaseIds"),
            tool_scopes=self._string_set(claims, "toolScopes"),
        )

    def validate_access(self, principal: DelegatedPrincipal, access: AccessContext) -> None:
        if access.user_id != principal.user_id:
            raise ProblemError(403, "Forbidden", "access_user_mismatch")
        if access.tenant_id != principal.tenant_id:
            raise ProblemError(403, "Forbidden", "access_tenant_mismatch")
        if not set(access.roles).issubset(principal.roles):
            raise ProblemError(403, "Forbidden", "access_role_mismatch")
        if not set(access.allowed_knowledge_base_ids).issubset(principal.allowed_knowledge_base_ids):
            raise ProblemError(403, "Forbidden", "access_knowledge_base_mismatch")
        if not set(access.tool_scopes).issubset(principal.tool_scopes):
            raise ProblemError(403, "Forbidden", "access_tool_scope_mismatch")

    @staticmethod
    def _required_string(claims: dict[str, Any], name: str) -> str:
        value = claims.get(name)
        if not isinstance(value, str) or not value:
            raise ProblemError(401, "Unauthorized", "invalid_delegated_token")
        return value

    @staticmethod
    def _optional_string(claims: dict[str, Any], name: str) -> str | None:
        value = claims.get(name)
        if value is None:
            return None
        if not isinstance(value, str):
            raise ProblemError(401, "Unauthorized", "invalid_delegated_token")
        return value

    @staticmethod
    def _string_set(claims: dict[str, Any], name: str) -> frozenset[str]:
        value = claims.get(name)
        if not isinstance(value, list) or any(not isinstance(item, str) or not item for item in value):
            raise ProblemError(401, "Unauthorized", "invalid_delegated_token")
        return frozenset(value)
