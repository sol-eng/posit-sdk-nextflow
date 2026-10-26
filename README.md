# posit-sdk-nextflow

Run Nextflow tasks as Posit Workbench Jobs, submitted via the Workbench launcher API. Sibling
project to [`posit-sdk-snakemake`](https://github.com/sol-eng/posit-sdk-snakemake), which
does the same for Snakemake -- the two are independent (no shared code), but follow the same
auth model and job lifecycle.

Nextflow plugins are JVM (Groovy), while the Workbench API client (`posit-sdk`) is Python. Rather
than reimplementing Workbench auth/API calls in Java, this repo ships two independent pieces:

- **`launcher/`** -- `posit-workbench-nf-launcher`, a thin Python CLI wrapping
  `posit.workbench`(`.admin`) with `submit`/`status`/`kill` subcommands shaped like a grid
  scheduler's CLI (`sbatch`/`squeue`/`scancel`).
- **`plugin/`** -- `nf-workbench`, a Nextflow plugin that extends `AbstractGridExecutor` and
  shells out to that CLI, exactly the way Nextflow's built-in Slurm/LSF/SGE executors shell out
  to their own scheduler CLIs.

## Two ways to run a pipeline

Both use the exact same plugin and CLI -- the only difference is which Workbench credential path
activates, based on whatever environment the CLI subprocess inherits from Nextflow:

1. **Interactive**, from inside a Positron or RStudio Pro Workbench session: open a terminal in
   the session and run `nextflow run ...` directly. The ambient session cookie is picked up
   automatically -- no credentials to set.
2. **Headless**, via the Workbench API: submit the whole `nextflow run ...` invocation itself as
   a Workbench Job (e.g. with `posit-sdk`'s `client.jobs.launch(...)` from a laptop or CI), with
   `WORKBENCH_SERVER`/`WORKBENCH_API_KEY` set as that job's environment. The "driver" job then
   submits per-task child Workbench Jobs the same way, now using the Bearer-token client.

Both modes assume a shared filesystem between wherever Nextflow is driven from and wherever
Workbench runs jobs (Nextflow's task staging, `.command.run` wrapper script, and completion
detection via `.exitcode` all go through the task's `workDir`) -- the same assumption
`posit-sdk-snakemake` makes.

## Setup

### 1. Install the launcher CLI

Requires Python >=3.11 and [uv](https://docs.astral.sh/uv/) (or plain `pip`).

```bash
cd launcher
uv venv
uv pip install .
```

This makes `posit-workbench-nf-launcher` available on `PATH` inside the active venv. Wherever
`nextflow run` will execute (the interactive session, or the headless driver job), make sure this
venv is active or the CLI is otherwise on `PATH` -- the plugin just shells out to whatever name
`workbench.launcherCli` is set to (default: `posit-workbench-nf-launcher`).

### 2. Build and install the Nextflow plugin

Requires a JDK (the build's toolchain auto-downloads one via the Foojay resolver if needed) and
either a local Gradle install or the Gradle wrapper (`gradle wrapper` once, if you don't have
Gradle installed).

```bash
cd plugin
gradle install   # or: ./gradlew install, once you've generated the wrapper
```

This installs the plugin into `~/.nextflow/plugins/`, where Nextflow will find it via the
`plugins { id 'nf-workbench@0.3.0' }` block in `nextflow.config` (see `examples/nextflow.config`).

### 3. Configure your pipeline

```groovy
plugins {
    id 'nf-workbench@0.3.0'
}

process {
    executor = 'workbench'
}

workbench {
    cluster = 'my-cluster'   // required -- see below for how to list valid names
}
```

Find valid cluster names:
```python
from posit.workbench.admin import Client   # or: from posit.workbench import Client, if in-session
print([c["name"] for c in Client().compute_envs.list()["clusters"]])
```

See [`docs/usage.md`](docs/usage.md) for the full usage reference: all `workbench`
settings, resource profiles, process directive mapping, the example's `--workbench*` command-line params, the
launcher CLI, and troubleshooting.

## Example

A three-step pipeline (`generate -> square -> sum`) in `examples/main.nf` +
`examples/nextflow.config`, mirroring `posit-sdk-snakemake`'s own example:

```bash
cd examples
nextflow run main.nf --workbenchCluster <cluster-name>
```

This submits three Workbench Jobs and produces `sum.txt` (`55`, the sum of 1..5 squared). Two of
the three steps declare their own `container` image; the third falls back to the default
`process.container` set in `examples/nextflow.config` (override with `--workbenchContainer IMG`).
Kubernetes clusters require every job to have a container.

### RNA-seq example (parallelism)

[`examples/rnaseq/`](examples/rnaseq/README.md) is a more realistic pipeline: Salmon index,
then FastQC + Salmon quant for each of 4 samples, then MultiQC. It runs 10 Workbench Jobs, with
up to 8 at a time:

```bash
cd examples/rnaseq
nextflow run main.nf --workbenchCluster <cluster-name>
```

## Resource mapping

Only `cpus` and `memory` process directives are mapped to Workbench `resourceLimits`
(`cpuCount`/`memory`), matching `posit-sdk-snakemake`'s coverage exactly. `time`, `disk`, and
`accelerator` are not mapped in this version.

Workbench resource profiles can be selected with `workbench.resourceProfile`, or per
process with `ext.resourceProfile`. See [`docs/usage.md`](docs/usage.md#resource-profiles).

## Credentials

- **Inside a Workbench session**: nothing to set -- the CLI uses the ambient session cookie
  automatically (`posit.workbench.Client`).
- **Anywhere else**: set `WORKBENCH_SERVER` and `WORKBENCH_API_KEY` (Bearer token via
  `posit.workbench.admin.Client`).

## Testing

```bash
# CLI unit tests (mocks the Workbench HTTP API with `responses`)
cd launcher && uv pip install ".[dev]" && uv run pytest

# Plugin unit tests (Spock)
cd plugin && gradle test
```

The interactive mode has been verified end-to-end on a real Workbench cluster. `examples/rnaseq`
was launched from a Positron session through the
[Positron Nextflow extension](https://github.com/sol-eng/positron-nextflow-extension), and
the launcher CLI picked up the session's credentials (`posit-workbench-nf-launcher whoami`
reports `via session`). So have headless runs started from a Positron session, where the driver
runs as a Workbench Job and submits its tasks with the job's own credentials. Headless runs
submitted through the Workbench API with `WORKBENCH_API_KEY` have not been tested yet. Known
limitations: no wall-time/disk resource mapping, and log retrieval works only over the shared
filesystem.
