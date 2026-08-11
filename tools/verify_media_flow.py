#!/usr/bin/env python3
"""Verify imported media through content, search, feed, and object HTTP APIs."""

from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from typing import Any


def json_request(url: str, *, method: str = "GET", payload: dict[str, Any] | None = None,
                 headers: dict[str, str] | None = None) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Accept": "application/json",
        **({"Content-Type": "application/json"} if data else {}),
        **(headers or {}),
    })
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def wait_published(base: str, content_id: str) -> dict[str, Any]:
    for _ in range(30):
        content = json_request(f"{base}/v1/contents/{content_id}")
        if content["status"] == "PUBLISHED":
            return content
        time.sleep(1)
    raise RuntimeError(f"content did not publish: {content_id}")


def assert_media(content: dict[str, Any]) -> None:
    request = urllib.request.Request(content["mediaUri"], method="HEAD")
    with urllib.request.urlopen(request, timeout=10) as response:
        media_type = response.headers.get_content_type()
        expected_prefix = "video/" if content["contentType"] == "VIDEO" else "image/"
        if not media_type.startswith(expected_prefix):
            raise RuntimeError(f"unexpected media type for {content['contentId']}: {media_type}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("content_ids", nargs="+")
    parser.add_argument("--content-api", default="http://127.0.0.1:8081")
    parser.add_argument("--online-api", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", default="media-e2e-user")
    parser.add_argument("--query", default="真实")
    args = parser.parse_args()

    contents = [wait_published(args.content_api, content_id) for content_id in args.content_ids]
    for content in contents:
        assert_media(content)

    json_request(
        f"{args.online_api}/v1/users/{urllib.parse.quote(args.user_id)}/interest-profile",
        method="PUT",
        payload={"topics": ["旅行", "咖啡"]},
    )
    query = urllib.parse.urlencode({"q": args.query, "page": 0, "size": 20})
    search = json_request(
        f"{args.online_api}/v1/search?{query}", headers={"X-User-Id": args.user_id})
    feed = json_request(
        f"{args.online_api}/v1/feed?size=20", headers={"X-User-Id": args.user_id})
    expected = set(args.content_ids)
    search_ids = {item["contentId"] for item in search["hits"]}
    feed_ids = {item["contentId"] for item in feed["items"]}
    if not expected.issubset(search_ids):
        raise RuntimeError(f"search missing imported ids: {sorted(expected - search_ids)}")
    if not expected.issubset(feed_ids):
        raise RuntimeError(f"feed missing imported ids: {sorted(expected - feed_ids)}")
    print(json.dumps({
        "contents": [{"contentId": item["contentId"], "contentType": item["contentType"],
                      "status": item["status"], "mediaUri": item["mediaUri"]} for item in contents],
        "searchMatched": sorted(expected & search_ids),
        "feedMatched": sorted(expected & feed_ids),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
