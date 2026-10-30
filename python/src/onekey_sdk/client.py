"""HTTP client for the OneKey REST API (read side).

Only the standard library is used so the package has no dependencies.
Values are returned to the caller and never logged by this module.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Dict, Iterator, List, Mapping, Optional

__version__ = "0.1.2"
_API = "/api/v1"


class OneKeyError(Exception):
    """An error response from OneKey, or a transport failure.

    ``status`` is the HTTP status (0 for transport errors). ``code`` is the stable
    error code from the response envelope, for example ``AUTHORIZATION_DENIED`` or
    ``PROJECT_NOT_FOUND``; branch on it rather than on ``message``.
    """

    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"{code} ({status}): {message}")
        self.status = status
        self.code = code
        self.message = message


class SecretNotFound(OneKeyError):
    """The project has no secret with that key."""

    def __init__(self, project: str, key: str):
        super().__init__(404, "SECRET_NOT_FOUND", f"{project} has no secret {key!r}")
        self.project = project
        self.key = key


@dataclass(frozen=True)
class Project:
    id: str
    name: str
    created_at: str
    updated_at: str
    my_role: Optional[str] = None


@dataclass(frozen=True)
class Group:
    id: str
    project_id: str
    project_name: str
    name: str
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class SecretMetadata:
    key: str
    version: int
    created_at: str
    updated_at: str
    description: Optional[str] = None


@dataclass(frozen=True)
class RuntimeSecrets(Mapping[str, str]):
    """Every secret of a project (all groups), as a read-only mapping of key to value."""

    project: str
    environment: str
    environment_id: str
    entries: Dict[str, str] = field(default_factory=dict)

    def __getitem__(self, key: str) -> str:
        return self.entries[key]

    def __iter__(self) -> Iterator[str]:
        return iter(self.entries)

    def __len__(self) -> int:
        return len(self.entries)

    def __repr__(self) -> str:  # never print values
        return f"RuntimeSecrets(project={self.project!r}, keys={len(self.entries)})"

    def as_env(self) -> Dict[str, str]:
        """A plain dict suitable for ``subprocess.run(..., env={**os.environ, **secrets.as_env()})``."""
        return dict(self.entries)


class OneKeyClient:
    """Reads project secrets from a OneKey server.

    ``url`` is the client endpoint, for example ``https://onekey.example.com`` or,
    behind an SSO proxy, ``https://onekey.example.com/cli``. ``token`` is a project
    runner token (``dbs_...``) or a personal token (``dpa_...``). Both default to the
    ``ONEKEY_URL`` and ``ONEKEY_TOKEN`` environment variables.
    """

    def __init__(
        self,
        url: Optional[str] = None,
        token: Optional[str] = None,
        *,
        timeout: float = 10.0,
    ):
        url = url or os.environ.get("ONEKEY_URL")
        token = token or os.environ.get("ONEKEY_TOKEN")
        if not url:
            raise ValueError("OneKey url is required (argument or ONEKEY_URL)")
        if not token:
            raise ValueError("OneKey token is required (argument or ONEKEY_TOKEN)")
        self._base = url.rstrip("/")
        self._token = token
        self.timeout = timeout

    # ----- public API -----

    def health(self) -> dict:
        """Server identity: ``product``, ``version``, ``apiVersion``, ``status``."""
        return self._get("/health")

    def projects(self) -> List[Project]:
        """Projects the token can see. Needs a personal token; runner tokens get ``AUTHORIZATION_DENIED``."""
        return [
            Project(
                id=p["id"],
                name=p["name"],
                created_at=p["createdAt"],
                updated_at=p["updatedAt"],
                my_role=p.get("myRole"),
            )
            for p in self._get("/projects")
        ]

    def groups(self, project: str) -> List[Group]:
        """Groups of a project. Groups organise secrets; they carry no permissions."""
        return [
            Group(
                id=g["id"],
                project_id=g["projectId"],
                project_name=g["projectName"],
                name=g["name"],
                created_at=g["createdAt"],
                updated_at=g["updatedAt"],
            )
            for g in self._get("/environments", {"project": project})
        ]

    def secret_names(self, project: str, group: Optional[str] = None) -> List[SecretMetadata]:
        """Key names and metadata (never values) of a project, optionally one group."""
        result: List[SecretMetadata] = []
        for g in self.groups(project):
            if group is not None and g.name != group and g.id != group:
                continue
            for s in self._get(f"/environments/{_quote(g.id)}/secrets"):
                result.append(
                    SecretMetadata(
                        key=s["key"],
                        version=s["version"],
                        created_at=s["createdAt"],
                        updated_at=s["updatedAt"],
                        description=s.get("description"),
                    )
                )
        return result

    def runtime(self, project: str) -> RuntimeSecrets:
        """Every secret value of the project, across all its groups."""
        data = self._get(f"/projects/{_quote(project)}/secrets/runtime")
        return RuntimeSecrets(
            project=data["project"],
            environment=data.get("environment", ""),
            environment_id=data.get("environmentId", ""),
            entries={e["key"]: e["value"] for e in data.get("entries", [])},
        )

    def secret(self, project: str, key: str) -> str:
        """One secret value. Raises ``SecretNotFound`` when the key does not exist."""
        secrets = self.runtime(project)
        try:
            return secrets[key]
        except KeyError:
            raise SecretNotFound(project, key) from None

    # ----- transport -----

    def _get(self, path: str, query: Optional[Mapping[str, str]] = None):
        url = f"{self._base}{_API}{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)
        request = urllib.request.Request(
            url,
            headers={
                "Authorization": f"Bearer {self._token}",
                "Accept": "application/json",
                "User-Agent": f"onekey-sdk-python/{__version__}",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                body = response.read()
        except urllib.error.HTTPError as error:
            raise _api_error(error.code, error.read()) from None
        except urllib.error.URLError as error:
            raise OneKeyError(0, "NETWORK_ERROR", str(error.reason)) from None
        return _unwrap(body)


def _quote(segment: str) -> str:
    return urllib.parse.quote(segment, safe="")


def _unwrap(body: bytes):
    try:
        envelope = json.loads(body)
    except ValueError:
        raise OneKeyError(0, "INVALID_RESPONSE", "OneKey returned a non-JSON body") from None
    if not isinstance(envelope, dict) or envelope.get("success") is not True:
        raise _api_error(200, body)
    return envelope.get("data")


def _api_error(status: int, body: bytes) -> OneKeyError:
    try:
        envelope = json.loads(body)
        errors = envelope.get("error") or {}
        code, message = next(iter(errors.items()))
        return OneKeyError(status, str(code), str(message))
    except (ValueError, AttributeError, StopIteration):
        return OneKeyError(status, "HTTP_ERROR", f"OneKey responded with HTTP {status}")
