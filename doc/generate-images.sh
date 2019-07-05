#! /bin/bash

# Remember the current directory when the script was started:
INSTALL_DIR="${PWD}"

# Found here:
#
# https://stackoverflow.com/questions/4774054/reliable-way-for-a-bash-script-to-get-the-full-path-to-itself/20265654
#
# I tested it on Ubuntu 18.04 Linux and macOS 10.13.6 and it worked
# for both, so at least for now I am going for it.
SCRIPTPATH="$( cd "$(dirname "$0")" ; pwd -P )"
#echo ":${SCRIPTPATH}:"
#exit 0

OUTPUT_DIR="${SCRIPTPATH}/tryout-images"
mkdir -p "${OUTPUT_DIR}"

ALIAS="-A:generate"
#ALIAS="${ALIAS}:disablecompressedoops"
clojure $ALIAS -m gen.generate "${OUTPUT_DIR}"

cd "${OUTPUT_DIR}"
for j in *.dot
do
    echo "Generating images from $j ..."
    for format in png pdf
    do
	dot -T${format} "$j" -o `basename $j .dot`.${format}
    done
done
