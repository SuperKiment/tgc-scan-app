"""``manifest.json``: writing the new one, reading the previously published one.

The shape is the one of ``docs/pack-format.md`` §6 (authoritative), read by the app's
``PackManifest`` / ``PackEntry``. :func:`validation_errors` repeats the app's own checks
(``PackManifest.validationError``, ``PackEntry.validationError``, ``PackPaths.isValidLang`` and
``PackPaths.isValidArchiveName``) so a manifest the app would reject at its network boundary is
refused here, before anything is published.

The previous manifest is read from ``https://github.com/<REPO>/releases/latest/download/manifest.json``
for the 2 % guard (:mod:`tcgscan_pipeline.guard`). A 404 is the very first run and is tolerated; any
other failure is an error, because a guard that cannot compare must not wave a release through.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from collections.abc import Mapping
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any

import requests

from . import config
from .tcgdex import read_capped

if TYPE_CHECKING:  # packbuild pulls in numpy / onnxruntime / cv2, which a manifest never needs
    from .packbuild import PackBuild

log = logging.getLogger(__name__)

MAX_MANIFEST_BYTES = 1024 * 1024
"""A real manifest is under 1 KiB; anything larger than this is not one."""

FETCH_ATTEMPTS = 3
"""Tries to read the published manifest (the first one plus two retries)."""

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})

# Same rules as the app: PackPaths.LANG_PATTERN, PackPaths.ARCHIVE_NAME_PATTERN and
# PackEntry.SHA256_PATTERN (Kotlin ``Regex.matches`` is a full match), PackManifest.MAX_VERSION_LENGTH.
_LANG_PATTERN = re.compile(r"[a-z]{2,3}")
_ARCHIVE_NAME_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
_SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")
_MAX_VERSION_LENGTH = 64

_REPO_PATTERN = re.compile(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")


class ManifestError(RuntimeError):
    """A manifest cannot be built, read or compared; nothing is published."""


def is_valid_lang(lang: str) -> bool:
    """True for a language code the app accepts as a manifest key (``PackPaths.isValidLang``)."""
    return _LANG_PATTERN.fullmatch(lang) is not None


# --- writing -----------------------------------------------------------------------------------


def build_manifest(
    packs: Mapping[str, PackBuild], *, now: datetime | None = None
) -> dict[str, Any]:
    """Return the manifest of ``packs`` (language code -> built pack).

    ``version`` is the UTC build date, what the app compares to offer an update (§6).
    """
    moment = (now if now is not None else datetime.now(timezone.utc)).astimezone(timezone.utc)
    return {
        "schema": config.MANIFEST_SCHEMA,
        "version": moment.strftime("%Y-%m-%d"),
        "generatedAt": moment.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packs": {
            lang: {
                "file": built.gz_path.name,
                "bytes": built.gz_bytes,
                "sha256": built.sha256,
                "cardCount": built.card_count,
                "packSchema": config.PACK_SCHEMA,
                "installedBytes": built.installed_bytes,
            }
            for lang, built in sorted(packs.items())
        },
    }


def validation_errors(manifest: Mapping[str, Any]) -> list[str]:
    """Return why the app would reject ``manifest``; empty when it is usable."""
    errors: list[str] = []
    if manifest.get("schema") != config.MANIFEST_SCHEMA:
        errors.append(f"schema must be {config.MANIFEST_SCHEMA}, got {manifest.get('schema')!r}")
    version = manifest.get("version")
    if not isinstance(version, str) or not version.strip():
        errors.append("empty version")
    elif len(version) > _MAX_VERSION_LENGTH:
        errors.append("version too long")

    packs = manifest.get("packs")
    if not isinstance(packs, Mapping) or not packs:
        errors.append("no pack published")
        return errors
    for lang, entry in packs.items():
        if not isinstance(lang, str) or not is_valid_lang(lang):
            errors.append(f"invalid language code: {lang!r}")
            continue
        errors.extend(f"pack {lang}: {reason}" for reason in _entry_errors(entry))
    return errors


def _entry_errors(entry: Any) -> list[str]:
    if not isinstance(entry, Mapping):
        return ["entry is not an object"]
    errors: list[str] = []
    file = entry.get("file")
    if not isinstance(file, str) or _ARCHIVE_NAME_PATTERN.fullmatch(file) is None:
        errors.append(f"invalid file name: {file!r}")
    sha256 = entry.get("sha256")
    if not isinstance(sha256, str) or _SHA256_PATTERN.fullmatch(sha256) is None:
        errors.append(f"invalid sha256: {sha256!r}")
    for field in ("bytes", "installedBytes", "cardCount", "packSchema"):
        if _positive_int(entry.get(field)) is None:
            errors.append(f"non-positive {field}: {entry.get(field)!r}")
    return errors


def write_manifest(manifest: Mapping[str, Any], path: Path) -> None:
    """Validate ``manifest`` and write it to ``path`` through a ``.part`` file.

    Raises:
        ManifestError: the app would reject the manifest.
    """
    errors = validation_errors(manifest)
    if errors:
        raise ManifestError("invalid manifest: " + "; ".join(errors))
    part = path.with_name(path.name + ".part")
    try:
        with open(part, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(part, path)
    except BaseException:
        part.unlink(missing_ok=True)
        raise


# --- reading the previous manifest -------------------------------------------------------------


def resolve_repo(environ: Mapping[str, str] | None = None) -> str:
    """Return the ``owner/repo`` of ``TCGSCAN_REPO``, the placeholder when it is unset.

    Raises:
        ManifestError: the value is not of the form ``owner/repo`` (it is pasted into a URL).
    """
    source = os.environ if environ is None else environ
    repo = source.get(config.REPO_ENV_VAR, "").strip() or config.GITHUB_REPO_PLACEHOLDER
    if _REPO_PATTERN.fullmatch(repo) is None:
        raise ManifestError(f"{config.REPO_ENV_VAR} must look like owner/repo, got {repo!r}")
    return repo


def fetch_previous(
    repo: str, *, session: requests.Session | None = None, timeout_s: float | None = None
) -> dict[str, Any] | None:
    """Fetch the manifest of the latest release of ``repo``; ``None`` when there is none (404).

    Raises:
        ManifestError: the release host is unreachable, answers anything but 200 / 404, or the body
            is not a manifest.
    """
    url = config.RELEASE_DOWNLOAD_URL_TEMPLATE.format(repo=repo, file=config.MANIFEST_FILENAME)
    timeout = config.REQUEST_TIMEOUT_S if timeout_s is None else timeout_s
    http = session or requests.Session()
    try:
        for attempt in range(1, FETCH_ATTEMPTS + 1):
            last = attempt == FETCH_ATTEMPTS
            try:
                response = http.get(
                    url,
                    headers={"User-Agent": config.USER_AGENT, "Accept": "application/json"},
                    timeout=timeout,
                    stream=True,
                )
            except requests.RequestException as exc:
                failure = f"{type(exc).__name__}: {exc}"
            else:
                with response:
                    if response.status_code == 404:
                        log.info("no published manifest at %s (first run)", url)
                        return None
                    if response.status_code == 200:
                        body = read_capped(response, MAX_MANIFEST_BYTES)
                        return parse_previous(body, url)
                    failure = f"HTTP {response.status_code}"
                    if response.status_code not in _RETRYABLE_STATUS:
                        raise ManifestError(f"cannot read the published manifest {url}: {failure}")
            if last:
                raise ManifestError(f"cannot read the published manifest {url}: {failure}")
            delay = min(
                config.RETRY_BACKOFF_BASE_S * 2 ** (attempt - 1), config.RETRY_BACKOFF_MAX_S
            )
            log.warning("manifest fetch failed (%s), retrying in %.0f s", failure, delay)
            time.sleep(delay)
    finally:
        if session is None:
            http.close()
    raise AssertionError("unreachable")  # pragma: no cover - the loop returns or raises


def load_previous(path: Path) -> dict[str, Any]:
    """Read a previous manifest from a local file, for a dry run of the guard.

    Raises:
        ManifestError: the file is unreadable or is not a manifest.
    """
    try:
        body = path.read_bytes()
    except OSError as exc:
        raise ManifestError(f"cannot read the previous manifest {path}: {exc}") from exc
    return parse_previous(body[: MAX_MANIFEST_BYTES + 1], str(path))


def parse_previous(body: bytes, source: str) -> dict[str, Any]:
    """Decode a previous manifest and check it has the shape the guard compares.

    Only the shape the guard relies on is enforced (``packs`` -> language -> ``cardCount``): the
    previous manifest was already published, so what matters is that it can be compared with.
    """
    if len(body) > MAX_MANIFEST_BYTES:
        raise ManifestError(f"previous manifest {source} is larger than {MAX_MANIFEST_BYTES} B")
    try:
        manifest = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ManifestError(f"previous manifest {source} is not JSON: {exc}") from exc
    card_counts(manifest, source)
    return manifest


def card_counts(manifest: Any, source: str = "manifest") -> dict[str, int]:
    """Return ``{language: cardCount}`` of ``manifest``.

    Raises:
        ManifestError: ``packs`` or one ``cardCount`` is missing or malformed.
    """
    packs = manifest.get("packs") if isinstance(manifest, Mapping) else None
    if not isinstance(packs, Mapping):
        raise ManifestError(f"{source} has no 'packs' object")
    counts: dict[str, int] = {}
    for lang, entry in packs.items():
        count = _positive_int(entry.get("cardCount")) if isinstance(entry, Mapping) else None
        if count is None:
            raise ManifestError(f"{source}: pack {lang!r} has no positive integer cardCount")
        counts[str(lang)] = count
    return counts


def _positive_int(value: object) -> int | None:
    """``value`` when it is a genuine positive integer (``True`` is not one), else ``None``."""
    if isinstance(value, int) and not isinstance(value, bool) and value > 0:
        return value
    return None
