#!/bin/bash
# Fukuii wrapper script
# This script launches the Fukuii Ethereum client with the proper configuration

exec /usr/share/fukuii/bin/fukuii \
    -Dconfig.file=/etc/fukuii/app.conf \
    -Dlogback.configurationFile=/etc/fukuii/logback.xml \
    "$@"
