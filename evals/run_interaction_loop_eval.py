#!/usr/bin/env python3
"""Run the Step 8 real Interaction API, Outbox, Kafka and fact replay acceptance."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import run_direct_search_eval as direct


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "evals" / "datasets" / "interaction-loop-v1.json"
DEFAULT_OUTPUT = ROOT / "evals" / "results" / "interaction-loop-v1-baseline.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--content-url", default="http://localhost:8081/v1/contents")
    parser.add_argument("--interaction-url", default="http://localhost:8080/v1/interactions:batch")
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    return parser.parse_args()


def request_json(method: str, url: str, payload: dict[str, Any], headers: dict[str, str]) -> tuple[int, dict[str, Any]]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=body, method=method)
    request.add_header("Accept", "application/json")
    request.add_header("Content-Type", "application/json")
    for name, value in headers.items():
        request.add_header(name, value)
    try:
        with urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        raw = error.read().decode("utf-8")
        return error.code, json.loads(raw) if raw else {}


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


def signal(event_id: str, event_type: str, request_id: str, trace_id: str,
           content_id: str, event_time: datetime) -> dict[str, Any]:
    return {
        "eventId": event_id,
        "eventType": event_type,
        "requestId": request_id,
        "traceId": trace_id,
        "contentId": content_id,
        "position": 1,
        "surface": "SEARCH",
        "eventTime": event_time.isoformat().replace("+00:00", "Z"),
    }


def disposition(receipt: dict[str, Any], event_id: str) -> tuple[str, str | None]:
    item = next(event for event in receipt.get("events", []) if event.get("eventId") == event_id)
    return str(item.get("disposition")), item.get("reason")


def main() -> int:
    args = parse_args()
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    document = dataset["document"]
    user_id = f"interaction-eval-{uuid.uuid4().hex}"
    request_id = f"request-{uuid.uuid4().hex}"
    trace_id = f"trace-{uuid.uuid4().hex}"
    exposure_id = str(uuid.uuid4())
    like_id = str(uuid.uuid4())
    all_event_ids: list[str] = [exposure_id, like_id]
    all_batch_keys: list[str] = []
    content_id = ""

    def send(events: list[dict[str, Any]], key: str) -> tuple[int, dict[str, Any]]:
        all_batch_keys.append(key)
        return request_json("POST", args.interaction_url, {"events": events}, {
            "Idempotency-Key": key,
            "X-User-Id": user_id,
        })

    try:
        content_id = direct.seed_document(args.content_url, document)
        direct.wait_until_published(args.content_url, content_id, time.monotonic() + args.timeout_seconds)

        now = datetime.now(timezone.utc)
        events = [
            signal(exposure_id, "EXPOSURE", request_id, trace_id, content_id, now),
            signal(like_id, "LIKE", request_id, trace_id, content_id, now + timedelta(milliseconds=1)),
        ]
        idempotency_key = f"interaction-batch-{uuid.uuid4().hex}"
        first_status, first = send(events, idempotency_key)
        replay_status, replay = send(events, idempotency_key)
        changed = json.loads(json.dumps(events))
        changed[1]["position"] = 2
        conflict_status, conflict = send(changed, idempotency_key)

        wait_for(
            lambda: int(sql_scalar(
                "SELECT count(*) FROM interaction.facts WHERE event_id IN ("
                + quote(exposure_id) + "::uuid," + quote(like_id) + "::uuid)")) == 2,
            args.timeout_seconds,
            "exposure and like facts")

        duplicate_status, duplicate = send(
            [events[1]], f"interaction-duplicate-{uuid.uuid4().hex}")

        unattributed_id = str(uuid.uuid4())
        all_event_ids.append(unattributed_id)
        unattributed_status, unattributed = send([
            signal(unattributed_id, "LIKE", f"missing-{uuid.uuid4().hex}", trace_id,
                   content_id, now + timedelta(seconds=1))
        ], f"interaction-unattributed-{uuid.uuid4().hex}")

        late_exposure_id = str(uuid.uuid4())
        early_action_id = str(uuid.uuid4())
        all_event_ids.extend([late_exposure_id, early_action_id])
        disorder_request = f"request-disorder-{uuid.uuid4().hex}"
        disorder_trace = f"trace-disorder-{uuid.uuid4().hex}"
        disorder_status, disorder = send([
            signal(early_action_id, "CLICK", disorder_request, disorder_trace,
                   content_id, now + timedelta(seconds=5)),
            signal(late_exposure_id, "EXPOSURE", disorder_request, disorder_trace,
                   content_id, now + timedelta(seconds=10)),
        ], f"interaction-disorder-{uuid.uuid4().hex}")

        attribution_row = sql_scalar(
            "SELECT concat_ws('|', user_id, request_id, trace_id, content_id::text, position::text, surface) "
            "FROM interaction.facts WHERE event_id = " + quote(like_id) + "::uuid")
        expected_attribution = "|".join([user_id, request_id, trace_id, content_id, "1", "SEARCH"])

        sql_scalar(
            "UPDATE outbox.events SET status='PENDING', next_attempt_at=now(), locked_at=NULL, "
            "published_at=NULL WHERE event_id = " + quote(like_id) + "::uuid RETURNING event_id")
        wait_for(
            lambda: sql_scalar("SELECT status FROM outbox.events WHERE event_id = "
                               + quote(like_id) + "::uuid") == "PUBLISHED",
            args.timeout_seconds,
            "Kafka outbox replay")
        facts_after_replay = int(sql_scalar(
            "SELECT count(*) FROM interaction.facts WHERE event_id IN ("
            + quote(exposure_id) + "::uuid," + quote(like_id) + "::uuid)"))

        direct.request_json("DELETE", f"{args.content_url}/{content_id}")
        withdrawn_id = str(uuid.uuid4())
        all_event_ids.append(withdrawn_id)
        withdrawn_status, withdrawn = send([
            signal(withdrawn_id, "EXPOSURE", f"withdrawn-{uuid.uuid4().hex}", trace_id,
                   content_id, now + timedelta(seconds=2))
        ], f"interaction-withdrawn-{uuid.uuid4().hex}")

        comparison = {
            "newBatchAccepted": first_status == 202 and first.get("acceptedCount") == 2,
            "sameKeyReplayStable": replay_status == 200 and replay.get("replayed") is True
                                   and replay.get("batchId") == first.get("batchId"),
            "changedBodyConflict": conflict_status == 409
                                   and conflict.get("code") == "INTERACTION_IDEMPOTENCY_CONFLICT",
            "duplicateEventNotCounted": duplicate_status == 202 and duplicate.get("duplicateCount") == 1
                                        and facts_after_replay == 2,
            "exposureActionLinked": attribution_row == expected_attribution,
            "invalidAttributionRejected": unattributed_status == 202
                                          and disposition(unattributed, unattributed_id)
                                          == ("REJECTED", "ATTRIBUTION_NOT_FOUND"),
            "outOfOrderRejected": disorder_status == 202
                                  and disposition(disorder, early_action_id)
                                  == ("REJECTED", "EVENT_BEFORE_EXPOSURE"),
            "withdrawnContentRejected": withdrawn_status == 202
                                         and disposition(withdrawn, withdrawn_id)
                                         == ("REJECTED", "CONTENT_NOT_PUBLISHED"),
            "kafkaReplayIdempotent": facts_after_replay == 2,
        }
        artifact = {
            "evaluationVersion": "interaction-loop-v1",
            "datasetVersion": dataset["datasetVersion"],
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "comparison": comparison,
            "evidence": {
                "firstBatchId": first.get("batchId"),
                "acceptedEventIds": [exposure_id, like_id],
                "attribution": attribution_row,
                "factCountAfterKafkaReplay": facts_after_replay,
            },
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(comparison, ensure_ascii=False, indent=2))
        return 0 if all(comparison.values()) else 2
    finally:
        if all_event_ids:
            ids = ",".join(quote(value) + "::uuid" for value in all_event_ids)
            try:
                sql_scalar("DELETE FROM interaction.facts WHERE event_id IN (" + ids + ")")
                sql_scalar("DELETE FROM outbox.events WHERE event_id IN (" + ids + ")")
                sql_scalar("DELETE FROM interaction.ingress_events WHERE event_id IN (" + ids + ")")
                if all_batch_keys:
                    keys = ",".join(quote(value) for value in set(all_batch_keys))
                    sql_scalar("DELETE FROM interaction.batches WHERE idempotency_key IN (" + keys + ")")
            except Exception as error:
                print(f"warning: failed to clean interaction fixtures: {error}", file=sys.stderr)
        if content_id:
            try:
                direct.request_json("DELETE", f"{args.content_url}/{content_id}")
            except Exception:
                pass


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"interaction loop eval failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
