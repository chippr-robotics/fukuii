#!/bin/bash
# Fukuii wrapper script
# This script launches the Fukuii Ethereum client with the proper configuration

# The package installs conf/ into /etc/fukuii, so the launcher never finds its
# application.ini (it looks in /usr/share/fukuii/conf) and the JVM flags there do not
# apply. The JDK-8385993 workaround (C2 ReduceAllocationMerges silently miscompiles
# field reads on JDK 23+, fixed in JDK 28) is required, so pass it here.
exec /usr/share/fukuii/bin/fukuii \
    -J-XX:+UnlockDiagnosticVMOptions \
    -J-XX:-ReduceAllocationMerges \
    -Dconfig.file=/etc/fukuii/app.conf \
    -Dlogback.configurationFile=/etc/fukuii/logback.xml \
    "$@"
