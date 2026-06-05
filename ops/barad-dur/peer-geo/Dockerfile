# fukuii peer-geo exporter — stdlib-only, no pip deps.
FROM python:3.12-slim

WORKDIR /app
COPY peer_geo_exporter.py /app/peer_geo_exporter.py

# Cache lives on a mounted volume at /data so geolocations survive restarts.
VOLUME ["/data"]
EXPOSE 9099

ENV CACHE_FILE=/data/peer_geo_cache.json \
    LISTEN_PORT=9099 \
    REFRESH_INTERVAL=30

ENTRYPOINT ["python3", "/app/peer_geo_exporter.py"]
