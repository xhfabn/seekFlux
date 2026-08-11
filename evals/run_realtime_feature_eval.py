#!/usr/bin/env python3
"""Run the Step 9 Kafka window, online feature and Search/Feed fallback acceptance."""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

import run_direct_search_eval as direct


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "evals" / "datasets" / "realtime-features-v1.json"
DEFAULT_OUTPUT = ROOT / "evals" / "results" / "realtime-features-v1-baseline.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--content-url", default="http://localhost:8081/v1/contents")
    parser.add_argument("--online-url", default="http://localhost:8080")
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    return parser.parse_args()


def request_json(method: str, url: str, payload: dict[str, Any] | None = None,
                 headers: dict[str, str] | None = None) -> tuple[int, dict[str, Any]]:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=body, method=method)
    request.add_header("Accept", "application/json")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
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


def quote_sql(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def redis_cli(*args: str) -> str:
    binary = shutil.which("redis-cli")
    if binary is None:
        candidate = ROOT / ".runtime" / "redis-8.2.1" / "src" / "redis-cli"
        if candidate.exists():
            binary = str(candidate)
    if binary is None:
        raise RuntimeError("redis-cli is unavailable")
    command = [binary, "-h", os.environ.get("REDIS_HOST", "127.0.0.1"),
               "-p", os.environ.get("REDIS_PORT", "6379"), "--raw", *args]
    return subprocess.run(command, check=True, text=True, capture_output=True).stdout.rstrip("\n")


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


def main() -> int:
    args = parse_args()
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    user_id = f"realtime-eval-{uuid.uuid4().hex}"
    interaction_url = f"{args.online_url}/v1/interactions:batch"
    feature_url = f"{args.online_url}/v1/features/users/{quote(user_id)}/short-term-interest"
    event_ids: list[str] = []
    batch_keys: list[str] = []
    content_ids: list[str] = []
    feature_key = "seekflux:feature:short-interest:" + base64.urlsafe_b64encode(
        user_id.encode("utf-8")).decode("ascii").rstrip("=")

    def send(events: list[dict[str, Any]]) -> tuple[int, dict[str, Any]]:
        key = f"realtime-batch-{uuid.uuid4().hex}"
        batch_keys.append(key)
        return request_json("POST", interaction_url, {"events": events}, {
            "Idempotency-Key": key,
            "X-User-Id": user_id,
        })

    try:
        for document in dataset["documents"]:
            content_id = direct.seed_document(args.content_url, document)
            direct.wait_until_published(args.content_url, content_id, time.monotonic() + args.timeout_seconds)
            content_ids.append(content_id)
        camping_id, coffee_id = content_ids
        indexing_deadline = time.monotonic() + args.timeout_seconds
        for document, content_id in zip(dataset["documents"], content_ids, strict=True):
            direct.wait_until_indexed(
                f"{args.online_url}/v1/search",
                document,
                content_id,
                ["realtime-eval-v1"],
                indexing_deadline,
            )

        request_json("PUT", f"{args.online_url}/v1/users/{quote(user_id)}/interest-profile",
                     {"topics": ["露营", "咖啡"]})

        now = datetime.now(timezone.utc)
        exposure_id, save_id = str(uuid.uuid4()), str(uuid.uuid4())
        event_ids.extend([exposure_id, save_id])
        status, receipt = send([
            signal(exposure_id, "EXPOSURE", "feature-request-main", "feature-trace-main", coffee_id, now),
            signal(save_id, "SAVE", "feature-request-main", "feature-trace-main", coffee_id,
                   now + timedelta(milliseconds=1)),
        ])
        if status != 202 or receipt.get("acceptedCount") != 2:
            raise RuntimeError(f"initial feature interactions were not accepted: {status} {receipt}")

        fresh = wait_for(
            lambda: (lambda result: result if result[0] == 200 and result[1].get("status") == "FRESH"
                     and any(topic.get("topic") == "咖啡" for topic in result[1].get("topics", [])) else None)(
                         request_json("GET", feature_url)),
            args.timeout_seconds,
            "fresh coffee short-term interest")
        snapshot = fresh[1]
        coffee_heat_key = "seekflux:feature:content-heat:" + coffee_id
        wait_for(
            lambda: redis_cli("GET", coffee_heat_key) or None,
            args.timeout_seconds,
            "online coffee heat snapshot")

        feed_status, feed = request_json(
            "GET", f"{args.online_url}/v1/feed?page_size=20", headers={"X-User-Id": user_id})
        search_status, search = request_json(
            "GET", f"{args.online_url}/v1/search?q={quote('周末生活')}&size=20&required_tags=realtime-eval-v1",
            headers={"X-User-Id": user_id})

        state_before_replay = sql_scalar(
            "SELECT concat(topics::text, '|', window_end::text, '|', source_event_id::text) "
            "FROM feature.short_term_interest_snapshots WHERE user_id=" + quote_sql(user_id))
        sql_scalar(
            "UPDATE outbox.events SET status='PENDING', next_attempt_at=now(), locked_at=NULL, published_at=NULL "
            "WHERE event_id IN (" + quote_sql(exposure_id) + "::uuid," + quote_sql(save_id)
            + "::uuid)")
        wait_for(
            lambda: sql_scalar("SELECT count(*) FROM outbox.events WHERE event_id IN ("
                               + quote_sql(exposure_id) + "::uuid," + quote_sql(save_id)
                               + "::uuid) AND status='PUBLISHED'") == "2",
            args.timeout_seconds,
            "replayed interaction outbox")
        time.sleep(0.5)
        state_after_replay = sql_scalar(
            "SELECT concat(topics::text, '|', window_end::text, '|', source_event_id::text) "
            "FROM feature.short_term_interest_snapshots WHERE user_id=" + quote_sql(user_id))

        accepted_late_exposure, accepted_late_like = str(uuid.uuid4()), str(uuid.uuid4())
        event_ids.extend([accepted_late_exposure, accepted_late_like])
        send([
            signal(accepted_late_exposure, "EXPOSURE", "feature-request-ooo", "feature-trace-ooo",
                   camping_id, now - timedelta(seconds=10)),
            signal(accepted_late_like, "LIKE", "feature-request-ooo", "feature-trace-ooo",
                   camping_id, now - timedelta(seconds=9)),
        ])
        accepted_lateness = wait_for(
            lambda: sql_scalar("SELECT disposition FROM feature.realtime_events WHERE event_id="
                               + quote_sql(accepted_late_like) + "::uuid") == "APPLIED",
            args.timeout_seconds,
            "allowed out-of-order event")

        dropped_id = str(uuid.uuid4())
        event_ids.append(dropped_id)
        send([signal(dropped_id, "EXPOSURE", "feature-request-late", "feature-trace-late",
                     camping_id, now - timedelta(seconds=40))])
        dropped_lateness = wait_for(
            lambda: sql_scalar("SELECT disposition FROM feature.realtime_events WHERE event_id="
                               + quote_sql(dropped_id) + "::uuid") == "LATE_DROPPED",
            args.timeout_seconds,
            "beyond-watermark event")

        stored_json = redis_cli("GET", feature_key)
        stale_json = json.loads(stored_json)
        stale_json["computed_at"] = (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat()
        redis_cli("SET", feature_key, json.dumps(stale_json, ensure_ascii=False))
        stale_status, stale_feature = request_json("GET", feature_url)
        stale_feed_status, stale_feed = request_json(
            "GET", f"{args.online_url}/v1/feed?page_size=20", headers={"X-User-Id": user_id})

        redis_cli("SET", feature_key, "{")
        unavailable_status, unavailable_feature = request_json("GET", feature_url)
        unavailable_feed_status, unavailable_feed = request_json(
            "GET", f"{args.online_url}/v1/feed?page_size=20", headers={"X-User-Id": user_id})
        redis_cli("SET", feature_key, stored_json)

        content_heat = float(sql_scalar(
            "SELECT score FROM feature.content_heat_snapshots WHERE content_id="
            + quote_sql(coffee_id) + "::uuid"))
        comparison = {
            "versionedFreshSnapshot": snapshot.get("featureVersion") == "realtime-window-v1"
                                      and bool(snapshot.get("computedAt")),
            "shortTermTopicDerived": any(
                topic.get("topic") == "咖啡" and float(topic.get("score", 0)) > 0
                for topic in snapshot.get("topics", [])),
            "contentHeatDerived": content_heat > 0,
            "feedConsumesRealtimeFeatures": feed_status == 200
                                            and feed.get("realtimeFeatureStatus") == "FRESH"
                                            and feed.get("items", [{}])[0].get("contentId") == coffee_id,
            "searchConsumesRealtimeFeatures": search_status == 200
                                              and search.get("trace", {}).get("realtimeFeatureStatus") == "FRESH"
                                              and search.get("hits", [{}])[0].get("contentId") == coffee_id,
            "kafkaReplayStable": state_before_replay == state_after_replay,
            "allowedOutOfOrderApplied": bool(accepted_lateness),
            "beyondWatermarkDropped": bool(dropped_lateness),
            "staleSnapshotFallsBack": stale_status == 200 and stale_feature.get("status") == "STALE"
                                      and stale_feed_status == 200 and stale_feed.get("degraded") is True
                                      and bool(stale_feed.get("items")),
            "featureFailureFallsBack": unavailable_status == 200
                                       and unavailable_feature.get("status") == "UNAVAILABLE"
                                       and unavailable_feed_status == 200
                                       and unavailable_feed.get("degraded") is True
                                       and bool(unavailable_feed.get("items")),
        }
        artifact = {
            "evaluationVersion": "realtime-features-v1",
            "datasetVersion": dataset["datasetVersion"],
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "comparison": comparison,
            "evidence": {
                "userId": user_id,
                "shortTermTopics": snapshot.get("topics"),
                "featureComputedAt": snapshot.get("computedAt"),
                "coffeeContentId": coffee_id,
                "contentHeat": content_heat,
                "feedFirstContentId": feed.get("items", [{}])[0].get("contentId"),
                "searchFirstContentId": search.get("hits", [{}])[0].get("contentId"),
                "feedItems": [
                    {"contentId": item.get("contentId"), "score": item.get("score"), "reason": item.get("reason")}
                    for item in feed.get("items", [])[:3]
                ],
                "searchHits": [
                    {"contentId": item.get("contentId"), "score": item.get("score")}
                    for item in search.get("hits", [])[:3]
                ],
            },
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(comparison, ensure_ascii=False, indent=2))
        return 0 if all(comparison.values()) else 2
    finally:
        try:
            redis_cli("DEL", feature_key)
            user_interest_key = "seekflux:user-interest:" + base64.urlsafe_b64encode(
                user_id.encode("utf-8")).decode("ascii").rstrip("=")
            redis_cli("DEL", user_interest_key)
        except Exception:
            pass
        if event_ids:
            ids = ",".join(quote_sql(value) + "::uuid" for value in event_ids)
            try:
                sql_scalar("DELETE FROM feature.realtime_events WHERE event_id IN (" + ids + ")")
                sql_scalar("DELETE FROM interaction.facts WHERE event_id IN (" + ids + ")")
                sql_scalar("DELETE FROM interaction.ingress_events WHERE event_id IN (" + ids + ")")
                sql_scalar("DELETE FROM outbox.events WHERE event_id IN (" + ids + ")")
            except Exception as error:
                print(f"warning: failed to clean realtime events: {error}", file=sys.stderr)
        if batch_keys:
            try:
                sql_scalar("DELETE FROM interaction.batches WHERE idempotency_key IN ("
                           + ",".join(quote_sql(value) for value in batch_keys) + ")")
            except Exception:
                pass
        try:
            sql_scalar("DELETE FROM outbox.events WHERE aggregate_type='RealtimeFeatureSnapshot' AND (aggregate_id="
                       + quote_sql(user_id) + (" OR aggregate_id IN (" + ",".join(quote_sql(value) for value in content_ids) + ")" if content_ids else "") + ")")
            sql_scalar("DELETE FROM feature.short_term_interest_snapshots WHERE user_id=" + quote_sql(user_id))
            if content_ids:
                sql_scalar("DELETE FROM feature.content_heat_snapshots WHERE content_id IN ("
                           + ",".join(quote_sql(value) + "::uuid" for value in content_ids) + ")")
        except Exception:
            pass
        for content_id in content_ids:
            try:
                direct.request_json("DELETE", f"{args.content_url}/{content_id}")
            except Exception:
                pass


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"realtime feature eval failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
