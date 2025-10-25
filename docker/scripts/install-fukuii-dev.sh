#!/usr/bin/env bash

set -euxo pipefail

FUKUII_TAG=$1

HERE=$(readlink -m $(dirname ${BASH_SOURCE[0]}))
. $HERE/install-nix-common.sh

mkdir ~/repos

cd ~/repos
git clone https://github.com/chippr-robotics/chordodes_fukuii.git
cd chordodes_fukuii
git checkout $FUKUII_TAG
git submodule update --init

# Trigger compilation, so that we get some dependencies from the internetz.
sbt 'set test in Test := {}' compile