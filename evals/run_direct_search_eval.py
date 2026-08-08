#!/usr/bin/env python3
"""Seed versioned fixtures, call Direct Search, and write reproducible ranking metrics."""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "evals" / "datasets" / "direct-search-v1.json"
DEFAULT_OUTPUT = ROOT / "evals" / "results" / "direct-search-v1-baseline.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--search-url", default="http://localhost:8080/v1/search")
    parser.add_argument("--content-url", default="http://localhost:8081/v1/contents")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    parser.add_argument("--keep-fixtures", action="store_true")
    parser.add_argument("--require-hybrid", action="store_true")
    parser.add_argument("--min-recall", type=float, default=0.0)
    return parser.parse_args()


def request_json(method: str, url: str, payload: dict[str, Any] | None = None, timeout: float = 5.0) -> Any:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=body, method=method)
    request.add_header("Accept", "application/json")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} returned {error.code}: {detail}") from error
    except URLError as error:
        raise RuntimeError(f"{method} {url} failed: {error.reason}") from error


def seed_document(content_url: str, document: dict[str, Any]) -> str:
    response = request_json("POST", content_url, {
        "creatorId": document["creatorId"],
        "mediaUri": document["mediaUri"],
        "title": document["title"],
        "description": document["description"],
        "sourceTags": document["tags"],
    })
    content_id = str(response.get("contentId", "")).strip()
    if not content_id:
        raise RuntimeError(f"content submission did not return contentId for {document['key']}")
    return content_id


def wait_until_published(content_url: str, content_id: str, deadline: float) -> None:
    while time.monotonic() < deadline:
        response = request_json("GET", f"{content_url}/{content_id}")
        status = response.get("status")
        if status == "PUBLISHED":
            return
        if status == "WITHDRAWN":
            raise RuntimeError(f"fixture {content_id} was withdrawn before publication")
        time.sleep(0.25)
    raise TimeoutError(f"fixture {content_id} was not published before the evaluation deadline")


def search(search_url: str, query: str, size: int, required_tags: list[str]) -> dict[str, Any]:
    parameters: list[tuple[str, Any]] = [("q", query), ("page", 0), ("size", size)]
    parameters.extend(("required_tags", tag) for tag in required_tags)
    return request_json("GET", f"{search_url}?{urlencode(parameters)}")


def wait_until_indexed(
        search_url: str,
        document: dict[str, Any],
        content_id: str,
        required_tags: list[str],
        deadline: float) -> None:
    while time.monotonic() < deadline:
        response = search(search_url, document["title"], 10, required_tags)
        if any(hit.get("contentId") == content_id for hit in response.get("hits", [])):
            return
        time.sleep(0.25)
    raise TimeoutError(f"fixture {document['key']} was not searchable before the evaluation deadline")


def dcg(grades: list[int]) -> float:
    return sum((2**grade - 1) / math.log2(rank + 2) for rank, grade in enumerate(grades))


def evaluate_query(query: dict[str, Any], ranked_keys: list[str], k: int) -> dict[str, float]:
    relevance = {str(key): int(value) for key, value in query["relevance"].items()}
    top = ranked_keys[:k]
    grades = [relevance.get(key, 0) for key in top]
    relevant_total = sum(1 for grade in relevance.values() if grade > 0)
    relevant_retrieved = sum(1 for grade in grades if grade > 0)
    reciprocal_rank = next((1.0 / (rank + 1) for rank, grade in enumerate(grades) if grade > 0), 0.0)
    ideal = sorted(relevance.values(), reverse=True)[:k]
    ideal_dcg = dcg(ideal)
    return {
        f"precision@{k}": relevant_retrieved / k,
        f"recall@{k}": relevant_retrieved / relevant_total if relevant_total else 0.0,
        f"mrr@{k}": reciprocal_rank,
        f"ndcg@{k}": dcg(grades) / ideal_dcg if ideal_dcg else 0.0,
        "zeroResult": 1.0 if not ranked_keys else 0.0,
    }


def average(metrics: list[dict[str, float]]) -> dict[str, float]:
    keys = metrics[0].keys()
    return {key: round(sum(item[key] for item in metrics) / len(metrics), 6) for key in keys}


def main() -> int:
    args = parse_args()
    if args.k < 1 or args.k > 50:
        raise ValueError("k must be between 1 and 50")
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    documents = dataset.get("documents", [])
    queries = dataset.get("queries", [])
    required_tags = [str(tag) for tag in dataset.get("requiredTags", [])]
    if not documents or not queries:
        raise ValueError("dataset must include non-empty documents and queries")

    content_ids: dict[str, str] = {}
    reverse_ids: dict[str, str] = {}
    created_ids: list[str] = []
    deadline = time.monotonic() + args.timeout_seconds
    try:
        for document in documents:
            content_id = seed_document(args.content_url, document)
            content_ids[document["key"]] = content_id
            reverse_ids[content_id] = document["key"]
            created_ids.append(content_id)
        for content_id in created_ids:
            wait_until_published(args.content_url, content_id, deadline)
        for document in documents:
            wait_until_indexed(
                args.search_url,
                document,
                content_ids[document["key"]],
                required_tags,
                deadline,
            )

        query_results: list[dict[str, Any]] = []
        all_metrics: list[dict[str, float]] = []
        for query in queries:
            response = search(args.search_url, query["text"], args.k, required_tags)
            trace = response.get("trace") or {}
            if not trace.get("policyVersion") or not trace.get("indexVersion") or not trace.get("channels"):
                raise RuntimeError(f"query {query['id']} returned an incomplete Search Trace")
            if args.require_hybrid and trace.get("executionMode") != "DIRECT_HYBRID":
                raise RuntimeError(f"query {query['id']} did not execute both retrieval channels")
            ranked_keys = [reverse_ids.get(hit.get("contentId"), f"external:{hit.get('contentId')}")
                           for hit in response.get("hits", [])]
            metrics = evaluate_query(query, ranked_keys, args.k)
            all_metrics.append(metrics)
            query_results.append({
                "queryId": query["id"],
                "query": query["text"],
                "rankedDocumentKeys": ranked_keys,
                "metrics": metrics,
                "trace": {
                    "executionMode": trace["executionMode"],
                    "indexVersion": trace["indexVersion"],
                    "policyVersion": trace["policyVersion"],
                    "degraded": trace["degraded"],
                    "channels": trace["channels"],
                },
            })

        aggregate = average(all_metrics)
        artifact = {
            "datasetVersion": dataset["datasetVersion"],
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "k": args.k,
            "queryCount": len(queries),
            "aggregate": aggregate,
            "queries": query_results,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(aggregate, ensure_ascii=False, indent=2))
        recall_key = f"recall@{args.k}"
        if aggregate[recall_key] < args.min_recall:
            print(f"{recall_key} {aggregate[recall_key]} is below {args.min_recall}", file=sys.stderr)
            return 2
        return 0
    finally:
        if not args.keep_fixtures:
            for content_id in reversed(created_ids):
                try:
                    request_json("DELETE", f"{args.content_url}/{content_id}")
                except Exception as error:  # cleanup must not hide the evaluation failure
                    print(f"warning: failed to remove fixture {content_id}: {error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"direct-search eval failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
