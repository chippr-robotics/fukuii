#!/usr/bin/env bash

set -eux

HERE=$(readlink -m $(dirname ${BASH_SOURCE[0]}))

exec $HERE/buildhelper.sh fukuii-dev Dockerfile-dev latest
