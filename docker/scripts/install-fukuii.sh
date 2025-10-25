#!/usr/bin/env bash

set -euxo pipefail

FUKUII_TAG=$1
FUKUII_DIST_ZIP_NAME=$2

HERE=$(readlink -m $(dirname ${BASH_SOURCE[0]}))
. $HERE/install-nix-common.sh

cd ~/repos/chordodes_fukuii

git checkout $FUKUII_TAG
git submodule update --init

sbt 'set test in Test := {}' dist
mkdir -p ~/fukuii-dist/app
unzip -d ~/fukuii-dist/app target/universal/${FUKUII_DIST_ZIP_NAME}.zip
mv ~/fukuii-dist/app/*/* ~/fukuii-dist/app
rmdir ~/fukuii-dist/app/$FUKUII_DIST_ZIP_NAME
rm -rf ~/repos ~/.ivy2 ~/.sbt