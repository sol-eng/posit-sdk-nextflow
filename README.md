# posit-sdk-nextflow

Run Nextflow tasks as Posit Workbench Jobs, submitted via the Workbench launcher API. Sibling
project to [`posit-sdk-snakemake`](https://github.com/sol-eng/posit-sdk-snakemake), which
does the same for Snakemake -- the two are independent (no shared code), but follow the same
auth model and job lifecycle.

This repo ships two pieces:

- **`plugin/`** -- `nf-workbench`, a Nextflow plugin with a `workbench` executor. It is a grid
  executor like Nextflow's Slurm/LSF/SGE executors, except that it calls the Workbench Launcher
  API itself (with the JDK's HTTP client) where those run `sbatch`/`squeue`/`scancel`. It needs
  nothing else: no Python, no CLI.
- **`launcher/`** -- `posit-workbench-nf-launcher`, an optional Python CLI (standard library only)
  for submitting, listing and stopping Workbench Jobs from a terminal or script, e.g. a headless
  Nextflow driver job. The plugin used it up to 0.3.0.

## Two ways to run a pipeline

Both use the same plugin -- the only difference is which Workbench credentials it finds in the
environment Nextflow runs with:

1. **Interactive**, from inside a Positron or RStudio Pro Workbench session: open a terminal in
   the session and run `nextflow run ...` directly. The ambient session cookie is picked up
   automatically -- no credentials to set.
2. **Headless**, via the Workbench API: submit the whole `nextflow run ...` invocation itself as
   a Workbench Job (e.g. with `posit-workbench-nf-launcher submit` from a laptop or CI, or the
   Positron Nextflow extension). The "driver" job then submits per-task child Workbench Jobs
   with the job's own credentials, or with `WORKBENCH_SERVER`/`WORKBENCH_API_KEY` set as that
   job's environment.

Both modes assume a shared filesystem between wherever Nextflow is driven from and wherever
Workbench runs jobs (Nextflow's task staging, `.command.run` wrapper script, and completion
detection via `.exitcode` all go through the task's `workDir`) -- the same assumption
`posit-sdk-snakemake` makes.

## Setup

### 1. Install the Nextflow plugin

Download `nf-workbench-<version>.zip` from the
[releases](https://github.com/sol-eng/posit-sdk-nextflow/releases) and unpack it into Nextflow's
plugins directory:

```bash
mkdir -p ~/.nextflow/plugins/nf-workbench-0.4.0
unzip nf-workbench-0.4.0.zip -d ~/.nextflow/plugins/nf-workbench-0.4.0
```

Or build and install it from a clone, with a JDK 17+ and Gradle (`gradle wrapper` once, if you
don't have Gradle installed):

```bash
cd plugin
gradle install   # or: ./gradlew install, once you've generated the wrapper
```

Nextflow then finds it through the `plugins { id 'nf-workbench@0.4.0' }` block in
`nextflow.config` (see `examples/nextflow.config`). On Workbench, install it into the
`$NXF_HOME/plugins` of the user who runs Nextflow, on shared storage, so a headless driver job
finds it too.

### 2. Configure your pipeline

```groovy
plugins {
    id 'nf-workbench@0.4.0'
}

process {
    executor = 'workbench'
}

workbench {
    cluster = 'my-cluster'   // required -- see below for how to list valid names
}
```

Find valid cluster names in Workbench's Jobs UI, or with the optional CLI
(`posit-workbench-nf-launcher clusters`).

See [`docs/usage.md`](docs/usage.md) for the full usage reference: all `workbench`
settings, resource profiles, process directive mapping, the example's `--workbench*` command-line params,
headless runs, the optional launcher CLI, and troubleshooting.

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

- **Inside a Workbench session or job**: nothing to set. The plugin uses the session's RPC
  cookie, from `$PWB_SESSION_RUNTIME_DIR/rpc_cookie` or `RS_SESSION_RPC_COOKIE`, against
  `RS_SERVER_ADDRESS`.
- **Anywhere else**: set `WORKBENCH_SERVER` and `WORKBENCH_API_KEY` (a Bearer token).
- **A server certificate from a private CA**: add it to the JVM's trust store, e.g.
  `NXF_OPTS=-Djavax.net.ssl.trustStore=/path/to/truststore.jks`. The CLI uses `REQUESTS_CA_BUNDLE`
  or `SSL_CERT_FILE`.

## Testing

```bash
# Plugin unit tests (Spock), including the API client against a stub server
cd plugin && gradle test

# The real Nextflow with the plugin, against a stand-in for the Workbench API that runs each
# job as a local process (e2e/fake_workbench.py): a completed run, and a run stopped while its
# tasks run. Needs Nextflow 25.10+, Java 17+ and python3 (for the stand-in only).
make -C plugin assemble && e2e/run.sh

# CLI unit tests, against a stub server
cd launcher && pip install ".[dev]" && pytest
```

The interactive mode has been verified end-to-end on a real Workbench cluster. `examples/rnaseq`
was launched from a Positron session through the
[Positron Nextflow extension](https://github.com/sol-eng/positron-nextflow-extension), and
the session's credentials were picked up (with 0.3.0, through the launcher CLI). So have
headless runs started from a Positron session, where the driver
runs as a Workbench Job and submits its tasks with the job's own credentials. Headless runs
submitted through the Workbench API with `WORKBENCH_API_KEY` have not been tested yet, and 0.4.0's
built-in API client has so far been tested only against the stand-in, not a live server. Known
limitations: no wall-time/disk resource mapping, and log retrieval works only over the shared
filesystem.
