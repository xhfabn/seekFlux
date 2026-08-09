#!/usr/bin/env python3
"""Compare Direct Search with the bounded Search Agent on versioned fixtures."""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import run_direct_search_eval as direct


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "evals" / "datasets" / "direct-search-v1.json"
DEFAULT_OUTPUT = ROOT / "evals" / "results" / "agent-search-v2-regression.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--search-url", default="http://localhost:8080/v1/search")
    parser.add_argument("--agent-url", default="http://localhost:8083/v1/agent/search")
    parser.add_argument("--content-url", default="http://localhost:8081/v1/contents")
    parser.add_argument("--agent-id", default="search-assistant")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    parser.add_argument("--keep-fixtures", action="store_true")
    parser.add_argument("--min-agent-recall", type=float, default=0.0)
    parser.add_argument("--min-top1-agreement", type=float, default=1.0)
    return parser.parse_args()


def agent_search(
        agent_url: str,
        agent_id: str,
        query: str,
        size: int,
        required_tags: list[str]) -> dict[str, Any]:
    suffix = uuid.uuid4().hex
    return direct.request_json("POST", agent_url, {
        "requestId": f"eval-request-{suffix}",
        "sessionId": f"eval-session-{suffix}",
        "turnId": "turn-1",
        "agentId": agent_id,
        "mode": "AGENT",
        "query": query,
        "size": size,
        "requiredTags": required_tags,
        "options": {"allowClarification": False},
    })


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
            content_id = direct.seed_document(args.content_url, document)
            content_ids[document["key"]] = content_id
            reverse_ids[content_id] = document["key"]
            created_ids.append(content_id)
        for content_id in created_ids:
            direct.wait_until_published(args.content_url, content_id, deadline)
        for document in documents:
            direct.wait_until_indexed(
                args.search_url,
                document,
                content_ids[document["key"]],
                required_tags,
                deadline,
            )

        query_results: list[dict[str, Any]] = []
        direct_metrics: list[dict[str, float]] = []
        agent_metrics: list[dict[str, float]] = []
        top1_matches = 0
        overlap_total = 0.0
        for query in queries:
            direct_response = direct.search(args.search_url, query["text"], args.k, required_tags)
            agent_response = agent_search(
                args.agent_url,
                args.agent_id,
                query["text"],
                args.k,
                required_tags,
            )
            if agent_response.get("state") != "RESULTS_READY":
                raise RuntimeError(f"query {query['id']} did not reach RESULTS_READY")
            if agent_response.get("executionMode") != "AGENT":
                raise RuntimeError(f"query {query['id']} unexpectedly used fallback")
            agent_trace = agent_response.get("agentTrace") or {}
            search_trace = agent_response.get("searchTrace") or {}
            steps = agent_trace.get("steps") or []
            selected_tool = agent_response.get("selectedTool") or "search_direct"
            linked = [
                step.get("linkedTraceId") for step in steps
                if step.get("toolName") == selected_tool
                and step.get("status", "").startswith("SUCCEEDED")
            ]
            if not agent_trace.get("agentVersion") or not agent_trace.get("toolSchemaVersions"):
                raise RuntimeError(f"query {query['id']} returned an incomplete Agent Trace")
            if linked != [search_trace.get("requestId")]:
                raise RuntimeError(f"query {query['id']} did not correlate Agent and Search traces")

            direct_keys = [reverse_ids.get(hit.get("contentId"), f"external:{hit.get('contentId')}")
                           for hit in direct_response.get("hits", [])]
            agent_keys = [reverse_ids.get(hit.get("contentId"), f"external:{hit.get('contentId')}")
                          for hit in agent_response.get("items", [])]
            direct_metric = direct.evaluate_query(query, direct_keys, args.k)
            agent_metric = direct.evaluate_query(query, agent_keys, args.k)
            direct_metrics.append(direct_metric)
            agent_metrics.append(agent_metric)
            if direct_keys[:1] == agent_keys[:1]:
                top1_matches += 1
            overlap_total += len(set(direct_keys[:args.k]) & set(agent_keys[:args.k])) / args.k
            query_results.append({
                "queryId": query["id"],
                "query": query["text"],
                "directRankedDocumentKeys": direct_keys,
                "agentRankedDocumentKeys": agent_keys,
                "directMetrics": direct_metric,
                "agentMetrics": agent_metric,
                "agentTrace": {
                    "agentId": agent_trace["agentId"],
                    "agentVersion": agent_trace["agentVersion"],
                    "plannerVersion": agent_trace["plannerVersion"],
                    "decisionProviderVersion": agent_trace["decisionProviderVersion"],
                    "toolSchemaVersions": agent_trace["toolSchemaVersions"],
                    "terminalState": agent_trace["terminalState"],
                    "executionMode": agent_trace["executionMode"],
                    "selectedTool": selected_tool,
                    "stepActions": [step["action"] for step in steps],
                    "searchExecutionMode": search_trace["executionMode"],
                },
            })

        direct_aggregate = direct.average(direct_metrics)
        agent_aggregate = direct.average(agent_metrics)
        comparison = {
            "top1Agreement": round(top1_matches / len(queries), 6),
            f"overlap@{args.k}": round(overlap_total / len(queries), 6),
        }
        artifact = {
            "datasetVersion": dataset["datasetVersion"],
            "evaluationVersion": "agent-search-v2-regression",
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "agentId": args.agent_id,
            "k": args.k,
            "queryCount": len(queries),
            "directAggregate": direct_aggregate,
            "agentAggregate": agent_aggregate,
            "comparison": comparison,
            "queries": query_results,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({
            "direct": direct_aggregate,
            "agent": agent_aggregate,
            "comparison": comparison,
        }, ensure_ascii=False, indent=2))
        recall_key = f"recall@{args.k}"
        if agent_aggregate[recall_key] < args.min_agent_recall:
            return 2
        if comparison["top1Agreement"] < args.min_top1_agreement:
            return 3
        return 0
    finally:
        if not args.keep_fixtures:
            for content_id in reversed(created_ids):
                try:
                    direct.request_json("DELETE", f"{args.content_url}/{content_id}")
                except Exception as error:
                    print(f"warning: failed to remove fixture {content_id}: {error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"agent-search eval failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
