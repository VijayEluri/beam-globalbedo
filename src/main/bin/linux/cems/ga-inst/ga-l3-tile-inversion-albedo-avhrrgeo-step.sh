#!/bin/bash

. ${GA_INST}/bin/ga_env/ga-env-l3-tile-inversion-albedo.sh

tile=$1
year=$2
doy=$3
gaRootDir=$4
bbdrRootDir=$5
inversionRootDir=$6
usePrior=$7
priorDir=$8
beamDir=$9
modisTileScaleFactor=${10}
albedoTargetDir=${11}  # remind the brackets if >= 10!!

task="ga-l3-tile-inversion-albedo-avhrrgeo"
jobname="${task}-${tile}-${year}-${doy}"
command="./bin/${task}-beam.sh ${tile} ${year} ${doy} ${gaRootDir} ${bbdrRootDir} ${inversionRootDir} ${usePrior} ${priorDir} ${beamDir} ${modisTileScaleFactor} ${albedoTargetDir}"

echo "jobname: $jobname"
echo "command: $command"

echo "`date -u +%Y%m%d-%H%M%S` submitting job '${jobname}' for task ${task}"

echo "calling read_task_jobs()..."
read_task_jobs ${jobname}

if [ -z ${jobs} ]; then
    submit_job ${jobname} ${command}
fi

wait_for_task_jobs_completion ${jobname} 
