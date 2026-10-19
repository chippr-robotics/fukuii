#!/bin/bash
# Fukuii wrapper script
# This script launches the Fukuii Ethereum client with the proper configuration

# JVM flags (heap, GC, and the JDK-8385993 C2 ReduceAllocationMerges workaround) come from
# /etc/fukuii/application.ini, which the launcher reads through the /usr/share/fukuii/conf -> /etc/fukuii
# link (debian/fukuii.links). Do not repeat them here.
exec /usr/share/fukuii/bin/fukuii \
    -Dconfig.file=/etc/fukuii/app.conf \
    -Dlogback.configurationFile=/etc/fukuii/logback.xml \
    "$@"
