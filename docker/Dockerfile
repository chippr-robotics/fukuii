FROM fukuii-dev:latest as CURRENTBUILD

ARG FUKUII_TAG
ENV FUKUII_TAG=${FUKUII_TAG:-develop}

# The command `sbt dist` generates a zip file in the `target/universal` directory.
# The value of `FUKUII_DIST_ZIP_NAME` must be the name of the generated zip,
# excluding the extension.
# So, for example, currently (commit `35e06611`) the `sbt dist` command
# produces `target/universal/fukuii-1.0-daedalus-rc1.zip`, so we can set
# `FUKUII_DIST_ZIP_NAME` to be `fukuii-1.0-daedalus-rc1`.
# A glob like `fukuii-*` also works and is more convenient, since it is invariant
# with respect to the other part, which dependens on the software version.
ARG FUKUII_DIST_ZIP_NAME
ENV FUKUII_DIST_ZIP_NAME=${FUKUII_DIST_ZIP_NAME:-fukuii-*}

# Grab latest fukuii, build the distribution and install it
RUN ~/install-fukuii.sh $FUKUII_TAG $FUKUII_DIST_ZIP_NAME
# Now fukuii is in /home/fukuii/fukuii-dist/app
# or just /app

# Start over and keep what is needed.
# Now the size optimization comes from `fukuii-base`:
# smaller `fukuii-base` means smaller `fukuii` image (this image).  
FROM fukuii-base:latest

USER root
COPY --from=CURRENTBUILD /home/fukuii/fukuii-dist /home/fukuii/fukuii-dist
RUN chown -R fukuii:fukuii /home/fukuii/fukuii-dist

USER fukuii
WORKDIR /app
VOLUME /app/conf