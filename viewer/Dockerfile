# The viewer of the worker's runs, only for the development machine.
# ffmpeg converts chunks that are not H.264 (tools/make_chunks.py) so the
# browser can play them; it stays in this local image and is never deployed.
FROM node:22-slim AS build
WORKDIR /viewer
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY src ./src
RUN npm run build

FROM node:22-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /viewer
COPY --from=build /viewer/dist ./dist
COPY server.mjs ./
ENV RUNS_DIR=/runs CACHE_DIR=/cache HOST=0.0.0.0 PORT=8098
EXPOSE 8098
CMD ["node", "server.mjs"]
