# Changelog

All notable changes to `nf-workbench` (the Nextflow plugin) and `posit-workbench-nf-launcher`
(the launcher CLI) are documented here. The two are versioned together.

## 0.4.0 - unreleased

### Changed

- The plugin calls the Workbench Launcher API itself (`WorkbenchApiClient`, with the JDK's HTTP client) instead of shelling out to `posit-workbench-nf-launcher`. Nextflow needs no Python and no CLI to run tasks as Workbench Jobs, in a session, in a headless driver job, or anywhere with `WORKBENCH_SERVER`/`WORKBENCH_API_KEY`. Submit, status and kill keep their behaviour; the status mapping moved from the CLI into `WorkbenchExecutor.queueStatusOf`.
- `workbench.launcherCli` is no longer used. It still validates, and logs a warning when set. The examples no longer set it, and have no `--launcherCli` param.
- The launcher CLI is optional, for submitting, listing and stopping jobs from a terminal (e.g. a headless driver). It no longer depends on posit-sdk: it uses a built-in client (standard library only), the same as posit-sdk-snakemake's. `whoami` now checks the credentials with one API call.
- A Workbench session without a readable RPC cookie falls back to `WORKBENCH_SERVER`/`WORKBENCH_API_KEY`, where posit-sdk failed.
- The plugin and launcher are now 0.4.0. Update `plugins { id 'nf-workbench@0.4.0' }` in pipeline configs.

### Added

- `e2e/run.sh`: the real Nextflow with the built plugin against `e2e/fake_workbench.py`, a stand-in for the Launcher API that runs each job as a local process. It checks a completed run (the job spec: script, work dir, cpus, memory, resource profile) and a run stopped while its tasks run (status polled, jobs stopped).
- CI: plugin tests and build, the end-to-end run, and the launcher tests.

## 0.3.0 - 2026-10-23

### Added

- Launcher: `submit --env KEY[=VALUE]` (repeatable; a bare `KEY` passes the variable through) and `--workdir`, to submit a headless Nextflow driver as a Workbench Job.
- Launcher: `kill --graceful` stops a job with SIGTERM instead of SIGKILL, so a Nextflow driver can cancel its tasks.
- `examples/headless/`: a driver script for headless runs, and a script that checks that a job's own Workbench credentials keep working after the session that submitted it has stopped.
- Docs: "Headless runs" in `docs/usage.md`.

### Changed

- Docs: verified end-to-end on Posit Workbench (Kubernetes): the interactive mode, with the session's credentials reaching the launcher CLI, and headless runs started from a Positron session, whose driver job submits its tasks with credentials of its own. Headless runs submitted through the Workbench API with `WORKBENCH_API_KEY` are not tested yet.
- The plugin and launcher are now 0.3.0. Update `plugins { id 'nf-workbench@0.3.0' }` in pipeline configs.

## 0.2.0 - 2026-10-21

### Added

- Run events: with `workbench.eventsFile` set, the plugin appends workflow and task events to that
  file as JSON lines (`started`, `task_submitted`, `task_started`, `task_completed`,
  `task_cached`, `error`, `completed`). Workflow events include Nextflow's `errorReport`. This
  works for any executor when the plugin is loaded, e.g. with `-plugins nf-workbench@0.2.0`. It is
  used by the Positron Nextflow extension to show live run status. See
  [Run events](docs/usage.md#run-events).
- Launcher: `clusters [--json]` lists cluster names and their resource profiles.
- Launcher: `whoami [--json]` shows the active credential path (`session` or `api-key`), the
  user and the server.
- Launcher: `status` accepts job ids to filter by, and `--json` for structured output.

### Changed

- The plugin and launcher versions are now 0.2.0. Update `plugins { id 'nf-workbench@0.2.0' }`
  in pipeline configs.

The plugin's `submit`/`status`/`kill` contract with the launcher is unchanged.

## 0.1.0 - 2026-10-07

### Added

- `nf-workbench` executor (`process.executor = 'workbench'`), which runs each task as a Posit
  Workbench Job through the `posit-workbench-nf-launcher` CLI (`submit`, `status`, `kill`).
- `cpus` and `memory` mapped to Workbench resource limits, and `container` to the job image.
- Resource profiles: `workbench.resourceProfile`, overridden per process with
  `ext.resourceProfile`.
- The top-level `workbench` config scope (`cluster`, `launcherCli`, `resourceProfile`), declared
  so that Nextflow's config validator recognises it.
- Examples: a three-step pipeline and an RNA-seq pipeline with parallel Workbench Jobs.
