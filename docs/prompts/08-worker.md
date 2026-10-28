# 08 · Worker de análisis (solo Meta, sin respaldo)

Esta etapa deja el worker base. Las etapas [11](11-worker-calidad.md) (cuadros que cuentan, balón), [12](12-mascara-cancha.md) (máscara de la cancha) y [13](13-cancha-en-metros.md) (metros) lo amplían; [arquitectura/06-pipeline-analisis.md](../arquitectura/06-pipeline-analisis.md) describe el resultado final del prototipo previo, que **sí** tenía respaldo local: aquí no.

**Sin respaldo:** SAM 3.1 sigue a jugadores y balón, y Muse Spark escribe los insights. No hay detector local (YOLO), ni tracker local, ni insights por reglas. Si Meta falla después de los reintentos, el trabajo falla con un mensaje claro. Así no hay modelos con licencias copyleft (YOLOv8n es AGPL-3.0) y el uso de las herramientas de Meta, que vale el 25 % de la evaluación, es total ([reglas](../rules/README.md)).

**En desarrollo, contra el simulador:** el worker se construye y prueba con `SAM_API_BASE_URL` apuntando al simulador local de la etapa [08a](08a-simulador-sam.md) ([13-entorno-desarrollo.md](../13-entorno-desarrollo.md)), con chunks de 1 min. Para el worker es solo una URL: no lleva código de modelos locales. La prueba con SAM real (criterios de abajo) se hace en cuanto haya key.

**Lee antes:** [13-entorno-desarrollo.md](../13-entorno-desarrollo.md), [arquitectura/06-pipeline-analisis.md](../arquitectura/06-pipeline-analisis.md), [03-arquitectura.md](../03-arquitectura.md#3-worker-de-ia-python) y [#4](../03-arquitectura.md#4-meta-model-api-lo-que-necesitamos-saber), [12-videos-largos-chunks.md](../12-videos-largos-chunks.md), [10-privacidad-jugadores.md](../10-privacidad-jugadores.md). No hay plan de respaldo: los errores se clasifican como dice el prompt.

```text
Crea el worker de análisis en worker/ (Python 3.12, solo numpy,
opencv-python-headless y PyYAML; HTTP con urllib; nada más sin preguntarme y
sin revisar su licencia). Corre en Docker (imagen python:3.12-slim) igual en
un VPS, en Modal o junto a la API en docker-compose (servicio "worker" con
profile "worker", código montado,
PP_COACH_TRACKER_API_URL=http://php/pp-coach-tracker).

NO HAY RESPALDO: no escribas detector local, ni tracker local, ni analista
por reglas, ni descargues modelos de visión. SAM 3.1 (Meta Model API) da
jugadores y balón; Muse Spark da los insights. Sin MODEL_API_KEY el worker no
arranca (error de configuración claro, sin imprimir la key).

Paquete ppcoachtracker_worker:
- __main__.py: `run` (consulta la cola cada POLL_SECONDS hasta SIGTERM),
  `once` (un trabajo), `analyze DIR [--home --away --home-color
  --away-color --out]` (sin la API de PP-Coach-Tracker, sobre una carpeta
  de chunks; sí llama a Meta).
- config.py (variables: PP_COACH_TRACKER_API_URL, WORKER_CALLBACK_SECRET,
  POLL_SECONDS, META_API_BASE_URL, SAM_API_BASE_URL (vacío = la de Meta; en
  desarrollo, el simulador), MODEL_API_KEY, SAM_MODEL "sam-3.1",
  MUSE_SPARK_MODEL "muse-spark-1.3", META_MAX_FRAMES_PER_JOB (15000),
  META_CACHE_DIR, WORK_DIR), api.py (next_job, download, post_result
  multipart con el análisis como archivo "analysis"), meta_api.py (cliente
  HTTP común: archivos, responses con stream, reintentos), chunks.py,
  types.py, colors.py, teams.py, metrics.py, pipeline.py, sam.py,
  insights.py.

Flujo de un trabajo: GET /internal/jobs/next → descargar clip.files a
WORK_DIR/job_NNNNNN → analizar → POST result done con el JSON; cualquier
excepción → failed con "tipo: mensaje" (InputError → "input: …",
MetaError → "meta_sam: …" o "muse_spark: …", sin la key ni el cuerpo
completo de la respuesta); borra la carpeta si salió bien y la conserva si
falló.

Lectura del clip: quita la primera línea "%YAML:1.0" antes de PyYAML. Lee
chunk_seconds y overlap_ms de chunks.yaml (perfiles de 1 min/2 s y 5 min/
3 s; NUNCA los supongas). Lee los chunks en orden como un solo video,
SALTANDO los overlap_frames de cada chunk n>1, con la hora del original desde el map (extrapola si el video
tiene más frames que el map).

Errores (sin escalera de respaldo):
- RetryableError (429, 5xx, timeout, stream cortado): reintenta la misma
  llamada 3 veces con backoff exponencial y respeta Retry-After.
- MetaError (401/403, modelo inexistente, salida ilegible después de los
  reintentos): el trabajo falla con ese mensaje.
- InputError (clip inválido, más de META_MAX_FRAMES_PER_JOB frames): el
  trabajo falla antes de gastar créditos.
El resultado registra en backends qué modelos se usaron, cuántas llamadas,
reintentos y frames se mandaron (backends.usage), para cuidar los créditos.

Caché: cada respuesta cruda de Meta se guarda en META_CACHE_DIR con llave
URL base + sha256(chunk) + modelo + concepto (o sha256 del prompt para Muse
Spark). La URL base en la llave evita que una respuesta del simulador salga
de la caché en una corrida contra SAM real.
Repetir el mismo chunk no vuelve a pagar. Es caché, no respaldo: si no está
en caché, se llama a Meta.

TrackSet común: filas (frame, t_ms, source_frame, track_id, label
player|ball, x, y, w, h normalizados 0..1, conf, team A|B|""). A = local.

sam.py (SAM 3.1):
- Por chunk: POST /files (multipart, purpose=user_data) → file_id; un POST
  /responses con stream por concepto ("player in <color> jersey" por equipo,
  "soccer ball"; si los colores no sirven, "soccer player"). Salida: marcas
  "<Nf>" y por objeto un ordinal y "<|box;x1=..;y1=..;x2=..;y2=..;c=..|>"
  (normalizadas o en pixeles). Guarda cada stream crudo.
- PRIMERO prueba con un clip propio corto y la key real, guarda el stream y
  ajusta el parser a lo que de verdad devuelve; escribe el formato real en
  worker/README.md. El parser es tolerante y sus pruebas usan esos streams.
- El id de track lo da SAM dentro de un chunk. Une tracks entre chunks por
  IoU en los frames del traslape (mismo frame del original): misma
  identidad si IoU > 0.5 en al menos la mitad de los frames de traslape en
  que aparecen los dos, y en 5 o más (el traslape es de 20 a 50 frames).
  Conserva el traslape una sola vez.
- Simulador: si una respuesta trae la cabecera X-PP-Simulator, o si
  SAM_API_BASE_URL no es la de Meta, backends.segmenter es
  "sam_simulator" (y analyst "muse_spark_simulator" si Muse Spark también
  respondió desde ahí). Nunca "meta_sam".

teams.py: el equipo sale del concepto con que SAM encontró al jugador. Si un
track aparece en los dos conceptos, o con "soccer player", decide el color:
mediana Lab del torso (15–50 % de alto, 25–75 % de ancho) ignorando pasto,
comparada con los colores declarados (home/away, mismos RGB de la app); muy
lejos de los dos (árbitro, porteros) queda sin equipo.

metrics.py (coordenadas de PANTALLA: no hay calibración de cancha todavía):
punto de pie = centro inferior de la caja. Por equipo: jugadores por cuadro,
tracks, forma media (centroide, spread_x, spread_y; solo con >= 3
jugadores), heatmap 12x8 normalizado, posesión (dueño = jugador más cercano
al balón a <= 0.06; rachas de menos de 5 cuadros son ruido: entre dos rachas
del mismo equipo cuentan para ese equipo, si no para nadie), posesión más
larga, presionadores (rivales a <= 0.10 del balón cuando el otro lo tiene).
Global: balón visto, cambios de posesión (y por minuto), timeline por
segundo, resumen por track (cuadros, primero/último ms, distancia ignorando
saltos > 0.2, posición media), eventos possession_change.

insights.py (Muse Spark): Responses API con salida estructurada (json_schema
estricto: summary + hasta 8 insights {title, description, t_start_ms,
t_end_ms, severity info|low|medium|high, recommendation}); en español; solo
métricas agregadas (sin heatmap ni tracks, sin nombres). El prompt le pide
decir solo lo que los números sostienen, "en la imagen" y nunca metros
mientras no haya cancha, y le da los avisos: pocos jugadores a la vista
(< 6 por cuadro) o balón casi no detectado (< 20 %) se dicen primero. Que
no diga "se siguieron 93 jugadores": son tracks, no personas. Si la
respuesta no valida, UNA llamada de reparación con el error; si tampoco,
el trabajo falla (MetaError). Guarda la respuesta cruda.

Resultado (lo guarda la API y lo leerá la app):
{version: 1, clip_id, job_id, generated_at, worker_version,
 processing_seconds, backends: {segmenter: "meta_sam", sam_model, analyst:
 "muse_spark", muse_spark_model, usage: {calls, retries, frames_sent,
 cached}}, match: {home, away, home_color, away_color}, video: {duration_ms,
 fps, frames, width, height, chunks, chunk_seconds, overlap_ms},
 coordinates: "screen", frames, teams:
 {A, B}, unassigned_per_frame, ball, timeline, tracks, events, summary,
 insights: [{title, description, players: [], t_start_ms, t_end_ms,
 severity, recommendation, source: "muse_spark"}], notes, limitations}

Herramientas: tools/make_chunks.py (corta un video local al formato de la
app, para probar sin teléfono; --profile dev|demo y --overlap-ms 2000..5000), tools/submit_clip.py (lo sube a la API como
lo haría la app, con consentimiento), tools/overlay.py (dibuja cajas y
equipos de un cuadro para revisar a ojo).

Pruebas (pytest, en Docker: `npm run worker:test` desde api/), SIN llamar a
Meta: una Meta API falsa (http.server) que responde con los streams
guardados del clip propio. YAML de OpenCV, clip de 2 chunks leído sin
repetir el traslape, parser de SAM (formatos de caja, stream cortado),
unión entre chunks con traslapes de 20 y 50 frames, perfiles de 1 y 5 min
leídos de chunks.yaml, segmenter "sam_simulator" con la cabecera, llave
de caché distinta por URL base, equipos por concepto y por color (incluido árbitro sin
equipo y colores declarados al revés), métricas y posesión con blips,
validación y reparación de Muse Spark, reintentos con 429/500 y Retry-After,
401 que hace fallar el trabajo sin respaldo, caché que evita la segunda
llamada, sin MODEL_API_KEY no arranca, trabajo completo contra una API
falsa incluido uno que falla por chunk faltante.

Escribe worker/README.md (cómo correrlo, variables, formato real de SAM,
formato del resultado, costo medido por minuto de video).
```

## Trampas conocidas

- **Cámara de TV que hace paneo:** SAM da ids dentro de un chunk, pero entre chunks la unión por IoU en el traslape falla si la cámara se movió mucho; revisar con `overlay.py` en el cambio de chunk.
- **Gastar créditos en pruebas:** las pruebas automáticas nunca llaman a Meta; para iterar, usar la caché y clips cortos. Medir el costo de 1 min de video antes de mandar un partido completo.
- **El formato de SAM no está verificado** hasta probarlo con la key: no escribir el parser solo desde la documentación.
- **`--start` fuera del video** en `make_chunks.py` da 0 cuadros sin error: revisar la duración primero.
- **Video de terceros** (transmisiones) solo para pruebas locales, nunca versionado ni en el demo ([08-uso-de-video.md](../08-uso-de-video.md)). Mandarlo a Meta también es usarlo: preferir video propio.
- **El límite por chunk** del Docker de pruebas tiene que ser el de producción (`MAX_CHUNK_BYTES` de 25 MB); con 1 MB un clip real no pasa por la API local.
- **No meter un detector "por si acaso":** cualquier modelo de visión local (YOLO de Ultralytics es AGPL-3.0) rompe la regla de licencias de [00-contexto.md](00-contexto.md). Si SAM no alcanza, se dice en los límites del resultado. El simulador de desarrollo no cuenta como detector del worker: vive fuera del repo y se usa por HTTP.
- **Funcionar con el simulador no prueba que funcione con SAM:** el formato del simulador es el documentado. Ajustar el parser (y el simulador) al primer stream real.
- **Un chunk de 5 min que falla cuesta caro:** ~3,030 frames por concepto. Reintentar solo errores temporales y nunca reenviar un chunk entero por un error de parseo: guardar el stream y reparar el parser.

## Criterios de aceptación

- [ ] `npm run worker:test` pasa sin red hacia Meta.
- [ ] Con el simulador: un clip de 2 chunks de 1 min encolado en la API local termina en `ready` con `backends.segmenter = "sam_simulator"` y los tracks unidos en el traslape.
- [ ] Con la key (`SAM_API_BASE_URL` vacío): `analyze` sobre un clip propio de 1–2 min usa `meta_sam` y `muse_spark`, los streams crudos quedan guardados, los equipos se ven bien en `overlay.py` y los insights son coherentes con el video.
- [ ] La segunda corrida del mismo clip sale de la caché (`usage.cached`) sin llamadas nuevas a SAM.
- [ ] Con la API PHP y el worker en Docker: un clip encolado termina en `ready` y `GET /clips/:id/analysis` devuelve el resultado.
- [ ] Con una key inválida, el trabajo termina en `failed` con `meta_sam: …` y sin la key en los logs.
- [ ] Con la key: el clip del demo en chunks de 5 min (perfil `demo`) termina en `ready` y la unión entre los dos chunks se ve bien en `overlay.py`.
- [ ] `worker/README.md` dice cuánto costó un minuto de video y cómo correr con el simulador.
