# Visor de corridas

Página local para revisar a ojo lo que hizo el worker: reproduce cada chunk con las cajas de SAM 3.1 (equipo, id del track, balón), la posesión por segundo en una línea de tiempo (tocar para saltar), las métricas, los hallazgos de Muse Spark y el `result.json` completo. Usa los mismos colores que la app (`docs/diseno.md`), en claro y oscuro según el sistema. Una corrida del simulador se marca "Análisis de desarrollo".

```bash
cd api
npm run viewer:up          # http://localhost:8098 (Docker), lee worker/.local/runs
```

Las corridas las guarda el worker con `DEBUG_DIR` (ya puesto en el servicio `worker` de docker-compose) o con `analyze --debug-dir`: `result.json`, `tracks.json` y una copia de los chunks. Los chunks que no son H.264 (los de `tools/make_chunks.py`) se convierten una vez con ffmpeg dentro del contenedor.

## Comparar dos corridas

Marca dos corridas en la lista (la casilla de cada una) y toca **Comparar**: sirve para comparar un segmentador local contra SAM 3.1 o para medir un cambio antes y después, siempre sobre los mismos chunks. Muestra:

- el video con las cajas de las dos: **Lado a lado** (el de la derecha sigue al de la izquierda), **Superpuestas** (la primera continua, la segunda punteada) o **Diferencias** (en gris lo que encontraron las dos con IoU ≥ 0.3; en naranja o violeta lo que encontró solo una), y en cada cuadro cuántos jugadores vio cada una;
- jugadores por cuadro de cada segundo en una gráfica, y debajo los segundos en que cada una vio el balón (tocar para saltar);
- las métricas medidas igual en las dos desde sus cajas (jugadores por equipo y sin equipo, tracks y su duración, balón, posesión, tiempo y costo de SAM), la coincidencia cuadro por cuadro y los hallazgos de cada una.

Avisa si las dos corridas no tienen los mismos chunks. Lee también el formato anterior de `tracks.json` (con los chunks junto a `result.json`).

Las corridas guardadas a mano para comparar van en `LOCAL_DATA/Results` (o en la carpeta de `VIEWER_EXTRA_RUNS` en `api/.env`), que el contenedor monta de solo lectura en `/extra`. Una corrida es cualquier carpeta, hasta 3 niveles abajo, con `result.json` y `tracks.json`; su id es su ruta unida con `~` (`sam~run~20261030-…`).

Sin Docker: `npm install && npm run build && npm start` (necesita ffmpeg en el PATH para chunks que no sean H.264; `RUNS_DIR` acepta varias carpetas separadas por comas); `npm run dev` para desarrollar con recarga.

Solo para la máquina de desarrollo: escucha en el puerto 8098 y no se despliega.
