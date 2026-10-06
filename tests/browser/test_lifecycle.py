"""Regression tests for the recovery check; no browser, Spring server or waiting."""
import copy
import io
import unittest
from contextlib import ExitStack, redirect_stdout
from unittest.mock import patch

import lifecycle


class Clock:
    def __init__(self):
        self.value = 0.0

    def monotonic(self):
        return self.value

    def sleep(self, seconds):
        self.value += seconds


class MetadataRecoveryCheckTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.sequences = {"mvc": ["full"], "webflux": ["full"]}
        self.counts = {"mvc": 0, "webflux": 0}
        self.baseline_status = {"mvc": 200, "webflux": 200}
        self.direct_status = 200
        self.direct_calls = []
        self.references = {
            category: {"title": category, "url": "https://example.org/" + category}
            for category in ("JAVA", "SPRING", "REACTOR", "MONGODB", "DOCKER", "TESTING")
        }
        self.output = io.StringIO()
        stack = self.enterContext(ExitStack())
        stack.enter_context(patch.object(lifecycle, "TARGETS", {"mvc": "mvc", "webflux": "webflux"}))
        stack.enter_context(patch.object(lifecycle, "call", side_effect=self.call))
        stack.enter_context(patch.object(lifecycle.time, "monotonic", self.clock.monotonic))
        stack.enter_context(patch.object(lifecycle.time, "sleep", self.clock.sleep))
        stack.enter_context(redirect_stdout(self.output))

    def response(self, complete=False):
        return {
            "query": "java", "total": 1, "limit": 10, "offset": 0,
            "items": [{
                "id": "d001", "title": "Java", "snippet": "Text", "category": "JAVA",
                "sourceUrl": "https://example.org", "score": 2.0,
                "reference": copy.deepcopy(self.references["JAVA"]) if complete else None,
            }],
            "degraded": not complete,
        }

    def call(self, base, path, timeout):
        self.clock.value += 0.01
        if path.startswith("/api/metadata?"):
            self.direct_calls.append(path)
            return self.direct_status, copy.deepcopy(self.references)
        if path.endswith("enrich=false"):
            result = self.response()
            result["degraded"] = False
            return self.baseline_status[base], result
        self.assertEqual("/api/search?q=java&enrich=true", path)
        self.assertGreater(timeout, 0)
        self.assertLessEqual(timeout, 5)
        self.counts[base] += 1
        sequence = self.sequences[base]
        action = sequence.pop(0) if len(sequence) > 1 else sequence[0]
        if isinstance(action, Exception):
            raise action
        result = self.response(complete=(action != "degraded"))
        if action == "http500":
            return 500, {"code": "INTERNAL_ERROR"}
        if action == "null-ref":
            result["items"][0]["reference"] = None
        if action == "wrong-ref":
            result["items"][0]["reference"]["url"] = "https://example.org/wrong"
        if action == "empty":
            result["items"] = []
        if action == "changed":
            result["total"] = 2
        if action == "invalid-flag":
            result["degraded"] = "false"
        if action == "late":
            self.clock.value += 21
        return 200, result

    def test_requires_three_complete_responses_from_both_implementations(self):
        lifecycle.metadata_recovered()
        self.assertEqual({"mvc": 3, "webflux": 3}, self.counts)
        self.assertIn("PASS mvc", self.output.getvalue())
        self.assertIn("PASS webflux", self.output.getvalue())
        self.assertEqual(1, len(self.direct_calls))
        self.assertNotIn("health", self.direct_calls[0])

    def test_allows_temporary_degradation_without_accepting_it_as_success(self):
        self.sequences["mvc"] = ["degraded", "degraded", "full"]
        lifecycle.metadata_recovered()
        self.assertEqual({"mvc": 5, "webflux": 3}, self.counts)

    def test_resets_success_streak_after_another_degraded_response(self):
        self.sequences["mvc"] = ["full", "full", "degraded", "full"]
        lifecycle.metadata_recovered()
        self.assertEqual(6, self.counts["mvc"])

    def test_persistent_degradation_fails_and_does_not_skip_webflux(self):
        self.sequences["mvc"] = ["degraded"]
        with self.assertRaisesRegex(AssertionError, "mvc: did not recover within 20s"):
            lifecycle.metadata_recovered()
        self.assertIn("PASS webflux", self.output.getvalue())
        self.assertNotIn("PASS mvc:", self.output.getvalue())
        self.assertLess(self.clock.value, 20.1)

    def test_reports_failure_for_both_implementations(self):
        self.sequences = {"mvc": ["degraded"], "webflux": ["degraded"]}
        with self.assertRaises(AssertionError) as failure:
            lifecycle.metadata_recovered()
        self.assertIn("mvc:", str(failure.exception))
        self.assertIn("webflux:", str(failure.exception))
        self.assertEqual("", self.output.getvalue())

    def test_does_not_retry_http500_as_a_transient_degraded_response(self):
        self.sequences["mvc"] = ["http500"]
        with self.assertRaisesRegex(AssertionError, "mvc: recovery check failed: .*Search failed"):
            lifecycle.metadata_recovered()
        self.assertEqual(1, self.counts["mvc"])
        self.assertIn("PASS webflux", self.output.getvalue())

    def test_complete_flag_does_not_hide_null_references(self):
        self.sequences["mvc"] = ["null-ref"]
        with self.assertRaisesRegex(AssertionError, "Missing or incorrect references"):
            lifecycle.metadata_recovered()

    def test_references_must_match_the_running_metadata_service(self):
        self.sequences["mvc"] = ["wrong-ref"]
        with self.assertRaisesRegex(AssertionError, "Missing or incorrect references"):
            lifecycle.metadata_recovered()

    def test_empty_page_cannot_pass_vacuous_all_assertion(self):
        self.sequences["mvc"] = ["empty"]
        with self.assertRaisesRegex(AssertionError, "Empty result page"):
            lifecycle.metadata_recovered()

    def test_recovery_must_not_change_primary_search_data(self):
        self.sequences["mvc"] = ["changed"]
        with self.assertRaisesRegex(AssertionError, "Primary search data changed"):
            lifecycle.metadata_recovered()

    def test_failure_of_the_metadata_api_is_not_treated_as_ready(self):
        self.direct_status = 503
        with self.assertRaisesRegex(AssertionError, "Metadata API is not ready"):
            lifecycle.metadata_recovered()
        self.assertEqual({"mvc": 0, "webflux": 0}, self.counts)

    def test_response_after_deadline_fails(self):
        self.sequences["mvc"] = ["late"]
        with self.assertRaisesRegex(AssertionError, "after recovery deadline"):
            lifecycle.metadata_recovered()
        self.assertNotIn("PASS mvc", self.output.getvalue())

    def test_network_error_in_the_search_application_is_not_silently_retried(self):
        self.sequences["mvc"] = [OSError("search connection failed")]
        with self.assertRaisesRegex(AssertionError, "search connection failed"):
            lifecycle.metadata_recovered()
        self.assertEqual(1, self.counts["mvc"])
        self.assertIn("PASS webflux", self.output.getvalue())

    def test_invalid_baseline_does_not_prevent_checking_other_application(self):
        self.baseline_status["mvc"] = 500
        with self.assertRaisesRegex(AssertionError, "mvc: invalid baseline"):
            lifecycle.metadata_recovered()
        self.assertEqual(0, self.counts["mvc"])
        self.assertIn("PASS webflux", self.output.getvalue())

    def test_string_false_does_not_count_as_a_boolean(self):
        self.sequences["mvc"] = ["invalid-flag"]
        with self.assertRaisesRegex(AssertionError, "invalid degraded flag"):
            lifecycle.metadata_recovered()


if __name__ == "__main__":
    unittest.main()
