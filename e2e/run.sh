#!/bin/bash
# Runs e2e/pipeline with the real Nextflow and the nf-workbench plugin built in plugin/build,
# against e2e/fake_workbench.py, with no launcher CLI on the PATH. Needs `make -C plugin
# assemble` first, Nextflow 25.10+ (NEXTFLOW, default `nextflow`) with Java 17+ (JAVA_HOME),
# and python3 for the fake server.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
version="$(sed -n "s/^version = '\(.*\)'/\1/p" "$repo/plugin/build.gradle")"
zip="$repo/plugin/build/distributions/nf-workbench-$version.zip"
[ -f "$zip" ] || { echo "build the plugin first: make -C plugin assemble" >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'kill "$fake_pid" 2>/dev/null || true; rm -rf "$tmp"' EXIT

# the plugin, installed the way `nextflow plugin install` would
mkdir -p "$tmp/plugins/nf-workbench-$version"
unzip -q "$zip" -d "$tmp/plugins/nf-workbench-$version"

python3 "$here/fake_workbench.py" --calls "$tmp/calls.json" >"$tmp/url" &
fake_pid=$!
for _ in $(seq 50); do [ -s "$tmp/url" ] && break; sleep 0.1; done
url="$(cat "$tmp/url")"

# no launcher CLI, and no Python environment, on the PATH Nextflow runs with
nf=(env -u PWB_SESSION_RUNTIME_DIR -u RS_SERVER_ADDRESS -u POSIT_PRODUCT -u RS_SESSION_RPC_COOKIE
  PATH="/usr/bin:/bin" NXF_PLUGINS_DIR="$tmp/plugins" NXF_OFFLINE=true
  WORKBENCH_SERVER="$url" WORKBENCH_API_KEY=fake-key
  "${NEXTFLOW:-nextflow}" -q run main.nf -plugins "nf-workbench@$version")

# 1. a run that completes
cp -r "$here/pipeline" "$tmp/run1"
(cd "$tmp/run1" && "${nf[@]}" >"$tmp/out.txt" 2>&1) || { cat "$tmp/out.txt"; tail -40 "$tmp/run1/.nextflow.log"; exit 1; }
cat "$tmp/out.txt"

# 2. a run stopped while its tasks run: Nextflow stops their jobs
cp -r "$here/pipeline" "$tmp/run2"
cd "$tmp/run2"
# a plain background command, so that $! is Nextflow itself
"${nf[@]}" --sleep 60 --marker "$tmp/started" >"$tmp/out2.txt" 2>&1 &
nf_pid=$!
cd "$here"
for _ in $(seq 300); do [ -e "$tmp/started" ] && break; sleep 0.2; done
sleep 3  # long enough for a status poll
kill -TERM "$nf_pid"; wait "$nf_pid" 2>/dev/null || true

kill -TERM "$fake_pid"; wait "$fake_pid" 2>/dev/null || true

python3 - "$tmp/calls.json" "$tmp/out.txt" <<'PY'
import json, sys
calls = json.load(open(sys.argv[1]))
out = open(sys.argv[2]).read()
jobs = [c["kwparams"]["job"] for c in calls if c["method"] == "launch_job"]
stops = [c["kwparams"] for c in calls if c["method"] == "stop_job"]
checks = {
    "pipeline finished": "done: [out_1.txt, out_2.txt]" in out,
    "task jobs launched (2 per run)": len(jobs) == 4,
    "job runs .command.run in its work dir": all(
        j["args"][0].endswith("/.command.run") and j["args"][0].startswith(j["workingDirectory"]) for j in jobs),
    "cpus and memory sent": all(
        {"type": "cpuCount", "value": "2"} in j["resourceLimits"]
        and {"type": "memory", "value": "256"} in j["resourceLimits"] for j in jobs),
    "resource profile sent": all(j.get("resourceProfile") == "large" for j in jobs),
    "status polled through the API": any(c["method"] == "get_session" for c in calls),
    "a stopped run stops its jobs": len(stops) >= 1 and all(s["force_quit"] is True for s in stops),
}
for name, ok in checks.items():
    print(("ok   " if ok else "FAIL ") + name)
sys.exit(0 if all(checks.values()) else 1)
PY
