# Visor de corridas

Página local para revisar a ojo lo que hizo el worker: reproduce cada chunk con las cajas de SAM 3.1 (equipo, id del track, balón), la posesión por segundo en una línea de tiempo (tocar para saltar), las métricas, los hallazgos de Muse Spark y el `result.json` completo. Usa los mismos colores que la app (`docs/diseno.md`), en claro y oscuro según el sistema. Una corrida del simulador se marca "Análisis de desarrollo".

```bash
cd api
npm run viewer:up          # http://localhost:8098 (Docker), lee worker/.local/runs
```

Las corridas las guarda el worker con `DEBUG_DIR` (ya puesto en el servicio `worker` de docker-compose) o con `analyze --debug-dir`: `result.json`, `tracks.json` y una copia de los chunks. Los chunks que no son H.264 (los de `tools/make_chunks.py`) se convierten una vez con ffmpeg dentro del contenedor.

Sin Docker: `npm install && npm run build && npm start` (necesita ffmpeg en el PATH para chunks que no sean H.264); `npm run dev` para desarrollar con recarga.

Solo para la máquina de desarrollo: escucha en el puerto 8098 y no se despliega.
