# The analysis worker: Python + OpenCV, no GPU and no local models (SAM 3.1 and
# Muse Spark run at Meta). The same image runs on a VPS, on Modal or next to
# the API in docker-compose.
FROM python:3.12-slim

WORKDIR /worker
COPY requirements.txt requirements-dev.txt ./
ARG DEV=0
RUN pip install --no-cache-dir -r requirements.txt \
    && if [ "$DEV" = "1" ]; then pip install --no-cache-dir -r requirements-dev.txt; fi

COPY ppcoachtracker_worker ./ppcoachtracker_worker
COPY tools ./tools

ENV WORK_DIR=/tmp/ppcoachtracker-worker \
    META_CACHE_DIR=/worker/.local/meta_cache \
    PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "ppcoachtracker_worker"]
CMD ["run"]
