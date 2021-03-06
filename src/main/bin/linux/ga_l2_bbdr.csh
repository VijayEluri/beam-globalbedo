#!/bin/tcsh

#input parameters:
set l1bproduct = $1
set sensor = $2   # MERIS, AATSR or VGT
set year = $3
set tile = $4

set l1bRootDir = $5   # at BC: "/data/MER_RR__1P"
set gaRootDir = $6    # at BC:  /bcserver12-data/GlobAlbedo
set beamRootDir = $7  # at BC:  /opt/beam-4.9.0.1

set l1bBaseName = `basename $l1bproduct .N1`

echo "time $beamRootDir/bin/gpt-d-l2.sh  ga.l2 -Psensor=$sensor -Ptile=$tile -PcomputeL1ToAotProductOnly=true $l1bRootDir/$year/$l1bproduct -e -t $gaRootDir/${l1bBaseName}_AOT.dim"
time $beamRootDir/bin/gpt-d-l2.sh  ga.l2 -Psensor=$sensor -Ptile=$tile -PcomputeL1ToAotProductOnly=true $l1bRootDir/$year/$l1bproduct -e -t $gaRootDir/${l1bBaseName}_AOT.dim
echo "AOT intermediate product created."
echo " $beamRootDir/bin/gpt-d-l2.sh  ga.l2 -Psensor=$sensor -Ptile=$tile -PcomputeAotToBbdrProductOnly=true $gaRootDir/${l1bBaseName}_AOT.dim  -t $gaRootDir/BBDR/$sensor/$year/$tile/subset_${l1bBaseName}_BBDR_Geo.dim"

if (! -e $gaRootDir/BBDR/$sensor/$year/$tile) then
    mkdir -p ${gaRootDir}/BBDR/$sensor/$year/$tile
endif

time $beamRootDir/bin/gpt-d-l2.sh  ga.l2 -Psensor=$sensor -Ptile=$tile -PcomputeAotToBbdrProductOnly=true $gaRootDir/${l1bBaseName}_AOT.dim  -t $gaRootDir/BBDR/$sensor/$year/$tile/subset_${l1bBaseName}_BBDR_Geo.dim
echo "BBDR product created."
echo "Removing AOT intermediate product..."
#rm -f $gaRootDir/${l1bBaseName}_AOT.dim
#rm -Rf $gaRootDir/${l1bBaseName}_AOT.data
echo "Done."
