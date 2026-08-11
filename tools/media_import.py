#!/usr/bin/env python3
"""Import external video/article media into local MinIO and SeekFlux.

The importer is resumable: the backend source identity is unique and a local state
file avoids downloading an already registered asset again.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
STATE_DIR = ROOT / ".local" / "imports"
BUCKET = "seekflux-media"


def load_dotenv() -> None:
    env_path = ROOT / ".env"
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


@dataclass(frozen=True)
class ImportItem:
    provider: str
    external_id: str
    content_type: str
    creator_id: str
    title: str
    description: str
    body: str
    tags: list[str]
    media_sources: list[str]
    source_page_uri: str
    source_author: str
    license_name: str

    @property
    def key(self) -> str:
        return f"{self.provider}:{self.external_id}"


class ImportState:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.values: dict[str, dict[str, Any]] = {}
        if path.exists():
            self.values = json.loads(path.read_text(encoding="utf-8"))

    def contains(self, key: str) -> bool:
        return key in self.values

    def record(self, key: str, result: dict[str, Any]) -> None:
        self.values[key] = {
            "contentId": result.get("contentId", ""),
            "status": result.get("status", ""),
            "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.values, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.path)


def request_json(url: str, *, method: str = "GET", payload: dict[str, Any] | None = None) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Accept": "application/json", "Content-Type": "application/json", "User-Agent": "SeekFlux/1.0"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code} {url}: {detail[:500]}") from error


def find_mc() -> Path:
    configured = os.getenv("MINIO_MC_BIN", "").strip()
    if configured:
        path = Path(configured)
        if path.is_file():
            return path
        raise RuntimeError(f"MINIO_MC_BIN does not exist: {path}")
    candidates = sorted((ROOT / ".runtime").glob("minio-*/mc"), reverse=True)
    if candidates:
        return candidates[0]
    raise RuntimeError("MinIO client is missing; run ./seekflux.sh start first")


def configure_minio(mc: Path) -> tuple[dict[str, str], str]:
    api_port = os.getenv("MINIO_API_PORT", "9000")
    endpoint = os.getenv("MINIO_PUBLIC_BASE", f"http://127.0.0.1:{api_port}").rstrip("/")
    config_dir = ROOT / ".local" / "minio" / "mc-config"
    config_dir.mkdir(parents=True, exist_ok=True)
    environment = {**os.environ, "MC_CONFIG_DIR": str(config_dir)}
    subprocess.run(
        [str(mc), "alias", "set", "seekflux-local", endpoint,
         os.getenv("MINIO_ROOT_USER", "seekflux"),
         os.getenv("MINIO_ROOT_PASSWORD", "seekflux_local_secret")],
        check=True,
        env=environment,
        stdout=subprocess.DEVNULL,
    )
    subprocess.run([str(mc), "mb", "--ignore-existing", f"seekflux-local/{BUCKET}"],
                   check=True, env=environment, stdout=subprocess.DEVNULL)
    subprocess.run([str(mc), "anonymous", "set", "download", f"seekflux-local/{BUCKET}"],
                   check=True, env=environment, stdout=subprocess.DEVNULL)
    return environment, endpoint


def extension_for(source: str, content_type: str, content_type_header: str) -> str:
    suffix = Path(urllib.parse.urlparse(source).path).suffix.lower()
    if suffix and len(suffix) <= 8:
        return suffix
    guessed = mimetypes.guess_extension(content_type_header.split(";", 1)[0].strip())
    return guessed or (".mp4" if content_type == "VIDEO" else ".jpg")


def download(source: str, destination_without_suffix: Path, content_type: str) -> Path:
    request = urllib.request.Request(source, headers={"User-Agent": "SeekFlux/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        extension = extension_for(source, content_type, response.headers.get("Content-Type", ""))
        destination = destination_without_suffix.with_suffix(extension)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        return destination


def upload_assets(item: ImportItem, mc: Path, mc_env: dict[str, str], public_base: str) -> list[str]:
    result: list[str] = []
    download_dir = STATE_DIR / "downloads" / item.provider / item.external_id
    for index, source in enumerate(item.media_sources):
        digest = hashlib.sha256(source.encode("utf-8")).hexdigest()[:12]
        local_file = download(source, download_dir / f"{index:02d}-{digest}", item.content_type)
        object_name = f"{item.provider}/{item.content_type.lower()}/{item.external_id}/{local_file.name}"
        subprocess.run(
            [str(mc), "cp", str(local_file), f"seekflux-local/{BUCKET}/{object_name}"],
            check=True,
            env=mc_env,
            stdout=subprocess.DEVNULL,
        )
        result.append(f"{public_base}/{BUCKET}/{urllib.parse.quote(object_name, safe='/')}")
    return result


def register(item: ImportItem, asset_uris: list[str], content_api: str) -> dict[str, Any]:
    payload = {
        "creatorId": item.creator_id,
        "contentType": item.content_type,
        "mediaUri": asset_uris[0],
        "assetUris": asset_uris,
        "title": item.title[:200],
        "description": item.description[:4000],
        "body": item.body[:100000],
        "sourceTags": list(dict.fromkeys(tag.strip() for tag in item.tags if tag.strip()))[:50],
        "sourceProvider": item.provider,
        "externalId": item.external_id,
        "sourcePageUri": item.source_page_uri,
        "sourceAuthor": item.source_author,
        "licenseName": item.license_name,
    }
    return request_json(f"{content_api.rstrip('/')}/v1/contents", method="POST", payload=payload)


def pixabay_items(media_type: str, query: str, limit: int) -> Iterable[ImportItem]:
    api_key = os.getenv("PIXABAY_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("PIXABAY_API_KEY is required; add your own key to .env")
    endpoint = "videos" if media_type == "video" else "api"
    params = urllib.parse.urlencode({"key": api_key, "q": query, "per_page": min(200, max(3, limit)), "safesearch": "true"})
    data = request_json(f"https://pixabay.com/api/{endpoint}/?{params}")
    for hit in data.get("hits", [])[:limit]:
        raw_tags = [value.strip() for value in hit.get("tags", "").split(",") if value.strip()]
        if media_type == "video":
            variants = hit.get("videos", {})
            selected = next((variants.get(name, {}).get("url") for name in ("medium", "small", "tiny", "large")
                             if variants.get(name, {}).get("url")), None)
            sources = [selected] if selected else []
            content_type = "VIDEO"
            noun = "视频"
        else:
            selected = hit.get("largeImageURL") or hit.get("webformatURL")
            sources = [selected] if selected else []
            content_type = "ARTICLE"
            noun = "图文"
        if not sources:
            continue
        author = str(hit.get("user", "Pixabay creator"))
        title = f"{raw_tags[0] if raw_tags else query} · Pixabay {noun}"
        description = f"来自 Pixabay 的真实{noun}素材，主题：{'、'.join(raw_tags[:8]) or query}。"
        yield ImportItem(
            provider="pixabay",
            external_id=f"{media_type}-{hit['id']}",
            content_type=content_type,
            creator_id=f"pixabay-{author}"[:128],
            title=title,
            description=description,
            body=description if content_type == "ARTICLE" else "",
            tags=raw_tags or [query],
            media_sources=sources,
            source_page_uri=str(hit.get("pageURL", "")),
            source_author=author,
            license_name="Pixabay Content License",
        )


def read_rows(path: Path) -> Iterable[dict[str, Any]]:
    if path.suffix.lower() == ".parquet":
        try:
            import pyarrow.parquet as parquet  # type: ignore[import-not-found]
        except ImportError as error:
            raise RuntimeError("reading Parquet requires: pip install pyarrow") from error
        yield from parquet.read_table(path).to_pylist()
        return
    with path.open(encoding="utf-8") as source:
        if path.suffix.lower() == ".json":
            value = json.load(source)
            yield from value if isinstance(value, list) else value.get("items", [])
            return
        for line in source:
            if line.strip():
                yield json.loads(line)


def normalize_sources(value: Any, asset_root: Path | None) -> list[str]:
    values = value if isinstance(value, list) else [value]
    result: list[str] = []
    for raw in values:
        if not raw:
            continue
        text = str(raw)
        parsed = urllib.parse.urlparse(text)
        if parsed.scheme in {"http", "https", "file"}:
            result.append(text)
        elif asset_root:
            result.append((asset_root / text).resolve().as_uri())
    return result


def qilin_items(path: Path, asset_root: Path | None, limit: int) -> Iterable[ImportItem]:
    for index, row in enumerate(read_rows(path)):
        if index >= limit:
            break
        external_id = str(row.get("note_idx") or row.get("id") or index)
        title = str(row.get("note_title") or row.get("title") or f"Qilin 图文 {external_id}")
        body = str(row.get("note_content") or row.get("content") or "")
        taxonomy = [row.get(name) for name in ("taxonomy1_id", "taxonomy2_id", "taxonomy3_id")]
        tags = [str(value) for value in taxonomy if value not in (None, "")]
        tags.extend(str(tag) for tag in row.get("tags", []) if tag)
        sources = normalize_sources(row.get("image_path") or row.get("asset_uris"), asset_root)
        if not sources:
            continue
        yield ImportItem(
            provider="qilin",
            external_id=external_id,
            content_type="ARTICLE",
            creator_id=str(row.get("creator_id") or "qilin-import")[:128],
            title=title,
            description=body[:4000],
            body=body,
            tags=tags,
            media_sources=sources,
            source_page_uri=str(row.get("source_page_uri") or "https://huggingface.co/datasets/THUIR/qilin"),
            source_author=str(row.get("author") or "THUIR Qilin dataset"),
            license_name=str(row.get("license_name") or "Verify original platform media rights before redistribution"),
        )


def manifest_items(path: Path, limit: int) -> Iterable[ImportItem]:
    for index, row in enumerate(read_rows(path)):
        if index >= limit:
            break
        content_type = str(row.get("contentType") or row.get("content_type") or "VIDEO").upper()
        sources = normalize_sources(row.get("mediaSources") or row.get("media_sources"), path.parent)
        if not sources:
            continue
        default_source_page = sources[0] if urllib.parse.urlparse(sources[0]).scheme in {"http", "https"} else ""
        yield ImportItem(
            provider=str(row.get("provider") or "manifest"),
            external_id=str(row.get("externalId") or row.get("external_id") or index),
            content_type=content_type,
            creator_id=str(row.get("creatorId") or row.get("creator_id") or "external-import")[:128],
            title=str(row.get("title") or f"External content {index}"),
            description=str(row.get("description") or ""),
            body=str(row.get("body") or ""),
            tags=[str(value) for value in row.get("tags", []) if value],
            media_sources=sources,
            source_page_uri=str(row.get("sourcePageUri") or row.get("source_page_uri") or default_source_page),
            source_author=str(row.get("sourceAuthor") or row.get("source_author") or "external creator"),
            license_name=str(row.get("licenseName") or row.get("license_name") or "verify source license"),
        )


def run_import(items: Iterable[ImportItem], args: argparse.Namespace) -> int:
    state = ImportState(Path(args.state))
    mc = None
    mc_env: dict[str, str] = {}
    public_base = ""
    if not args.dry_run:
        mc = find_mc()
        mc_env, public_base = configure_minio(mc)
    imported = skipped = failed = 0
    for item in items:
        if state.contains(item.key) and not args.force:
            skipped += 1
            print(f"SKIP {item.key}")
            continue
        try:
            if args.dry_run:
                print(json.dumps(item.__dict__, ensure_ascii=False))
                imported += 1
                continue
            assert mc is not None
            assets = upload_assets(item, mc, mc_env, public_base)
            result = register(item, assets, args.content_api)
            state.record(item.key, result)
            imported += 1
            print(f"OK   {item.key} -> {result.get('contentId')} ({result.get('status')})")
        except Exception as error:  # keep the batch resumable after one bad source
            failed += 1
            print(f"FAIL {item.key}: {error}", file=sys.stderr)
            if args.fail_fast:
                break
    print(f"imported={imported} skipped={skipped} failed={failed}")
    return 1 if failed else 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="Import external media into SeekFlux")
    root.add_argument("--content-api", default=os.getenv("SEEKFLUX_CONTENT_API_BASE", "http://127.0.0.1:8081"))
    root.add_argument("--state", default=str(STATE_DIR / "state.json"))
    root.add_argument("--dry-run", action="store_true")
    root.add_argument("--force", action="store_true")
    root.add_argument("--fail-fast", action="store_true")
    commands = root.add_subparsers(dest="source", required=True)
    pixabay = commands.add_parser("pixabay", help="download playable Pixabay videos or images")
    pixabay.add_argument("--type", choices=("video", "image"), required=True)
    pixabay.add_argument("--query", required=True)
    pixabay.add_argument("--limit", type=int, default=10)
    qilin = commands.add_parser("qilin", help="import Qilin JSON/JSONL/Parquet metadata and local images")
    qilin.add_argument("--metadata", type=Path, required=True)
    qilin.add_argument("--asset-root", type=Path)
    qilin.add_argument("--limit", type=int, default=1000)
    manifest = commands.add_parser("manifest", help="import normalized JSON/JSONL media records")
    manifest.add_argument("--file", type=Path, required=True)
    manifest.add_argument("--limit", type=int, default=1000)
    return root


def main() -> int:
    load_dotenv()
    args = parser().parse_args()
    if args.source == "pixabay":
        items = pixabay_items(args.type, args.query, args.limit)
    elif args.source == "qilin":
        items = qilin_items(args.metadata, args.asset_root, args.limit)
    else:
        items = manifest_items(args.file, args.limit)
    return run_import(items, args)


if __name__ == "__main__":
    raise SystemExit(main())
