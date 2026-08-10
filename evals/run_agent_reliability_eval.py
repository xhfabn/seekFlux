#!/usr/bin/env python3
"""Run the Step 7 live reliability, fencing, outbox, shadow and SLO acceptance checks."""

from __future__ import annotations

import argparse
import atexit
import concurrent.futures
import json
import math
import os
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import run_direct_search_eval as direct


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "evals" / "results" / "agent-reliability-v1-baseline.json"
DEFAULT_DATASET = ROOT / "evals" / "datasets" / "direct-search-v1.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-base-url", default="http://localhost:8083/v1/agent")
    parser.add_argument("--content-url", default="http://localhost:8081/v1/contents")
    parser.add_argument("--search-url", default="http://localhost:8080/v1/search")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--requests", type=int, default=12)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    return parser.parse_args()


def request_json(method: str, url: str, payload: dict[str, Any] | None = None) -> tuple[int, dict[str, Any], float]:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=body, method=method)
    request.add_header("Accept", "application/json")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    started = time.perf_counter()
    try:
        with urlopen(request, timeout=10) as response:
            result = json.loads(response.read().decode("utf-8"))
            return response.status, result, (time.perf_counter() - started) * 1000
    except HTTPError as error:
        result = json.loads(error.read().decode("utf-8"))
        return error.code, result, (time.perf_counter() - started) * 1000


def payload(
        request_id: str,
        session_id: str,
        turn_id: str,
        required_tags: list[str],
        patch: dict[str, Any] | None = None) -> dict[str, Any]:
    value: dict[str, Any] = {
        "requestId": request_id,
        "sessionId": session_id,
        "turnId": turn_id,
        "agentId": "search-assistant",
        "mode": "AGENT",
        "query": "只看新手猫咪宠物护理知识，不要户外旅行",
        "size": 3,
        "requiredTags": required_tags,
        "options": {"allowClarification": False},
    }
    if patch is not None:
        value["constraintPatch"] = patch
    return value


def sql_scalar(sql: str) -> str:
    environment = os.environ.copy()
    environment.setdefault("PGPASSWORD", environment.get("POSTGRES_PASSWORD", "seekflux_local"))
    command = [
        "psql", "-X", "-A", "-t", "-q", "-v", "ON_ERROR_STOP=1",
        "-h", environment.get("POSTGRES_HOST", "127.0.0.1"),
        "-p", environment.get("POSTGRES_PORT", "5432"),
        "-U", environment.get("POSTGRES_USER", "seekflux"),
        "-d", environment.get("POSTGRES_DB", "seekflux"),
        "-c", sql,
    ]
    return subprocess.run(command, check=True, text=True, capture_output=True, env=environment).stdout.strip()


def quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def wait_for(predicate, timeout: float, description: str) -> Any:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.2)
    raise RuntimeError(f"timed out waiting for {description}")


def percentile(values: list[float], ratio: float) -> float:
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * ratio) - 1)], 3)


def main() -> int:
    args = parse_args()
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    documents = dataset.get("documents", [])
    required_tags = [str(tag) for tag in dataset.get("requiredTags", [])]
    if not documents or not required_tags:
        raise ValueError("dataset must include documents and isolation requiredTags")

    created_ids: list[str] = []

    def cleanup_fixtures() -> None:
        for content_id in reversed(created_ids):
            try:
                direct.request_json("DELETE", f"{args.content_url}/{content_id}")
            except Exception as error:
                print(f"warning: failed to remove reliability fixture {content_id}: {error}",
                      file=__import__("sys").stderr)

    atexit.register(cleanup_fixtures)
    deadline = time.monotonic() + args.timeout_seconds
    for document in documents:
        created_ids.append(direct.seed_document(args.content_url, document))
    for content_id in created_ids:
        direct.wait_until_published(args.content_url, content_id, deadline)
    for document, content_id in zip(documents, created_ids, strict=True):
        direct.wait_until_indexed(args.search_url, document, content_id, required_tags, deadline)

    shadow_url = f"{args.agent_base_url}/runtime/shadow"
    search_url = f"{args.agent_base_url}/search"
    request_json("PUT", shadow_url, {"enabled": True, "sampleRate": 1.0})

    duplicate_request = f"reliability-duplicate-{uuid.uuid4().hex}"
    duplicate_session = f"reliability-shadow-{uuid.uuid4().hex}"
    first_status, first, first_latency = request_json(
        "POST", search_url, payload(duplicate_request, duplicate_session, "turn-1", required_tags))
    duplicate_status, duplicate, _ = request_json(
        "POST", search_url, payload(duplicate_request, duplicate_session, "turn-1", required_tags))
    if first_status != 200 or duplicate_status != 409 or duplicate.get("code") != "DUPLICATE_AGENT_REQUEST":
        raise RuntimeError("duplicate request idempotency acceptance failed")

    shadow_count = wait_for(
        lambda: int(sql_scalar(
            "SELECT count(*) FROM agent.shadow_evaluations WHERE request_id = " + quote(duplicate_request))),
        args.timeout_seconds,
        "shadow evaluation")
    run_count = int(sql_scalar(
        "SELECT count(*) FROM agent.runs WHERE request_id = " + quote(duplicate_request)))
    tool_event_count = int(sql_scalar(
        "SELECT count(*) FROM agent.run_events event JOIN agent.runs run USING (agent_run_id) "
        "WHERE run.request_id = " + quote(duplicate_request) + " AND event.event_type = 'TOOL_COMPLETED'"))
    outbox_count = int(sql_scalar(
        "SELECT count(*) FROM outbox.events WHERE aggregate_id = " + quote(duplicate_session)
        + " AND event_type LIKE 'agent.run.%'"))
    audit_count = wait_for(
        lambda: int(sql_scalar(
            "SELECT count(*) FROM agent.audit_events WHERE session_id = " + quote(duplicate_session))),
        args.timeout_seconds,
        "idempotent Agent audit consumer")

    request_json("PUT", shadow_url, {"enabled": False, "sampleRate": 0.0})
    disabled_request = f"reliability-shadow-off-{uuid.uuid4().hex}"
    disabled_session = f"reliability-shadow-off-{uuid.uuid4().hex}"
    disabled_status, disabled_result, _ = request_json(
        "POST", search_url, payload(disabled_request, disabled_session, "turn-1", required_tags))
    time.sleep(0.5)
    disabled_shadow_count = int(sql_scalar(
        "SELECT count(*) FROM agent.shadow_evaluations WHERE request_id = " + quote(disabled_request)))

    takeover_session = f"reliability-takeover-{uuid.uuid4().hex}"
    takeover_request = f"reliability-takeover-{uuid.uuid4().hex}"
    initial_status, initial, _ = request_json(
        "POST", search_url, payload(takeover_request, takeover_session, "turn-1", required_tags))
    first_token = int(sql_scalar(
        "SELECT active_fencing_token FROM agent.sessions WHERE session_id = " + quote(takeover_session)))
    concurrent_payloads = [
        payload(
            f"reliability-race-{uuid.uuid4().hex}", takeover_session, f"turn-{index}",
            required_tags,
            {"baseVersion": 1, "replacementQuery": query, "addRequiredTags": [], "removeRequiredTags": []})
        for index, query in enumerate([
            "只看杭州亲子露营，不要成人徒步",
            "只看猫咪护理教程，不要户外旅行",
        ], start=2)
    ]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        race = list(executor.map(lambda body: request_json("POST", search_url, body), concurrent_payloads))
    successes = [item for item in race if item[0] == 200]
    conflicts = [item for item in race if item[0] == 409]
    second_token = int(sql_scalar(
        "SELECT active_fencing_token FROM agent.sessions WHERE session_id = " + quote(takeover_session)))
    state_version = int(sql_scalar(
        "SELECT state_version FROM agent.sessions WHERE session_id = " + quote(takeover_session)))

    latencies = [first_latency]
    availability_successes = 1
    traces: list[dict[str, Any]] = [first.get("agentTrace") or {}]
    for index in range(max(1, args.requests - 1)):
        status, response, latency = request_json(
            "POST", search_url,
            payload(f"reliability-slo-{uuid.uuid4().hex}", f"reliability-slo-{uuid.uuid4().hex}",
                    f"turn-{index}", required_tags))
        latencies.append(latency)
        availability_successes += int(status == 200)
        traces.append(response.get("agentTrace") or {})

    usage_measured = all(trace.get("usageMeasured") is True for trace in traces)
    comparison = {
        "singleWriterPassed": initial_status == 200 and len(successes) == 1 and len(conflicts) == 1 and state_version == 2,
        "fencingMonotonicPassed": second_token > first_token > 0,
        "duplicateSideEffectFreePassed": run_count == 1 and tool_event_count == 2,
        "transactionalOutboxPassed": outbox_count == 1,
        "idempotentAuditConsumerPassed": audit_count == 1,
        "shadowPrimaryUnchangedPassed": first.get("state") == "RESULTS_READY" and shadow_count >= 1,
        "shadowFastDisablePassed": disabled_status == 200 and disabled_result.get("state") == "RESULTS_READY" and disabled_shadow_count == 0,
        "availability": round(availability_successes / len(latencies), 6),
        "p95Ms": percentile(latencies, 0.95),
        "fallbackRate": round(sum(bool(trace.get("fallbackReason")) for trace in traces) / len(traces), 6),
        "providerUsageMeasured": usage_measured,
        "inputTokens": sum(int(trace.get("inputTokens", 0)) for trace in traces),
        "outputTokens": sum(int(trace.get("outputTokens", 0)) for trace in traces),
        "costMicros": sum(int(trace.get("costMicros", 0)) for trace in traces),
    }
    artifact = {
        "evaluationVersion": "agent-reliability-v1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "requestCount": len(latencies),
        "providerVersion": traces[0].get("decisionProviderVersion"),
        "promptVersion": traces[0].get("promptVersion"),
        "toolSchemaVersions": traces[0].get("toolSchemaVersions"),
        "comparison": comparison,
        "faultInjectionEvidence": {
            "model": "AgentRuntimeTest.modelFaultInjectionProducesDeterministicFallback",
            "tool": "AgentRuntimeTest.toolFaultInjectionReturnsPartialFailureAsStableFallback",
            "authorityLoss": "SessionExecutorReliabilityTest.staleOwnerCannotAppendOutcomeAfterLosingAuthority",
            "crossInstanceCancel": "SessionExecutorReliabilityTest.cancellationWrittenByAnotherInstanceStopsTheRunningLoop",
            "bulkhead": "AgentCallGuardTest.rejectsASecondModelCallWhenTheBulkheadIsFull",
        },
        "providerBoundary": (
            "The configured deterministic provider does not report tokens or paid cost. "
            "OpenAI-compatible usage accounting is protocol-tested; set a real endpoint and prices to populate it."
            if not usage_measured else "Provider usage and configured price accounting were measured."
        ),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(comparison, ensure_ascii=False, indent=2))
    required = [
        comparison["singleWriterPassed"],
        comparison["fencingMonotonicPassed"],
        comparison["duplicateSideEffectFreePassed"],
        comparison["transactionalOutboxPassed"],
        comparison["idempotentAuditConsumerPassed"],
        comparison["shadowPrimaryUnchangedPassed"],
        comparison["shadowFastDisablePassed"],
        comparison["availability"] == 1.0,
    ]
    return 0 if all(required) else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"agent reliability eval failed: {error}", file=__import__("sys").stderr)
        raise SystemExit(1) from error
