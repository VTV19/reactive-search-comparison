import json
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def call(base, path, method="GET", body=None, timeout=12):
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(base.rstrip("/") + path, data=payload, method=method)
    if payload is not None:
        request.add_header("Content-Type", "application/json")
    try:
        response = urlopen(request, timeout=timeout)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read().decode("utf-8")
        return response.status, json.loads(raw) if raw else None


def metadata_mode(mode):
    base = os.environ.get("METADATA_BASE_URL", "http://127.0.0.1:19080")
    status, _ = call(base, "/admin/mode?mode=" + mode, "POST")
    assert status == 200


def remove_created(base, identifier):
    status, value = call(base, "/api/documents/" + identifier)
    if status == 200:
        status, _ = call(base, f"/api/documents/{identifier}?version={value['version']}", "DELETE")
        assert status == 204
