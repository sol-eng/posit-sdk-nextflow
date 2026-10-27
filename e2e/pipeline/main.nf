// The pipeline e2e/run.sh runs through the nf-workbench executor. With --sleep N, each task
// writes a marker to --marker and then sleeps, so the run can be stopped while tasks run.
params.sleep = 0
params.marker = ''

process SAY {
  cpus 2
  memory '256 MB'
  ext resourceProfile: 'large'

  input: val x
  output: path "out_${x}.txt"
  script:
  """
  ${params.marker ? "touch '${params.marker}'" : ''}
  sleep ${params.sleep}
  echo hello ${x} > out_${x}.txt
  """
}

workflow {
  SAY(channel.of(1, 2)).collect().view { files -> "done: ${files*.name.sort()}" }
}
