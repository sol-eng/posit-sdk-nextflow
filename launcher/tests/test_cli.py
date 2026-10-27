"""Unit tests for the posit-workbench-nf-launcher CLI, against a stub Workbench HTTP API."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from posit_workbench_nf_launcher import cli


class StubWorkbench:
    """Records the API calls; answers each method with its queued replies (the last repeats)."""

    def __init__(self):
        self.calls: list[dict] = []
        self.replies: dict[str, list[tuple[int, object]]] = {}
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                stub.calls.append({**body, "headers": dict(self.headers)})
                queue = stub.replies.get(body["method"]) or [(200, {"result": None})]
                status, payload = queue.pop(0) if len(queue) > 1 else queue[0]
                data = (
                    payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
                )
                self.send_response(status)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def add(self, method: str, json: object, status: int = 200):
        self.replies.setdefault(method, []).append((status, json))


@pytest.fixture(autouse=True)
def wb(monkeypatch):
    """The API-key credentials path, against the stub -- there's no session in a test run."""
    stub = StubWorkbench()
    for name in ("POSIT_PRODUCT", "RS_SERVER_ADDRESS", "PWB_SESSION_RUNTIME_DIR"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("WORKBENCH_SERVER", stub.url)
    monkeypatch.setenv("WORKBENCH_API_KEY", "test-token")
    yield stub
    stub.server.shutdown()


def test_submit_prints_job_id(wb, capsys, tmp_path):
    script = tmp_path / "run.sh"
    script.write_text("#!/bin/bash\necho hi\n")

    wb.add(
        "launch_job",
        json={"result": {"job": {"id": "42"}}},
    )

    rc = cli.main(
        [
            "submit",
            "--cluster",
            "Kubernetes",
            "--name",
            "nf-task",
            "--cpus",
            "2",
            "--mem-mb",
            "512",
            str(script),
        ]
    )

    assert rc == 0
    assert capsys.readouterr().out.strip() == "42"

    sent = wb.calls[0]
    assert sent["method"] == "launch_job"
    job = sent["kwparams"]["job"]
    assert job["cluster"] == "Kubernetes"
    assert job["exe"] == "/bin/bash"
    assert job["args"] == [str(script)]
    assert job["resourceLimits"] == [
        {"type": "cpuCount", "value": "2"},
        {"type": "memory", "value": "512"},
    ]
    assert "resourceProfile" not in job


def test_submit_passes_resource_profile(wb, tmp_path):
    script = tmp_path / "run.sh"
    script.write_text("echo hi\n")

    wb.add(
        "launch_job",
        json={"result": {"job": {"id": "43"}}},
    )

    rc = cli.main(
        [
            "submit",
            "--cluster",
            "Kubernetes",
            "--name",
            "n",
            "--resource-profile",
            "large",
            str(script),
        ]
    )

    assert rc == 0
    job = wb.calls[0]["kwparams"]["job"]
    assert job["resourceProfile"] == "large"
    assert "resourceLimits" not in job


def test_submit_with_container_reports_failure(wb, tmp_path, capsys):
    script = tmp_path / "run.sh"
    script.write_text("echo hi\n")

    wb.add(
        "launch_job",
        json={"error": {"code": 1, "message": "cluster not found"}},
    )

    rc = cli.main(
        [
            "submit",
            "--cluster",
            "bad",
            "--name",
            "n",
            "--container",
            "python:3.12-slim",
            str(script),
        ]
    )

    assert rc == 1
    assert "cluster not found" in capsys.readouterr().err


def test_status_maps_workbench_states_to_queue_status(wb, capsys):
    wb.add(
        "get_historical_session",
        json={"result": {"jobs": []}},
    )
    wb.add(
        "get_session",
        json={
            "result": {
                "jobs": [
                    {"id": "1", "status": "Pending"},
                    {"id": "2", "status": "Running"},
                    {"id": "3", "status": "Suspended"},
                    {"id": "4", "status": "Finished", "exitCode": 0},
                    {"id": "5", "status": "Finished", "exitCode": 1},
                    {"id": "6", "status": "Failed"},
                ]
            }
        },
    )

    rc = cli.main(["status"])

    assert rc == 0
    lines = sorted(capsys.readouterr().out.strip().splitlines())
    assert lines == [
        "1 PENDING",
        "2 RUNNING",
        "3 HOLD",
        "4 DONE",
        "5 ERROR",
        "6 ERROR",
    ]


def test_kill_is_best_effort_on_already_finished_jobs(wb):
    wb.add("stop_job", json={"result": None})
    wb.add(
        "stop_job",
        json={"error": {"code": 2, "message": "job already finished"}},
    )

    rc = cli.main(["kill", "1", "2"])

    assert rc == 0
    assert [c["method"] for c in wb.calls] == ["stop_job", "stop_job"]


def test_status_json_filters_by_id(wb, capsys):
    wb.add(
        "get_historical_session",
        json={"result": {"jobs": []}},
    )
    wb.add(
        "get_session",
        json={
            "result": {
                "jobs": [
                    {"id": "1", "status": "Running"},
                    {"id": "2", "status": "Finished", "exitCode": 0},
                ]
            }
        },
    )

    rc = cli.main(["status", "--json", "2"])

    assert rc == 0
    assert json.loads(capsys.readouterr().out) == {
        "2": {"status": "DONE", "workbenchStatus": "Finished"}
    }


def test_clusters_json_lists_resource_profiles(wb, capsys):
    wb.add(
        "get_compute_envs",
        json={
            "result": {
                "clusters": [
                    {"name": "Kubernetes", "resourceProfiles": [{"name": "small"}]},
                    {"name": "Local"},
                ]
            }
        },
    )

    rc = cli.main(["clusters", "--json"])

    assert rc == 0
    assert json.loads(capsys.readouterr().out) == [
        {"name": "Kubernetes", "resourceProfiles": ["small"]},
        {"name": "Local", "resourceProfiles": []},
    ]


def test_whoami_reports_the_api_key_path(wb, capsys):
    wb.add("get_compute_envs", json={"result": {"clusters": []}})
    rc = cli.main(["whoami", "--json"])

    assert rc == 0
    info = json.loads(capsys.readouterr().out)
    assert info["auth"] == "api-key"
    assert info["server"] == wb.url
    assert wb.calls[0]["headers"]["Authorization"] == "Bearer test-token"


def test_submit_sets_and_passes_through_environment(wb, tmp_path, monkeypatch):
    script = tmp_path / "driver.sh"
    script.write_text("exec nextflow run main.nf\n")
    monkeypatch.setenv("WORKBENCH_API_KEY", "test-token")
    wb.add("launch_job", json={"result": {"job": {"id": "7"}}})

    rc = cli.main(
        [
            "submit",
            "--cluster",
            "Kubernetes",
            "--name",
            "nf-driver",
            "--env",
            "NXF_OPTS=-Xmx3g -Xms512m",
            "--env",
            "WORKBENCH_API_KEY",
            "--workdir",
            str(tmp_path),
            str(script),
        ]
    )

    assert rc == 0
    job = wb.calls[0]["kwparams"]["job"]
    assert job["environment"] == [
        {"name": "NXF_OPTS", "value": "-Xmx3g -Xms512m"},
        {"name": "WORKBENCH_API_KEY", "value": "test-token"},
    ]
    assert job["workingDirectory"] == str(tmp_path)


def test_submit_rejects_an_unset_pass_through_variable(wb, tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("NOT_SET_ANYWHERE", raising=False)
    script = tmp_path / "driver.sh"
    script.write_text("true\n")

    rc = cli.main(
        ["submit", "--cluster", "K", "--name", "n", "--env", "NOT_SET_ANYWHERE", str(script)]
    )

    assert rc == 1
    assert "NOT_SET_ANYWHERE" in capsys.readouterr().err


def test_submit_without_env_sends_no_environment(wb, tmp_path):
    script = tmp_path / "run.sh"
    script.write_text("true\n")
    wb.add("launch_job", json={"result": {"job": {"id": "8"}}})

    assert cli.main(["submit", "--cluster", "K", "--name", "n", str(script)]) == 0

    job = wb.calls[0]["kwparams"]["job"]
    assert "environment" not in job
    assert "workingDirectory" not in job


@pytest.mark.parametrize(("flags", "force_quit"), [([], True), (["--graceful"], False)])
def test_kill_is_forced_unless_graceful(wb, flags, force_quit):
    wb.add("stop_job", json={"result": None})

    assert cli.main(["kill", *flags, "42"]) == 0

    sent = wb.calls[0]["kwparams"]
    assert sent == {"job_id": "42", "force_quit": force_quit}
