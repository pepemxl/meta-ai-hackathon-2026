# 04 · API Node + MySQL

**Lee antes:** [arquitectura/03-api.md](../arquitectura/03-api.md) y [arquitectura/04-flujos.md](../arquitectura/04-flujos.md) (flujos 1, 3, 4 y 5), [12-videos-largos-chunks.md](../12-videos-largos-chunks.md#cómo-los-usan-la-api-y-el-worker).

```text
Crea la API de pp-coach-tracker en api/: Node.js >= 20, Express, multer, mysql2,
jsonwebtoken. Sirve para desarrollar y probar; en producción correrá una
versión PHP con el MISMO contrato (otra etapa), así que el contrato de abajo es
ley: rutas, cuerpos, códigos y nombres de campos.

Estructura: src/server.js (config por variables de entorno), src/app.js
(createApp({repo, storageDir, tokens, jwtSecret, workerSecret,
maxChunkBytes}) → Express app), src/auth.js, src/consent.js, src/storage.js,
src/repo/memory.js y src/repo/mysql.js con la MISMA interfaz,
src/cli/add-user.js, db/schema.sql, docker-compose.yml (MySQL 8 en
127.0.0.1:13306, base y usuario ppcoachtracker, que carga el esquema al
primer arranque), tests/. Revisa la licencia de cada dependencia y anótala
en THIRD_PARTY.md.

Variables: JWT_SECRET (>= 32, obligatorio: no arranca sin él),
WORKER_CALLBACK_SECRET, API_TOKENS ("coach_id:token,…" para scripts y
pruebas), REPO (mysql|memory), DB_*, STORAGE_DIR, HOST/PORT (127.0.0.1:3000),
MAX_PART_BYTES (8 MB), MAX_CHUNK_BYTES (160 MB por chunk armado),
PP_COACH_TRACKER_REQUIRE_META (1 en producción: solo acepta resultados de
SAM real).

AUTENTICACIÓN
- Entrenador: POST /auth/login {email, password} → {token, email, name}.
  JWT HS256, sub "u<id>", exp 30 días. Contraseñas con scrypt. El coach_id de
  todo lo que sube es "u<id>". También vale un token fijo de API_TOKENS
  (comparación en tiempo constante contra todos).
- Límite: 10 fallos por correo en 15 min → 429 con Retry-After.
- Worker: Bearer WORKER_CALLBACK_SECRET en /internal/*.
- No hay registro público: npm run user:add -- correo "Nombre" 'contraseña'.

RUTAS (errores siempre {error: "...", ...extra})
GET  /health → {ok, version, db, authenticated, consent_version}
POST /auth/login
POST /clips (entrenador), JSON:
  {client_clip_id, title, duration_ms, chunk_count, chunk_seconds, overlap_ms,
   match: {client_match_id, home, away, home_color, away_color, date, venue},
   consent: {version, accepted_at, app_version}}
  → {clip_id, status, chunk_count, received: [{number, sha256}]}
  - Idempotente por (coach_id, client_clip_id); el partido por
    (coach_id, client_match_id). Si cambia chunk_count, chunk_seconds u
    overlap_ms, se descartan los chunks anteriores (y sus .partial) y vuelve a "registered".
  - 400 {error:"invalid fields", fields:[…]} con límites: duration_ms <= 6 h,
    chunk_count 1..1000, textos 1..160, chunk_seconds 60..300 (perfiles dev
    = 60 y demo = 300, docs/12-videos-largos-chunks.md), overlap_ms
    2000..5000.
  - La respuesta trae también partial: [{number, received_bytes}] de los
    chunks a medias, para reanudar.
  - 428 {error:"consent required", consent_version} si falta consent, la
    versión no es la vigente (constante CONSENT_VERSION = "2026-10-27") o la
    fecha es inválida o más de 1 día en el futuro.
  - 428 {error:"consent withdrawn", consent_version} si accepted_at es
    anterior o igual al último retiro del entrenador.
  - Registra la aceptación como evento (abajo) y liga el clip a ese evento.
PUT  /clips/:id/chunks/:n/video?offset=<bytes> — cuerpo CRUDO
  (application/octet-stream), una parte de hasta MAX_PART_BYTES (413 si
  pasa). Se escribe al final de STORAGE_DIR/clip_%06d/video_part_%03d.mp4.partial
  SOLO si offset es igual a lo que ya hay; si no → 409 {error:"offset
  mismatch", expected_offset}. 413 si el total pasaría MAX_CHUNK_BYTES.
  Primera parte del clip → "uploading". → {number, received_bytes}
PUT  /clips/:id/chunks/:n — multipart yaml, sha256 (campo), SIN video: cierra
  el chunk. Calcula el sha256 del .partial; si no coincide con el campo o
  con el `sha256:` del YAML → 422 {error:"sha256 mismatch", expected, got},
  borra el .partial y no guarda nada. Si coincide, lo renombra a
  video_part_%03d.mp4 y guarda el .yaml. 409 si no hay .partial.
  → {number, sha256, size_bytes}
POST /clips/:id/complete — multipart index (chunks.yaml). 409 {error:"missing
  chunks", missing:[…]} si falta alguno. Si están todos: guarda chunks.yaml,
  crea un trabajo (o reutiliza el abierto en queued/running) y el clip pasa a
  "queued". → como POST /clips + job_id
GET  /clips/:id → estado, chunks recibidos y partial (bytes de los chunks a
  medias)
GET  /clips/:id/analysis → {clip_id, job_id, segmenter, analyst,
  created_at, result} o 404 {error:"analysis not found", status}
POST /consent {action: "accept"|"withdraw", version, at, app_version}
  - accept solo para la versión vigente; withdraw puede nombrar una anterior
    (version /^[\w.-]{1,20}$/). Fecha como en /clips. 400 si no.
  - INSERT IGNORE: el mismo evento dos veces (misma acción, versión y hora
    al segundo) queda una vez. → {id, action, version, at (ISO .000Z)}
GET  /consent → {consent_version, current: none|accepted|withdrawn|outdated,
  events:[{action, version, at, received_at, app_version}]}
GET  /internal/jobs/next (worker) → 204, o {job_id, type, clip: {id, title,
  duration_ms, chunk_count, dir, index, files:["chunks.yaml",
  "video_part_001.mp4","video_part_001.yaml",…]}, match: {home, away,
  home_color, away_color, date, venue}}. Toma el queued más viejo con
  SELECT … FOR UPDATE SKIP LOCKED, lo pasa a running y el clip a "segmenting".
GET  /internal/clips/:id/files/:name (worker) — solo nombres que cumplan
  /^(video_part_\d{3}\.(mp4|yaml)|chunks\.yaml)$/; 404 si no.
POST /internal/jobs/:id/result (worker) — JSON {status: done|failed, error}
  o MULTIPART con esos campos y el análisis como archivo "analysis" (JSON):
  no cabe en el límite de 64 KB del JSON. Si es done con análisis: debe ser
  un objeto JSON (400 si no) y se guarda en clip_analyses (segmenter y
  analyst salen de result.backends). Con PP_COACH_TRACKER_REQUIRE_META=1,
  un segmenter distinto de "meta_sam" (por ejemplo "sam_simulator") → 422
  {error:"simulator result rejected"} y el clip pasa a failed. El clip pasa
  a ready o failed.

Un entrenador nunca ve ni toca clips de otro: 404.
Estados del clip: registered → uploading → queued → segmenting → ready|failed.

ESQUEMA (db/schema.sql, solo CREATE TABLE IF NOT EXISTS, utf8mb4):
users(id, email UNIQUE, name, password_hash, created_at)
matches(id, coach_id, client_match_id, home, away, home_color, away_color,
        match_date, venue, created_at, UNIQUE(coach_id, client_match_id))
clips(id, coach_id, client_clip_id, match_id FK, title, duration_ms,
      chunk_count, chunk_seconds, overlap_ms, status, created_at, updated_at,
      UNIQUE(coach_id, client_clip_id))
clip_chunks(clip_id FK CASCADE, number, sha256, size_bytes, received_at, PK(clip_id, number))
jobs(id, clip_id FK CASCADE, type 'segment', status queued|running|done|failed,
     error VARCHAR(500), created_at, started_at, finished_at, KEY(status, id))
login_failures(id, email, ip, at, KEY(email, at), KEY(ip, at))  -- la usa PHP
consent_events(id, coach_id, action, version, at DATETIME, received_at
     DATETIME, ip, app_version, UNIQUE(coach_id, action, version, at))
clip_consent_events(clip_id PK FK CASCADE, event_id FK)
clip_analyses(clip_id PK FK CASCADE, job_id, segmenter, analyst,
     result MEDIUMTEXT, created_at)
Fechas de consentimiento en UTC (en mysql2: timezone 'Z').

PRUEBAS (node:test + supertest), corren en memoria con `npm test` y contra el
MySQL de Docker con `TEST_MYSQL=1 npm test` (y al terminar borran sus datos):
health, todas las rutas piden token, registro idempotente y validado,
subida completa en varias partes, sha256 incorrecto, reanudar a media parte
(offset equivocado → 409 con expected_offset), cerrar sin .partial,
chunk_seconds y overlap_ms fuera de rango, números de chunk inválidos /
archivos faltantes / otro entrenador / parte o chunk grande, resultado del
simulador rechazado con REQUIRE_META, parseo de API_TOKENS,
login con JWT, contraseñas incorrectas y límite, JWT falsificado o vencido,
consentimiento (5 rechazos, se guarda en UTC, el mismo evento no se repite),
historial con retiro y nueva aceptación, worker (toma, resultado, estados),
worker con partido + descarga de archivos + análisis de 100 KB por multipart
+ el entrenador lo lee y otro no, completar dos veces no crea otro trabajo,
JWT_SECRET corto no arranca.

Scripts npm: start, dev, test, user:add, db:up, db:down.
Escribe api/README.md con el contrato (esta misma tabla) y cómo correr todo.
```

<a id="gitignore"></a>
## `.gitignore` de la raíz

```
.env
node_modules/
api/storage/
api/php/dist/
worker/.local/
LOCAL_DATA/
data/
*.mp4
android_native/keystore.properties
*.jks
.gradle/
build/
local.properties
outputs/
```

## Trampas conocidas

- **Fechas de prueba en el futuro:** el servidor rechaza aceptaciones más de 1 día adelante; en las pruebas usa fechas pasadas fijas (no "hoy + algo").
- **MySQL compartido entre pruebas:** los eventos de consentimiento de una prueba afectan a otra; usa horas anteriores a la aceptación por omisión para los retiros, y borra todo al final (`DELETE` de `consent_events`, `clips`, `matches` y usuarios de prueba).
- **`TIMESTAMP` vs `DATETIME`:** guarda las horas del teléfono en UTC como `'YYYY-MM-DD HH:MM:SS'` y lee con `timezone: 'Z'`, o las pruebas fallan según la zona de la máquina.
- **Partes en orden:** WorkManager puede reintentar una parte que sí llegó (se cortó la respuesta). El 409 con `expected_offset` es la forma de resincronizar, no un error para el usuario.
- **Un `.partial` huérfano** (la app cambió de perfil y regeneró los chunks): `POST /clips` con otro `chunk_count` o `chunk_seconds` descarta los chunks y los `.partial`.
- **El consentimiento desde el inicio como historial de eventos** (no "una fila por versión"): la primera construcción lo hizo así y luego necesitó migrar.

## Criterios de aceptación

- [ ] `npm test` y `TEST_MYSQL=1 npm test` pasan (17 pruebas o más).
- [ ] `curl` contra `npm start`: login, registro, un chunk de 20 MB en 3 partes, cierre, complete, `/internal/jobs/next` y resultado.
