# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Nextflow executor (`process.executor = 'workbench'`) that runs each task as a Posit Workbench Job. Two independently built pieces:

- `plugin/` — `nf-workbench`, a Groovy Nextflow plugin. `WorkbenchExecutor` extends `AbstractGridExecutor`, but calls the Workbench Launcher API itself (`WorkbenchApiClient`, JDK HTTP client) where the built-in Slurm executor runs `sbatch`/`squeue`/`scancel`. Since 0.4.0 it needs no CLI and no Python.
- `launcher/` — `posit-workbench-nf-launcher`, an optional Python CLI (standard library only, `workbench.py`) for submitting, listing and stopping jobs from a terminal, e.g. a headless driver job. Up to 0.3.0 the plugin shelled out to it, and it depended on a fork of `posit-sdk`.

This is a sibling of `posit-sdk-snakemake`. The API clients follow the same contract in three places: `WorkbenchApiClient.groovy` here, `launcher/.../workbench.py` (a copy of posit-sdk-snakemake's `posit_workbench_smk_launcher/workbench.py`), and that file itself. Keep them in step.

## Installation

### Prerequisites 

#### Java SDK

As a prerequisite, NextFlow needs Java 17+ & gradle, you can install this via
```bash
curl -s https://get.sdkman.io | bash
source "~/.sdkman/bin/sdkman-init.sh" 
sdk install java 17.0.10-tem
sdk install gradle
```

#### Nextflow

Install Nextflow as a normal user (no root needed; requires Java 17+, e.g. via SDKMAN: ``):
```bash
curl -s https://get.nextflow.io | bash          # downloads ./nextflow
chmod +x nextflow && mkdir -p ~/.local/bin && mv nextflow ~/.local/bin/   # ensure ~/.local/bin is on PATH
nextflow -version                               # plugin targets >= 25.10.0; pin with NXF_VER=25.10.0 if needed
```

### Launcher plugin 

Launcher (Python >=3.11, optional):
```bash
cd launcher && uv venv && uv pip install ".[dev]"
uv run pytest                                   # all tests, against a stub HTTP server
uv run pytest tests/test_cli.py::test_submit_prints_job_id   # single test
uv run ruff check . && uv run ruff format .     # line-length 99, isort rules enabled
```

Plugin (Gradle, `io.nextflow.nextflow-plugin`, targets Nextflow 25.10.0):
```bash
cd plugin
gradle test                                     # Spock tests (or `make test`)
gradle test --tests 'com.posit.nextflow.workbench.WorkbenchExecutorTest'
gradle install                                  # installs into ~/.nextflow/plugins
```
End to end, without a Workbench server: `make -C plugin assemble && e2e/run.sh` (or `make -C plugin e2e`) runs the real Nextflow with the built plugin against `e2e/fake_workbench.py`, a stand-in for the Launcher API that runs each job as a local process. It needs Nextflow 25.10+ (`NEXTFLOW`), Java 17+ (`JAVA_HOME`) and python3, for the stand-in only.
The repo has no Gradle wrapper. Run `gradle wrapper` to generate one, then use `make GRADLE=./gradlew ...`.

Example pipelines (need a real Workbench and the plugin installed). Run them from a directory on the shared filesystem (not `/tmp`), because the Workbench Jobs need to see `work/`:
```bash
cd examples && nextflow run main.nf --workbenchCluster <cluster-name>   # produces sum.txt = 55
cd examples/rnaseq && nextflow run main.nf                              # 10 jobs, up to 8 parallel; see results/pipeline_info/trace.txt
```

## Architecture

The executor is a grid executor whose submit, status and kill go through the Workbench API:

- **Submit:** `createTaskHandler` returns a `WorkbenchTaskHandler`, a `GridTaskHandler` that overrides only `processStart(ProcessBuilder, String)`: it calls `WorkbenchExecutor.submitJob`, which launches `jobSpec(task, .command.run)` with `launch_job` and returns the id. `GridTaskHandler.submit()` still writes the wrapper, retries a failed submit, records the id through `parseJobId` and sets SUBMITTED; it calls `processStart` by name, so the override is picked up. The process builder from `getSubmitCommandLine` is never started (that list only describes the submit in logs).
- **Status:** `getQueueStatus0` lists every job visible to the credential (`get_historical_session`, tolerating 401/403, plus `get_session`) and maps each with `queueStatusOf`, the one Workbench→Nextflow status mapping (the CLI's `_queue_status` is the same, for its own output). It returns null when Workbench can't be asked, as a failed `squeue` would. Completion is still detected through `.exitcode`.
- **Kill:** `killTask` calls `stop_job` with `force_quit=true` for each id, best-effort.
- `getKillCommand`, `queueStatusCommand` and `parseQueueStatus` are abstract in `AbstractGridExecutor`, but never run.

Other design points:
- **Resources** go into the job spec as structured fields, not as script header directives, so `getDirectives` is a no-op. Only `cpus` (sent only when >1) and `memory` are mapped, to `resourceLimits` `cpuCount`/`memory`. This matches the Snakemake sibling's coverage. `time`/`disk`/`accelerator` are intentionally not mapped.
- **Resource profiles:** `workbench.resourceProfile` is the default, and per-process `ext.resourceProfile` overrides it (`resourceProfileFor`). It goes into the job spec as `resourceProfile`. Confirmed live: the Launcher validates the name (`Unknown resource profile`), and jobs without one get `custom`.
- **Header script** exports `HOME`/`USER`/`LOGNAME` from the driving JVM. Workbench containers running as a non-root LDAP uid may have no passwd entry.
- **Auth** uses the environment Nextflow runs with: in a Workbench session or job, the RPC cookie (`$PWB_SESSION_RUNTIME_DIR/rpc_cookie`, then `RS_SESSION_RPC_COOKIE`) as `X-RS-Session-Server-RPC-Cookie` against `RS_SERVER_ADDRESS`; otherwise `Authorization: Bearer $WORKBENCH_API_KEY` against `WORKBENCH_SERVER`. A session without a readable cookie falls back to the API key. The cookie file is re-read for every call. The client speaks HTTP/1.1 (no h2c upgrade attempt). Tests use stub servers: `StubWorkbench.groovy` (Spock), and an `http.server` stub in `launcher/tests`.
- **A shared filesystem is assumed** between the Nextflow driver and the Workbench jobs. Task staging, `.command.run`, and completion detection through `.exitcode` all go through `task.workDir`.
- **Config:** the plugin's settings live in its own top-level `workbench` scope, declared by `WorkbenchConfig` (a `ConfigScope` extension point registered in `build.gradle`) so Nextflow's config validator knows about them. They can't go under `executor.$workbench`: core Nextflow validates that block against its fixed list of executor options, and ignores plugin scopes whose name collides with an existing scope. Generic grid-executor options (`queueSize`, `pollInterval`, ...) go in the plain `executor` scope. `executor.$workbench.*` works at runtime too, but Nextflow 25.10 warns about every `executor.$<name>.*` option, even for core executors. Run validation checks without `nextflow -q`, which hides the warnings. `workbench.cluster` is required (`register()` aborts without it). `launcherCli` is deprecated: still declared so old configs validate, ignored, with a warning when set. A new setting needs a `@ConfigOption` field in `WorkbenchConfig`. The plugin reads no `params`. The `--workbenchCluster`/`--workbenchContainer`/`--workbenchResourceProfile` flags exist only because `examples/nextflow.config` maps them into config.
- **User-facing docs:** `docs/usage.md` is the usage reference (config settings, directive mapping, example params, CLI, troubleshooting). Update it whenever you add or change a config setting, directive mapping, example param, or CLI flag.

Known gaps: no wall-time/disk mapping, and log retrieval only works over the shared filesystem. The interactive mode has been verified end-to-end on Workbench: `examples/rnaseq` was launched from a Positron session through the Positron Nextflow extension, and the session credentials were picked up (with 0.3.0, through the CLI). Headless runs started from a Positron session (the driver as a Workbench Job, using the job's own credentials) have been verified too. Headless runs submitted through the Workbench API (`WORKBENCH_SERVER`/`WORKBENCH_API_KEY`) have not been tested end-to-end yet, and 0.4.0's built-in client has been tested only against the stand-in (`e2e/`), not a live server.
