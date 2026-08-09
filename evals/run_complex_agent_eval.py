#!/usr/bin/env python3
"""Evaluate complex-query routing, constrained retrieval and multi-turn Agent state."""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import run_direct_search_eval as direct


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "evals" / "datasets" / "complex-search-v1.json"
DEFAULT_OUTPUT = ROOT / "evals" / "results" / "complex-search-v1-baseline.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--search-url", default="http://localhost:8080/v1/search")
    parser.add_argument("--agent-url", default="http://localhost:8083/v1/agent/search")
    parser.add_argument("--content-url", default="http://localhost:8081/v1/contents")
    parser.add_argument("--agent-id", default="search-assistant")
    parser.add_argument("--k", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--keep-fixtures", action="store_true")
    parser.add_argument("--min-mrr-gain", type=float, default=0.5)
    parser.add_argument("--min-tool-selection-accuracy", type=float, default=1.0)
    return parser.parse_args()


def agent_search(
        agent_url: str,
        agent_id: str,
        session_id: str,
        turn_id: str,
        query: str,
        size: int,
        required_tags: list[str],
        patch: dict[str, Any] | None = None) -> tuple[dict[str, Any], float]:
    payload: dict[str, Any] = {
        "requestId": f"complex-eval-{uuid.uuid4().hex}",
        "sessionId": session_id,
        "turnId": turn_id,
        "agentId": agent_id,
        "mode": "AUTO",
        "query": query,
        "size": size,
        "requiredTags": required_tags,
        "options": {"allowClarification": False},
    }
    if patch is not None:
        payload["constraintPatch"] = patch
    started = time.perf_counter()
    response = direct.request_json("POST", agent_url, payload, timeout=10.0)
    return response, round((time.perf_counter() - started) * 1000, 3)


def expect_conflict(agent_url: str, payload: dict[str, Any]) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(agent_url, data=body, method="POST")
    request.add_header("Accept", "application/json")
    request.add_header("Content-Type", "application/json")
    try:
        with urlopen(request, timeout=10.0):
            raise RuntimeError("stale constraint patch unexpectedly succeeded")
    except HTTPError as error:
        detail = json.loads(error.read().decode("utf-8"))
        if error.code != 409:
            raise RuntimeError(f"stale constraint patch returned {error.code}: {detail}") from error
        return detail


def ranked_keys(response: dict[str, Any], reverse_ids: dict[str, str], field: str) -> list[str]:
    return [
        reverse_ids.get(hit.get("contentId"), f"external:{hit.get('contentId')}")
        for hit in response.get(field, [])
    ]


def percentile(values: list[float], ratio: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * ratio) - 1)
    return round(ordered[index], 3)


def validate_complex_response(
        query: dict[str, Any], response: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    if response.get("state") != "RESULTS_READY" or response.get("executionMode") != "AGENT":
        raise RuntimeError(f"query {query['id']} did not complete through the Agent")
    if response.get("routeReason") != "COMPLEX_QUERY" or response.get("goalVersion") != 1:
        raise RuntimeError(f"query {query['id']} returned an invalid route or goal version")
    plan = response.get("searchPlan") or {}
    if plan.get("derivedRequiredTags") != query["expectedTags"] or not plan.get("complex"):
        raise RuntimeError(f"query {query['id']} returned an invalid structured Search Plan: {plan}")
    trace = response.get("agentTrace") or {}
    steps = trace.get("steps") or []
    tools = trace.get("toolSchemaVersions") or {}
    if set(tools) != {"search_direct", "search_filtered"}:
        raise RuntimeError(f"query {query['id']} did not freeze the expected tool set")
    if [step.get("action") for step in steps] != ["CALL_TOOL", "CALL_TOOL", "COMPLETE"]:
        raise RuntimeError(f"query {query['id']} returned an invalid Agent step sequence")
    filtered_trace_ids = [
        step.get("linkedTraceId") for step in steps
        if step.get("toolName") == "search_filtered" and step.get("status", "").startswith("SUCCEEDED")
    ]
    search_trace = response.get("searchTrace") or {}
    if filtered_trace_ids != [search_trace.get("requestId")]:
        raise RuntimeError(f"query {query['id']} did not reuse the filtered Search candidate set")
    tool_selected = (
        response.get("selectedTool") == "search_filtered"
        and response.get("successfulToolCount") == 2
        and response.get("candidateSetReused") is True
    )
    return trace, tool_selected


def main() -> int:
    args = parse_args()
    if args.k < 1 or args.k > 50:
        raise ValueError("k must be between 1 and 50")
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    documents = dataset.get("documents", [])
    queries = dataset.get("queries", [])
    simple_queries = dataset.get("simpleQueries", [])
    isolation_tags = [str(tag) for tag in dataset.get("requiredTags", [])]
    if not documents or not queries or not simple_queries:
        raise ValueError("dataset must include documents, complex queries and simple queries")

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
                args.search_url, document, content_ids[document["key"]], isolation_tags, deadline)

        direct_metrics: list[dict[str, float]] = []
        agent_metrics: list[dict[str, float]] = []
        query_results: list[dict[str, Any]] = []
        tool_selection_successes = 0
        fallbacks = 0
        direct_latencies: list[float] = []
        agent_latencies: list[float] = []
        for query in queries:
            direct_started = time.perf_counter()
            direct_response = direct.search(args.search_url, query["text"], args.k, isolation_tags)
            direct_latency_ms = round((time.perf_counter() - direct_started) * 1000, 3)
            session_id = f"complex-query-{uuid.uuid4().hex}"
            agent_response, latency_ms = agent_search(
                args.agent_url, args.agent_id, session_id, "turn-1",
                query["text"], args.k, isolation_tags)
            trace, tool_selected = validate_complex_response(query, agent_response)
            tool_selection_successes += int(tool_selected)
            fallbacks += int(bool(agent_response.get("degraded")))
            direct_latencies.append(direct_latency_ms)
            agent_latencies.append(latency_ms)
            direct_keys = ranked_keys(direct_response, reverse_ids, "hits")
            agent_keys = ranked_keys(agent_response, reverse_ids, "items")
            direct_metric = direct.evaluate_query(query, direct_keys, args.k)
            agent_metric = direct.evaluate_query(query, agent_keys, args.k)
            direct_metrics.append(direct_metric)
            agent_metrics.append(agent_metric)
            query_results.append({
                "queryId": query["id"],
                "query": query["text"],
                "derivedRequiredTags": agent_response["searchPlan"]["derivedRequiredTags"],
                "directRankedDocumentKeys": direct_keys,
                "agentRankedDocumentKeys": agent_keys,
                "directMetrics": direct_metric,
                "agentMetrics": agent_metric,
                "selectedTool": agent_response.get("selectedTool"),
                "directLatencyMs": direct_latency_ms,
                "agentLatencyMs": latency_ms,
                "agentTrace": {
                    "agentVersion": trace["agentVersion"],
                    "plannerVersion": trace["plannerVersion"],
                    "decisionProviderVersion": trace["decisionProviderVersion"],
                    "toolSchemaVersions": trace["toolSchemaVersions"],
                    "stepActions": [step["action"] for step in trace["steps"]],
                    "terminalState": trace["terminalState"],
                },
            })

        simple_results: list[dict[str, Any]] = []
        for query in simple_queries:
            response, latency_ms = agent_search(
                args.agent_url, args.agent_id, f"simple-query-{uuid.uuid4().hex}", "turn-1",
                query["text"], args.k, isolation_tags)
            valid = (
                response.get("executionMode") == "DIRECT"
                and response.get("routeReason") == "SIMPLE_QUERY"
                and response.get("agentTrace") is None
                and response.get("goalVersion") == 0
            )
            simple_results.append({
                "queryId": query["id"],
                "executionMode": response.get("executionMode"),
                "routeReason": response.get("routeReason"),
                "agentTraceAbsent": response.get("agentTrace") is None,
                "latencyMs": latency_ms,
                "passed": valid,
            })

        multi_session = f"multi-turn-{uuid.uuid4().hex}"
        initial_query = queries[0]
        initial, _ = agent_search(
            args.agent_url, args.agent_id, multi_session, "turn-1",
            initial_query["text"], args.k, isolation_tags)
        replacement = "只看适合亲子的杭州露营，不要成人徒步"
        patched, _ = agent_search(
            args.agent_url, args.agent_id, multi_session, "turn-2", "去掉教程限制",
            args.k, isolation_tags, {
                "baseVersion": 1,
                "replacementQuery": replacement,
                "removeRequiredTags": [],
                "addRequiredTags": [],
            })
        stale_payload = {
            "requestId": f"stale-eval-{uuid.uuid4().hex}",
            "sessionId": multi_session,
            "turnId": "turn-stale",
            "agentId": args.agent_id,
            "mode": "AUTO",
            "query": "再次修改旧版本",
            "size": args.k,
            "requiredTags": isolation_tags,
            "constraintPatch": {
                "baseVersion": 1,
                "replacementQuery": replacement,
                "removeRequiredTags": [],
                "addRequiredTags": [],
            },
            "options": {"allowClarification": False},
        }
        conflict = expect_conflict(args.agent_url, stale_payload)
        multi_turn_passed = (
            initial.get("goalVersion") == 1
            and patched.get("goalVersion") == 2
            and patched.get("routeReason") == "MULTI_TURN_PATCH"
            and "教程" not in (patched.get("searchPlan") or {}).get("derivedRequiredTags", [])
            and conflict.get("code") == "AGENT_CONSTRAINT_VERSION_CONFLICT"
        )

        direct_aggregate = direct.average(direct_metrics)
        agent_aggregate = direct.average(agent_metrics)
        metric_key = f"mrr@{args.k}"
        comparison = {
            "mrrGain": round(agent_aggregate[metric_key] - direct_aggregate[metric_key], 6),
            "toolSelectionAccuracy": round(tool_selection_successes / len(queries), 6),
            "taskCompletionRate": round(sum(metric[f"recall@{args.k}"] > 0 for metric in agent_metrics) / len(queries), 6),
            "simpleDirectRate": round(sum(result["passed"] for result in simple_results) / len(simple_results), 6),
            "fallbackRate": round(fallbacks / len(queries), 6),
            "multiTurnVersionPassed": multi_turn_passed,
            "directP95Ms": percentile(direct_latencies, 0.95),
            "agentP95Ms": percentile(agent_latencies, 0.95),
            "agentAddedP95Ms": percentile([
                agent - direct_latency
                for agent, direct_latency in zip(agent_latencies, direct_latencies, strict=True)
            ], 0.95),
            "simpleDirectP95Ms": percentile(
                [result["latencyMs"] for result in simple_results], 0.95),
            "modelTokenAndCostMeasured": False,
        }
        artifact = {
            "datasetVersion": dataset["datasetVersion"],
            "evaluationVersion": "complex-search-agent-v1",
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "agentId": args.agent_id,
            "k": args.k,
            "queryCount": len(queries),
            "directAggregate": direct_aggregate,
            "agentAggregate": agent_aggregate,
            "comparison": comparison,
            "queries": query_results,
            "simpleRouting": simple_results,
            "multiTurn": {
                "initialGoalVersion": initial.get("goalVersion"),
                "patchedGoalVersion": patched.get("goalVersion"),
                "patchedDerivedRequiredTags": (patched.get("searchPlan") or {}).get("derivedRequiredTags"),
                "stalePatchErrorCode": conflict.get("code"),
                "passed": multi_turn_passed,
            },
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({
            "direct": direct_aggregate,
            "agent": agent_aggregate,
            "comparison": comparison,
        }, ensure_ascii=False, indent=2))
        if comparison["mrrGain"] < args.min_mrr_gain:
            return 2
        if comparison["toolSelectionAccuracy"] < args.min_tool_selection_accuracy:
            return 3
        if comparison["simpleDirectRate"] < 1.0 or not multi_turn_passed:
            return 4
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
        print(f"complex-agent eval failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
