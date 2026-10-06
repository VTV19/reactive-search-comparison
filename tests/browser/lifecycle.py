"""Container lifecycle checks, invoked by verify.sh between controlled restarts."""
import json
import os
import sys
import time
import uuid
from pathlib import Path

from http_support import call, remove_created

REPORTS = Path(os.environ.get("REPORTS_DIR", "/reports"))
TARGETS = {
    "mvc": os.environ.get("MVC_BASE_URL", "http://127.0.0.1:18081"),
    "webflux": os.environ.get("WEBFLUX_BASE_URL", "http://127.0.0.1:18082"),
}
MARKERS = REPORTS / "lifecycle-markers.json"


def prepare():
    markers = {}
    for key, base in TARGETS.items():
        token = "persist" + uuid.uuid4().hex
        status, created = call(base, "/api/documents", "POST", {
            "title": token, "text": token, "category": "JAVA", "sourceUrl": "https://example.org"})
        assert status == 201, (key, status, created)
        markers[key] = created
        status, seed = call(base, "/api/documents/d036")
        assert status == 200, (key, status, seed)
        assert call(base, f"/api/documents/d036?version={seed['version']}", "DELETE")[0] == 204
    MARKERS.write_text(json.dumps(markers), encoding="utf-8")


def persisted():
    markers = json.loads(MARKERS.read_text(encoding="utf-8"))
    for key, base in TARGETS.items():
        original = markers[key]
        status, actual = call(base, "/api/documents/" + original["id"])
        assert status == 200 and actual == original, (key, status, actual)
        assert call(base, "/api/documents/d036")[0] == 404, "Deleted seed was restored"
        status, result = call(base, "/api/search?q=" + original["title"] + "&enrich=false")
        assert status == 200 and result["total"] == 1, (key, status, result)
        remove_created(base, original["id"])
        print(f"PASS {key}: writes survive restart; deleted seed stays deleted; text index works")


def database_down():
    failures = []
    for key, base in TARGETS.items():
        try:
            status, info = call(base, "/api/info", timeout=5)
            assert status == 200 and isinstance(info, dict) and info.get("implementation") == key, (status, info)
            status, body = call(base, "/api/search?q=java&enrich=false")
            assert status == 503 and isinstance(body, dict) and body.get("code") == "DATABASE_UNAVAILABLE", (status, body)
            assert call(base, "/actuator/health/readiness")[0] == 503
            print(f"PASS {key}: database outage is reported; application info stays available")
        except (AssertionError, OSError, ValueError) as error:
            failures.append(f"{key}: {error}")
    assert not failures, "\n".join(failures)


def recovered():
    for key, base in TARGETS.items():
        status, result = call(base, "/api/search?q=java&enrich=false")
        assert status == 200 and result["total"] > 0
        assert call(base, "/actuator/health/readiness")[0] == 200
        print(f"PASS {key}: database connection recovers without restarting the application")


def metadata_down():
    for key, base in TARGETS.items():
        status, result = call(base, "/api/search?q=java&enrich=true")
        assert status == 200 and result["degraded"] is True and result["total"] > 0
        print(f"PASS {key}: search survives a stopped metadata process")


RECOVERY_TIMEOUT_SECONDS = 20
RECOVERY_POLL_SECONDS = 0.5
RECOVERY_REQUIRED_SUCCESSES = 3


def search_content(result):
    """Compare useful search data independently of optional references."""
    assert isinstance(result, dict), "Search response is not an object"
    assert type(result.get("degraded")) is bool, "Missing or invalid degraded flag"
    assert type(result.get("total")) is int and result["total"] > 0, "Empty search result"
    assert isinstance(result.get("items"), list) and result["items"], "Empty result page"
    assert all(isinstance(item, dict) for item in result["items"]), "Invalid result item"
    return {
        "query": result["query"], "total": result["total"],
        "limit": result["limit"], "offset": result["offset"],
        "items": [{key: value for key, value in item.items() if key != "reference"}
                  for item in result["items"]],
    }


def metadata_recovered():
    # Local health does not establish that an existing HTTP client has reconnected.
    metadata_base = os.environ.get("METADATA_BASE_URL", "http://127.0.0.1:19080")
    categories = "JAVA,SPRING,REACTOR,MONGODB,DOCKER,TESTING"
    status, references = call(metadata_base, "/api/metadata?categories=" + categories, timeout=5)
    assert status == 200 and isinstance(references, dict), (
        "Metadata API is not ready", status, references)
    for category in categories.split(","):
        reference = references.get(category)
        assert isinstance(reference, dict), ("Missing metadata category", category)
        assert isinstance(reference.get("title"), str) and reference["title"].strip(), category
        assert isinstance(reference.get("url"), str) and reference["url"].startswith(
            ("http://", "https://")), category

    pending = dict(TARGETS)
    failures = []
    baseline = {}
    for key, base in list(pending.items()):
        try:
            status, result = call(base, "/api/search?q=java&enrich=false", timeout=5)
            assert status == 200, ("Baseline search failed", status, result)
            baseline[key] = search_content(result)
            assert result["degraded"] is False, "Unenriched search is degraded"
        except (AssertionError, OSError, ValueError, KeyError, TypeError) as error:
            failures.append(f"{key}: invalid baseline: {error}")
            del pending[key]

    # The window is shared by both implementations; polling does not restart either one.
    started = time.monotonic()
    deadline = started + RECOVERY_TIMEOUT_SECONDS
    attempts = {key: 0 for key in pending}
    streaks = {key: 0 for key in pending}
    last = {key: "no response within recovery window" for key in pending}
    while pending and time.monotonic() < deadline:
        for key, base in list(pending.items()):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            attempts[key] += 1
            try:
                status, result = call(base, "/api/search?q=java&enrich=true",
                                      timeout=min(5, remaining))
                assert time.monotonic() < deadline, "Response arrived after recovery deadline"
                assert status == 200, ("Search failed", status, result)
                assert search_content(result) == baseline[key], "Primary search data changed"
                if result["degraded"]:
                    streaks[key] = 0
                    last[key] = "HTTP 200, degraded=true"
                    continue
                assert all(item.get("reference") == references.get(item.get("category"))
                           and item.get("reference") is not None
                           for item in result["items"]), "Missing or incorrect references"
                streaks[key] += 1
                last[key] = f"complete responses in a row: {streaks[key]}"
                if streaks[key] == RECOVERY_REQUIRED_SUCCESSES:
                    elapsed = time.monotonic() - started
                    print(f"PASS {key}: complete search recovers after metadata service restart; "
                          f"attempts={attempts[key]}, observed={elapsed:.2f}s, "
                          f"consecutive={streaks[key]}")
                    del pending[key]
            except (AssertionError, OSError, ValueError, KeyError, TypeError) as error:
                failures.append(f"{key}: recovery check failed: {error}")
                del pending[key]
        if pending:
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(RECOVERY_POLL_SECONDS, remaining))

    for key in pending:
        failures.append(f"{key}: did not recover within {RECOVERY_TIMEOUT_SECONDS}s; "
                        f"attempts={attempts[key]}; last={last[key]}")
    assert not failures, "\n".join(failures)


if __name__ == "__main__":
    phases = {"prepare": prepare, "persisted": persisted, "database-down": database_down,
              "recovered": recovered, "metadata-down": metadata_down, "metadata-recovered": metadata_recovered}
    if len(sys.argv) != 2 or sys.argv[1] not in phases:
        raise SystemExit("Usage: lifecycle.py prepare|persisted|database-down|recovered|metadata-down|metadata-recovered")
    phases[sys.argv[1]]()
    REPORTS.mkdir(parents=True, exist_ok=True)
    with (REPORTS / "lifecycle.log").open("a", encoding="utf-8") as log:
        log.write("PASS " + sys.argv[1] + "\n")
