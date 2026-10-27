# Usage reference

How to use the `nf-workbench` executor in a Nextflow pipeline. It covers every setting the plugin
reads, the command-line params defined by the bundled example, and the optional
`posit-workbench-nf-launcher` CLI. See the [root README](../README.md) for installation.

## Plugin vs. example params

Nextflow passes `--someName value` on the `nextflow run` command line through as `params.someName`.
**The plugin reads no `params` itself.** It only reads `nextflow.config` settings
(the `workbench` scope and `process.*`). `--workbenchCluster`, `--workbenchContainer` and
`--workbenchResourceProfile` only work because `examples/nextflow.config` copies them into those
settings. To
get the same flags in your own pipeline, copy that pattern (see
[Wiring params into config](#wiring-params-into-config)).

## Example pipeline params

These are defined in `examples/nextflow.config`, for `nextflow run examples/main.nf`:

| Param | Default | Sets | Purpose |
|---|---|---|---|
| `--workbenchCluster NAME` | `Kubernetes` | `workbench.cluster` | Workbench cluster that jobs are submitted to. |
| `--workbenchContainer IMG` | `python:3.12-slim` | `process.container` | Default image for processes that don't declare their own `container`. Processes that set `container` keep their own image. |
| `--workbenchResourceProfile NAME` | unset (cluster default) | `workbench.resourceProfile` | Workbench resource profile for every task. See [Resource profiles](#resource-profiles). |

Example:

```bash
cd examples
nextflow run main.nf \
    --workbenchCluster Kubernetes \
    --workbenchContainer python:3.12-slim \
    --workbenchResourceProfile custom \
    -resume
```

Standard Nextflow options such as `-resume`, `-profile`, `-work-dir`, and `-with-report` work as
usual. The example config defines two empty profiles, `interactive` and `headless`. They exist for
readability only: either way, authentication is chosen from the environment (see
[Credentials](#credentials)).

## Plugin configuration (`nextflow.config`)

### Enabling the plugin and executor

```groovy
plugins {
    id 'nf-workbench@0.4.0'
}

process {
    executor = 'workbench'
}
```

You can also enable the executor for selected processes only, for example
`process { withLabel: 'big' { executor = 'workbench' } }`.

### `workbench` settings

The plugin's own settings go in a top-level `workbench` scope. The plugin declares this scope,
so Nextflow checks the option names: a typo such as `clustr` produces
`WARN: Unrecognized config option 'workbench.clustr'`.

| Setting | Required | Default | Description |
|---|---|---|---|
| `cluster` | **yes** | – | Workbench cluster name. The run stops at startup if this is unset. |
| `launcherCli` | no | – | No longer used since 0.4.0: the plugin calls the Workbench API itself. Setting it logs a warning. |
| `resourceProfile` | no | unset | Default Workbench resource profile for every task. A single process can override it with `ext.resourceProfile`. If neither is set, Workbench uses the cluster's default profile (`custom` on a stock setup). |
| `eventsFile` | no | unset | File that workflow and task events are appended to, one JSON object per line. Used by the Positron Nextflow extension to follow a run. See [Run events](#run-events). |

To list valid cluster names, use Workbench's Jobs UI, or the optional CLI:

```bash
posit-workbench-nf-launcher clusters
```

### Generic grid-executor settings

`nf-workbench` is built on Nextflow's grid-executor base class, so the standard
[executor settings](https://www.nextflow.io/docs/latest/reference/config.html#executor) also
apply. They go in the plain `executor` scope, **not** in the `workbench` scope:

```groovy
executor {
    queueSize         = 20
    queueStatInterval = '15 sec'
}
```

`executor { $workbench { queueSize = 20 } }` also works at runtime. However, Nextflow 25.10's
config validator warns `Unrecognized config option` for every `executor.$<name>.*` option,
including built-in executors like `$slurm`, so the plain `executor` scope avoids the noise. The
settings you're most likely to change:

| Setting | Effect |
|---|---|
| `queueSize` | Maximum number of Workbench jobs Nextflow keeps submitted at once. |
| `submitRateLimit` | Throttles job submission, e.g. `'10/1min'`. |
| `pollInterval` | How often Nextflow checks tasks for completion (it looks for `.exitcode` in the work dir). |
| `queueStatInterval` | How often the plugin asks Workbench for the status of its jobs. Each call lists **every** job visible to the credential, so on a busy Workbench a longer interval keeps the load down. |
| `exitReadTimeout` | How long to wait for `.exitcode` after Workbench reports a job finished. Raise this if the shared filesystem is slow to sync. |

### Process directives

| Directive | Mapped to | Notes |
|---|---|---|
| `container` | Job `container.image` | **Required on Kubernetes clusters.** Workbench rejects a job without a container (`Kubernetes requires the "container" parameter`). Set `process.container` as a default. |
| `cpus` | `resourceLimits` `cpuCount` | Sent only when greater than 1. With `cpus 1` the cluster's default applies. |
| `memory` | `resourceLimits` `memory` | Converted to MB. |
| `ext.resourceProfile` | Job `resourceProfile` | Overrides `workbench.resourceProfile` for this process. |
| `time`, `disk`, `accelerator`, `queue`, `clusterOptions` | – | Not mapped in this version, and ignored by Workbench. |

Jobs are named `nf-<process name>`, which is how they appear in the Workbench Jobs UI.

### Resource profiles

Workbench admins define resource profiles per cluster, for example in
`launcher.kubernetes.resources.conf`. To list the profiles available on a cluster:

```bash
posit-workbench-nf-launcher clusters --json    # [{"name": ..., "resourceProfiles": [...]}]
```

To select a profile for every task, or for individual processes:

```groovy
workbench {
    cluster         = 'Kubernetes'
    resourceProfile = 'small'           // default for every task
}

process {
    withLabel: 'big_mem' {
        ext.resourceProfile = 'large'       // per-process override
    }
}
```

Workbench checks the name when the job is submitted. An unknown profile fails the task with
`Unknown resource profile` (see [Troubleshooting](#troubleshooting)).

`cpus`/`memory` are still sent alongside the profile. With the `custom` profile they set the
job's limits as usual. How Workbench combines them with a non-custom profile's own limits hasn't
been tested yet: if a profile should fully determine a task's resources, leave `cpus`/`memory`
unset for that process.

### Wiring params into config

To expose command-line flags in your own pipeline, copy the pattern from `examples/nextflow.config`:

```groovy
process {
    executor  = 'workbench'
    container = params.workbenchContainer ?: 'python:3.12-slim'
}

workbench {
    cluster = params.workbenchCluster ?: 'Kubernetes'
}
```

## Run events

With `workbench.eventsFile` set, the plugin appends one JSON object per line to that file as the
run progresses. Tools such as the Positron Nextflow extension tail the file to show live status.
Events are written for any executor, not only `workbench`. To get them for a pipeline that does
not otherwise use the plugin, load it on the command line:

```bash
nextflow run main.nf -plugins nf-workbench@0.4.0 -c events.config   # events.config: workbench { eventsFile = 'events.ndjson' }
```

Every line has `event` and `utcTime`. Workflow events (`started`, `error`, `completed`) carry a
`workflow` object with `runName`, `sessionId`, `launchDir`, `workDir`, `success`, `exitStatus`,
`errorMessage`, `errorReport` and similar fields. Task events (`task_submitted`, `task_started`, `task_completed`,
`task_cached`) carry the task's `trace` record, including `task_id`, `name`, `process`, `status`,
`exit`, `native_id` (the Workbench job id) and `workdir`. The `script` and `env` fields are left
out.

## Headless runs

The Nextflow driver (`nextflow run` itself) can run as a Workbench Job too, so the pipeline keeps
running without a session. The driver then submits its tasks as Workbench Jobs, using the
credentials it was given.

Through the Workbench API, e.g. from a laptop or CI, give the driver job an API token. Its task
jobs then use the same token:

```bash
export WORKBENCH_SERVER=https://workbench.example.com WORKBENCH_API_KEY=...
cd /shared/project    # on the shared filesystem
posit-workbench-nf-launcher submit --cluster Kubernetes --name nf-driver \
    --container IMAGE --cpus 2 --mem-mb 4096 \
    --env WORKBENCH_SERVER --env WORKBENCH_API_KEY \
    --workdir . examples/headless/driver.sh
```

A bare `--env NAME` passes the variable through from your environment. The token is then part of
the job's environment, so anyone who can see the job's details can see it.

The image needs bash, `ps` and Java 17 or later. Nextflow and this plugin are found in the shared
home directory (`~/.local/bin`, `~/.nextflow/plugins`), or must be installed in the image. The
driver needs no Python: the plugin calls the Workbench API itself.

To stop a headless run, stop its driver job with `posit-workbench-nf-launcher kill --graceful ID`.
That sends SIGTERM, and Nextflow cancels its tasks before it exits. A plain `kill` sends SIGKILL,
which leaves the task jobs running.

A driver job submitted from a Positron or RStudio Pro session needs no credentials passed in.
Workbench gives every job credentials of its own (`RS_SESSION_RPC_COOKIE`, `POSIT_PRODUCT` and
`RS_SERVER_ADDRESS`). They keep working after the session that submitted the job has stopped, and
they expire 7 days after the job starts. The plugin in the driver uses them for every task job.
Do not pass a copy of the session's own cookie: it stops working when the session does.
`examples/headless/check-session-credentials.sh` checks this on a cluster. The Positron Nextflow
extension submits such a driver job for you ("As a Workbench Job" in Run Pipeline).

## Credentials

The plugin authenticates with the credentials in the environment `nextflow` runs with:

| Where `nextflow run` executes | What to set |
|---|---|
| Terminal inside a Positron / RStudio Pro Workbench session | Nothing. The session's RPC cookie (`$PWB_SESSION_RUNTIME_DIR/rpc_cookie`) is used, against `RS_SERVER_ADDRESS`. |
| A Workbench Job, e.g. a headless driver submitted from a session | Nothing. The job's own credentials (`RS_SESSION_RPC_COOKIE`, `RS_SERVER_ADDRESS`) are used. |
| Anywhere else (laptop, CI) | `WORKBENCH_SERVER` (Workbench URL) and `WORKBENCH_API_KEY` (API token). |

A server certificate from a private CA has to be in the JVM's trust store, e.g.
`NXF_OPTS=-Djavax.net.ssl.trustStore=/path/to/truststore.jks`.

## Launcher CLI reference

The plugin doesn't need the CLI. It is a terminal tool for submitting, listing and stopping jobs,
e.g. a headless driver, and for checking credentials. It uses the same credentials as the
plugin, and needs Python 3.11 or later and nothing else (`pip install ./launcher`).

```text
posit-workbench-nf-launcher submit --cluster NAME --name NAME
                                   [--cpus N] [--mem-mb N] [--container IMG]
                                   [--resource-profile NAME] [--env KEY[=VALUE] ...]
                                   [--workdir DIR] SCRIPT
posit-workbench-nf-launcher status [--json] [ID ...]
posit-workbench-nf-launcher kill [--graceful] ID [ID ...]
posit-workbench-nf-launcher clusters [--json]
posit-workbench-nf-launcher whoami [--json]
```

| Subcommand | Output | Notes |
|---|---|---|
| `submit` | The new job id on stdout | Runs `SCRIPT` with `/bin/bash`, as an absolute path on the shared filesystem. Exits 1 with `error: failed to submit job: ...` on stderr if the Workbench API rejects the job. `--env` sets the job's environment (a bare `--env KEY` passes a variable through) and `--workdir` its working directory. |
| `status` | One `<id> <STATE>` line per job | `STATE` is one of `PENDING`, `RUNNING`, `HOLD`, `DONE`, `ERROR`, `UNKNOWN`. |
| `kill` | Nothing | Best-effort. Errors such as an already-finished job are ignored. Sends SIGKILL; `--graceful` sends SIGTERM so the job can clean up. |
| `status --json` | `{"<id>": {"status": STATE, "workbenchStatus": ...}}` | Only the given ids, if any. |
| `clusters` | One cluster name per line; with `--json`, `[{"name", "resourceProfiles": [...]}]` | For cluster and profile pickers. |
| `whoami` | The user, credential path (`session` or `api-key`) and server | Checks the credentials with one API call. |

## Troubleshooting

| Error | Cause / fix |
|---|---|
| `No Workbench credentials` | Nextflow runs outside a Workbench session or job, and `WORKBENCH_SERVER`/`WORKBENCH_API_KEY` aren't set. See [Credentials](#credentials). |
| `Cannot run program "posit-workbench-nf-launcher"` | The plugin is 0.3.0 or older. 0.4.0 needs no CLI; update the plugin and the version in `plugins { id 'nf-workbench@...' }`. |
| `PKIX path building failed` | The server's certificate is from a CA the JVM doesn't trust. Add it to a trust store, see [Credentials](#credentials). |
| `Missing required config workbench.cluster` | Set `workbench.cluster`, or pass `--workbenchCluster` with the example config. |
| `Unknown resource profile` | The profile name isn't defined for that cluster. Check the names with the snippet under [Resource profiles](#resource-profiles), and check both `workbench.resourceProfile` and any `ext.resourceProfile`. |
| `Unrecognized config option 'executor.$workbench.cluster'` (or `resourceProfile`) | These settings moved to the top-level `workbench` scope. Change `executor { $workbench { cluster = ... } }` to `workbench { cluster = ... }`. |
| `Kubernetes requires the "container" parameter` | A process has no container. Set a default with `process.container` (or `--workbenchContainer` with the example config). |
| Nextflow can't find or download plugin `nf-workbench` | Unpack the release zip into `~/.nextflow/plugins/nf-workbench-<version>`, or run `gradle install` in `plugin/`, and check that the version in `plugins { id 'nf-workbench@...' }` matches `plugin/build.gradle`. |
| Task hangs after its job finished in Workbench | `.exitcode` isn't visible from the Nextflow side. Check that the work dir is on a filesystem shared with the Workbench jobs, or raise `exitReadTimeout`. |
