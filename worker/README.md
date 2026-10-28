# Worker de análisis de pp-coach-tracker

Toma los clips de la cola de la API, sigue a jugadores y balón con **SAM 3.1**, calcula métricas por equipo y escribe los hallazgos con **Muse Spark**, los dos por la Meta Model API.

**Sin respaldo:** no hay detector local, ni tracker local, ni insights por reglas. Si Meta falla después de los reintentos, el trabajo termina en `failed` con un mensaje claro. Sin `MODEL_API_KEY` el worker no arranca.

## Cómo correrlo

```bash
cd api
npm run worker:test                 # pruebas (pytest en Docker, sin red hacia Meta)
npm run worker:up                   # el worker junto a la API PHP local (consulta la cola)

# Sin la API, sobre una carpeta de chunks (sí llama a Meta o al simulador):
wsl -e docker compose --profile worker run --rm worker analyze /worker/.local/mi_clip --home-color red --away-color blue
#   --touches puntos.json --pitch-mode no_lines   los puntos de la cancha (como POST /clips/:id/calibration)
#   --debug-dir /worker/.local/runs               guarda la corrida para el visor

# Herramientas
python tools/make_chunks.py video.mp4 .local/mi_clip --profile dev            # corta un video como la app
PP_COACH_TRACKER_TOKEN=… python tools/submit_clip.py .local/mi_clip --api http://localhost:8097/pp-coach-tracker
python tools/overlay.py .local/mi_clip --frame 590                             # cajas y equipos de un cuadro
```

Las herramientas y `analyze` usan OpenCV: en Windows corren dentro del contenedor (`wsl -e docker compose --profile worker run --rm --entrypoint python worker tools/…`).

### Con el simulador de SAM (desarrollo)

El worker se desarrolla contra el simulador local de la API de SAM ([docs/13-entorno-desarrollo.md](../docs/13-entorno-desarrollo.md)), que vive **fuera de este repo**. Para el worker es solo otra URL:

```bash
wsl -e docker network create ppcoachtracker-dev     # una vez
# en la carpeta del simulador: wsl -e docker compose up -d sam-sim
# en .env: SAM_API_BASE_URL=http://sam-sim:8090/v1
npm run php:up && npm run worker:up
```

Todo lo que sale del simulador se marca `backends.segmenter = "sam_simulator"` (por la cabecera `X-PP-Simulator` o porque `SAM_API_BASE_URL` no es la de Meta), y la API de producción lo rechaza. Sin key: `META_API_BASE_URL` también al simulador, `MODEL_API_KEY=sim-local` y `SIM_MUSE=1`; entonces `analyst = "muse_spark_simulator"`.

## Variables

| Variable | Para qué | Por omisión |
|---|---|---|
| `MODEL_API_KEY` | Key de la Meta Model API | — (obligatoria) |
| `META_API_BASE_URL` | Meta Model API (Muse Spark) | `https://api.meta.ai/v1` |
| `SAM_API_BASE_URL` | SAM; vacío = la de Meta. En desarrollo, el simulador | vacío |
| `SAM_MODEL`, `MUSE_SPARK_MODEL` | Modelos | `sam-3.1`, `muse-spark-1.3` |
| `META_MAX_FRAMES_PER_JOB` | Un clip con más cuadros (por concepto) falla antes de gastar | 15000 |
| `META_CACHE_DIR` | Caché de respuestas crudas | `.local/meta_cache` |
| `PP_COACH_TRACKER_API_URL`, `WORKER_CALLBACK_SECRET` | La API y el secreto de `/internal/*` (para `run` y `once`) | — |
| `POLL_SECONDS` | Cada cuánto consulta la cola | 10 |
| `WORK_DIR` | Carpeta de trabajo (`job_NNNNNN`; se borra si salió bien) | `.local/jobs` |

## Flujo de un trabajo

1. `GET /internal/jobs/next` → descarga `clip.files` a `WORK_DIR/job_NNNNNN/clip`.
2. Lee `chunks.yaml` (perfil: `chunk_seconds`, `overlap_ms`; nunca se suponen) y los YAML de cada chunk (quita la línea `%YAML:1.0` antes de PyYAML).
3. **SAM 3.1** por chunk: `POST /files` y un `POST /responses` con stream por concepto (`player in <color> jersey` por equipo y `soccer ball`; `soccer player` si los colores no distinguen a los equipos). Los streams crudos quedan en `raw/` y todas las cajas en `raw/tracks.json`.
4. Une los tracks entre chunks por IoU en los cuadros del traslape: misma identidad si IoU > 0.5 en al menos la mitad de los cuadros de traslape donde aparecen los dos, y en 5 o más. El traslape se conserva una vez.
5. Equipos: del concepto; el color del torso (mediana Lab, sin pasto) decide solo si un track aparece en los dos conceptos o con `soccer player`. Muy lejos de los dos colores (árbitro, porteros) queda sin equipo.
6. Métricas en coordenadas de **pantalla**, siempre. Y, si el entrenador marcó puntos y no contestó "No calcular la cancha" (`options.pitch_mode` distinto de `off`), **la cancha en metros** (`calibration/`):
   - la homografía del cuadro tocado con 5 o más puntos que coincidan (con 4 cualquier nombre encaja; RANSAC quita los mal nombrados), si la mediana del error no pasa de 1.5 % del alto;
   - propagada por su toma con KLT sobre el pasto y `findHomography` (una cámara que no se mueve queda fija); la cadena se corta en un corte de cámara, con menos de 30 % (o de 30) puntos que coinciden, o donde menos del 90 % de los pies cae en la cancha con 5 m de margen;
   - cada jugador gana `mx`, `my`; las métricas en metros (`pitch`) salen solo de los cuadros con homografía y solo con 10 s o más, con velocidades entre las posiciones medias de cada segundo (un paso de más de 11 m/s es un cambio de track y no cuenta).
   - Las líneas con SAM (paso 5 de la etapa 13) no están hechas: no hay nada automático sin puntos.
7. **Muse Spark** con salida estructurada (`json_schema` estricto); una reparación si no valida; si tampoco, falla.
8. `POST /internal/jobs/:id/result` (multipart, el análisis como archivo `analysis`).

Errores: `RetryableError` (429, 5xx, timeout, stream cortado) se reintenta 3 veces con backoff exponencial y respeta `Retry-After`; `MetaError` (401/403, modelo inexistente, salida ilegible) falla el trabajo con `meta_sam: …` o `muse_spark: …`; `InputError` (clip inválido o demasiados cuadros) con `input: …`, antes de gastar créditos. La key nunca aparece en los mensajes.

**Caché:** cada respuesta cruda se guarda con la llave `URL base + sha256(chunk) + modelo + concepto` (o el sha256 del prompt para Muse Spark). La URL base evita que una respuesta del simulador salga de la caché en una corrida contra SAM real. Es caché, no respaldo.

## Formato de SAM

> **No verificado todavía.** El parser sigue el formato documentado: una marca `<Nf>` por cuadro y, por objeto, su ordinal y `<|box;x1=..;y1=..;x2=..;y2=..;c=..|>` (normalizadas 0..1 o en pixeles), dentro de eventos `response.output_text.delta` hasta `response.completed`. Las pruebas usan streams **sintéticos** con ese formato (`tests/fakes.py`). En cuanto haya key: capturar el stream de un clip propio de 1 min (`analyze` deja los crudos en `raw/`), ajustar el parser y el simulador a lo que de verdad devuelve, reemplazar los streams sintéticos de las pruebas por esos y escribir aquí el formato real.

## Resultado

```json
{
  "version": 1, "clip_id": 54, "job_id": 18, "generated_at": "…Z", "worker_version": "0.1.0", "processing_seconds": 0.4,
  "backends": {"segmenter": "meta_sam", "sam_model": "sam-3.1", "analyst": "muse_spark", "muse_spark_model": "muse-spark-1.3",
               "usage": {"calls": 9, "retries": 0, "frames_sent": 2310, "cached": 0}},
  "match": {"home", "away", "home_color", "away_color"},
  "video": {"duration_ms", "fps", "frames", "width", "height", "chunks", "chunk_seconds", "overlap_ms"},
  "coordinates": "screen", "frames": 750,
  "teams": {"A": {"players_per_frame", "max_players", "tracks", "shape", "heatmap", "possession", "possession_frames",
                  "longest_possession_ms", "pressers_per_frame"}, "B": {…}},
  "unassigned_per_frame", "ball": {"seen_frames", "seen_ratio"}, "possession_changes", "possession_changes_per_minute",
  "timeline": [{"t_ms", "players_a", "players_b", "ball", "owner"}],
  "tracks": [{"id", "label", "team", "frames", "first_ms", "last_ms", "distance", "mean_x", "mean_y"}],
  "events": [{"type": "possession_change", "frame", "t_ms", "from", "to"}],
  "summary": "…", "insights": [{"title", "description", "players": [], "t_start_ms", "t_end_ms", "severity", "recommendation", "source": "muse_spark"}],
  "notes": ["…avisos…"], "limitations": ["…"]
}
```

Todos los tiempos (`t_ms`, `t_start_ms`…) son del video **original**, traducidos con el `map` de cada chunk: "ver en el video" usa el mismo reloj que el clip del teléfono. A = local.

## Costo

SAM cobra US$0.20 por cada 1,000 cuadros y concepto (3 conceptos por chunk). Un minuto de video a 10 fps en el perfil `dev` son ~600 cuadros por concepto: **~US$0.37 estimado**. **Costo medido: pendiente** (requiere la key; `backends.usage.frames_sent` lo da por corrida).

## Herramientas

- `tools/make_chunks.py`: corta un video local al formato de la app (`--profile dev|demo`, `--overlap-ms 2000..5000`). Escribe MPEG-4 (`mp4v`) porque OpenCV no trae codificador H.264; para SAM real conviene recodificar con `ffmpeg -c:v libx264` o usar los chunks de la app. Revisa la duración antes de usar `--start`.
- `tools/submit_clip.py`: lo sube a la API como la app (consentimiento, partes de 8 MB, reanuda por offset).
- `tools/overlay.py`: dibuja las cajas y equipos de un cuadro; úsalo alrededor del cambio de chunk para revisar la unión.
