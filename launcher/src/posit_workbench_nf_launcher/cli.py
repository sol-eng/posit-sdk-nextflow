"""CLI that submits, lists and stops Posit Workbench Jobs from the command line.

The nf-workbench Nextflow plugin no longer needs it: since 0.4.0 the plugin calls the Workbench
API itself. The CLI remains for the terminal, for scripts, and for submitting and stopping a
headless Nextflow driver job (see docs/usage.md, "Headless runs"). It uses a built-in API client
(``workbench.py``, standard library only), the same as the sibling Snakemake integration
(posit-sdk-snakemake).

- `submit`: prints the new job's id to stdout, like `sbatch`. `--env`/`--workdir` are for driver
  jobs.
- `status`: prints one `<id> <STATE>` line per known job, like `squeue`; `--json [ids]` for
  tools.
- `kill <id> [<id> ...]`: best-effort, like `scancel`; `--graceful` sends SIGTERM.
- `clusters`, `whoami`: cluster pickers and diagnostics, e.g. for the Positron Nextflow extension.

`STATE` values are Nextflow's own `AbstractGridExecutor.QueueStatus` enum names (PENDING,
RUNNING, HOLD, DONE, ERROR, UNKNOWN), with the same mapping as the plugin's
`WorkbenchExecutor.queueStatusOf`.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from .workbench import (
    TERMINAL_FAILURE_STATUSES,
    WorkbenchError,
    launch_job,
    make_client,
    resource_limits,
    whoami,
)


def _environment(entries: list[str] | None) -> list[dict[str, str]] | None:
    """`KEY=VALUE` sets a variable; a bare `KEY` passes it through from this process."""
    env = []
    for entry in entries or []:
        name, sep, value = entry.partition("=")
        if not sep:
            if name not in os.environ:
                raise ValueError(f"--env {name}: not set in this environment")
            value = os.environ[name]
        env.append({"name": name, "value": value})
    return env or None


def cmd_submit(args: argparse.Namespace) -> int:
    try:
        environment = _environment(args.env)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    try:
        job = launch_job(
            make_client(),
            cluster=args.cluster,
            name=args.name,
            exe="/bin/bash",
            args=[os.path.abspath(args.script)],
            working_directory=os.path.abspath(args.workdir) if args.workdir else None,
            environment=environment,
            container={"image": args.container} if args.container else None,
            resource_limits=resource_limits(args.cpus, args.mem_mb),
            resource_profile=args.resource_profile,
        )
    except WorkbenchError as e:
        print(f"error: failed to submit job: {e}", file=sys.stderr)
        return 1
    print(job["id"])
    return 0


def _queue_status(job: dict) -> str:
    status = job.get("status")
    if status == "Finished":
        return "DONE" if job.get("exitCode") in (0, None) else "ERROR"
    if status in TERMINAL_FAILURE_STATUSES:
        return "ERROR"
    if status == "Running":
        return "RUNNING"
    if status == "Suspended":
        return "HOLD"
    if status == "Pending":
        return "PENDING"
    return "UNKNOWN"


def cmd_status(args: argparse.Namespace) -> int:
    client = make_client()
    jobs = client.jobs.get_status_map()
    if args.ids:
        jobs = {job_id: job for job_id, job in jobs.items() if job_id in args.ids}
    if args.json:
        statuses = {
            job_id: {"status": _queue_status(job), "workbenchStatus": job.get("status")}
            for job_id, job in jobs.items()
        }
        print(json.dumps(statuses))
        return 0
    for job_id, job in jobs.items():
        print(f"{job_id} {_queue_status(job)}")
    return 0


def cmd_clusters(args: argparse.Namespace) -> int:
    client = make_client()
    clusters = [
        {
            "name": c["name"],
            "resourceProfiles": [p["name"] for p in c.get("resourceProfiles") or []],
        }
        for c in client.compute_envs.list().get("clusters") or []
    ]
    if args.json:
        print(json.dumps(clusters))
    else:
        for c in clusters:
            print(c["name"])
    return 0


def cmd_whoami(args: argparse.Namespace) -> int:
    """Report which credentials are used, checked with one API call."""
    try:
        info = whoami()
    except WorkbenchError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(info))
    else:
        print(f"{info['user']} via {info['auth']} on {info['server']}")
    return 0


def cmd_kill(args: argparse.Namespace) -> int:
    client = make_client()
    for job_id in args.ids:
        try:
            # a graceful stop sends SIGTERM, so e.g. a Nextflow driver can cancel its tasks
            client.jobs.stop(job_id, force_quit=not args.graceful)
        except WorkbenchError:
            pass  # best-effort cancellation, e.g. the job may have already finished
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="posit-workbench-nf-launcher")
    sub = parser.add_subparsers(dest="command", required=True)

    submit = sub.add_parser("submit", help="Launch a Workbench job running SCRIPT via /bin/bash")
    submit.add_argument("--cluster", required=True, help="Workbench compute env/cluster name")
    submit.add_argument("--name", required=True, help="Job display name")
    submit.add_argument("--cpus", type=int, default=None)
    submit.add_argument("--mem-mb", type=int, default=None)
    submit.add_argument("--container", default=None, help="Container image reference")
    submit.add_argument(
        "--resource-profile",
        default=None,
        help="Workbench resource profile name (as configured for the cluster)",
    )
    submit.add_argument(
        "--env",
        action="append",
        metavar="KEY[=VALUE]",
        help="Set an environment variable for the job; a bare KEY passes it through (repeatable)",
    )
    submit.add_argument("--workdir", default=None, help="Working directory of the job")
    submit.add_argument("script", help="Path to the script to run")
    submit.set_defaults(func=cmd_submit)

    status = sub.add_parser("status", help="Print `<id> <STATE>` for every known job")
    status.add_argument("ids", nargs="*", help="Only report these job ids")
    status.add_argument("--json", action="store_true", help="Print a JSON object keyed by job id")
    status.set_defaults(func=cmd_status)

    clusters = sub.add_parser("clusters", help="List cluster names and their resource profiles")
    clusters.add_argument("--json", action="store_true")
    clusters.set_defaults(func=cmd_clusters)

    whoami = sub.add_parser("whoami", help="Show the active Workbench credential path and user")
    whoami.add_argument("--json", action="store_true")
    whoami.set_defaults(func=cmd_whoami)

    kill = sub.add_parser("kill", help="Stop one or more jobs by id (best-effort)")
    kill.add_argument("ids", nargs="+")
    kill.add_argument(
        "--graceful",
        action="store_true",
        help="Send SIGTERM instead of SIGKILL, so the job can clean up",
    )
    kill.set_defaults(func=cmd_kill)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except WorkbenchError as e:
        # e.g. no session cookie and no WORKBENCH_SERVER/WORKBENCH_API_KEY
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
