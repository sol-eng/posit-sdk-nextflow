# RNA-seq example: parallel Workbench Jobs

A small RNA-seq quantification pipeline, adapted from the canonical
[nextflow-io/rnaseq-nf](https://github.com/nextflow-io/rnaseq-nf) tutorial. It shows how the
`workbench` executor runs independent tasks in parallel. Every task is its own Posit Workbench
Job, and Nextflow submits a job as soon as its inputs are ready.

```text
                     +--> FASTQC (x N samples) --+
  reads (N samples) -+                           +--> MULTIQC
                     +--> QUANT  (x N samples) --+
                             ^
  transcriptome --> INDEX ---+  (one index, reused by every QUANT task)
```

| Step | Tool (container) | Runs | Parallelism |
|---|---|---|---|
| `INDEX` | Salmon `quay.io/biocontainers/salmon:1.10.3--h6dccd9a_2` | once | Starts immediately, alongside FASTQC |
| `FASTQC` | FastQC `quay.io/biocontainers/fastqc:0.12.1--hdfd78af_0` | per sample | Fan-out: one job per sample, all at once |
| `QUANT` | Salmon | per sample | Fan-out: all start as soon as `INDEX` is done |
| `MULTIQC` | MultiQC `quay.io/biocontainers/multiqc:1.32--pyhdfd78af_0` | once | Fan-in: waits for all 2×N per-sample results |

## Run it

Requirements are the same as for the root example (plugin installed, launcher CLI on `PATH`,
shared filesystem). The test data (a chicken transcriptome slice and 4 paired-end samples,
about 6 MB in total) is downloaded from GitHub by the Nextflow driver into the work dir. The
Workbench Jobs only need to be able to pull the `quay.io` images.

```bash
cd examples/rnaseq
nextflow run main.nf --workbenchCluster Kubernetes
```

Measured on a Kubernetes cluster, with images already pulled:

| Setting | Max concurrent jobs | Wall time |
|---|---|---|
| default (`queueSize = 10`) | 8 | ~38 s |
| `--workbenchQueueSize 2` | 2 | ~93 s |

The first run takes longer while the cluster pulls the images.

## Params

| Param | Default | Purpose |
|---|---|---|
| `--samples LIST` | `gut,liver,lung,spleen` | Comma-separated sample names from the bundled test data. Use fewer to scale down. |
| `--reads GLOB` | unset | Use your own paired-end FASTQs instead, e.g. `'data/*_{1,2}.fq'`. Overrides `--samples`. |
| `--transcriptome PATH` | rnaseq-nf test transcriptome | Reference transcriptome FASTA (local path or URL). |
| `--dataUrl URL` | rnaseq-nf `data/ggal` on GitHub | Base URL for the `--samples` FASTQs. |
| `--outdir DIR` | `results` | Published outputs and execution reports. |
| `--workbenchCluster NAME` | `Kubernetes` | Sets `workbench.cluster`. |
| `--workbenchResourceProfile NAME` | unset | Sets `workbench.resourceProfile`. |
| `--workbenchContainer IMG` | `python:3.12-slim` | Fallback `process.container`. Every process declares its own image, so this is rarely used. |
| `--workbenchQueueSize N` | `10` | Sets `executor.queueSize`, the maximum number of Workbench Jobs submitted at once. |

## Tuning parallelism

- **Concurrency cap:** `executor.queueSize`, or `--workbenchQueueSize`. With `--workbenchQueueSize 2`
  the same pipeline runs at most 2 jobs at a time. That's useful for seeing the difference in
  `timeline.html`, or for staying within a shared cluster's quota.
- **Per-task resources:** set by label in `nextflow.config` (`single_core`: 1 CPU / 1 GB,
  `multi_core`: 2 CPU / 2 GB). Each task's request has to fit within the cluster's
  `resourceLimits` `maxValue`, or Workbench rejects the job.
- **Status polling:** `executor.queueStatInterval = '15 sec'`. Each poll lists every job visible
  to your credential. Completion is noticed at the next poll, which is why the tasks in
  `trace.txt` tend to finish in batches.

## Outputs

```text
results/
├── quant/<sample>/quant.sf        # Salmon transcript quantification, per sample
├── multiqc_report.html            # QC + mapping summary across all samples
└── pipeline_info/
    ├── timeline.html              # Gantt chart of all tasks -- overlapping bars = parallel jobs
    ├── report.html                # Nextflow execution report
    └── trace.txt                  # per-task submit/start/complete times + Workbench job id
```

The `native_id` column in `trace.txt` is the Workbench job id. Use it to look a task up in the
Workbench Jobs UI, or to query it with `posit-workbench-nf-launcher status`.
