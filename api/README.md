# API de pp-coach-tracker

Recibe los chunks de 10 fps que sube la app, guarda el consentimiento del entrenador y coordina al worker de análisis. Hay dos implementaciones con **el mismo contrato**: esta en Node (desarrollo y pruebas) y la de PHP en `php/` (producción en https://ppcoachtracker.pepemxl.com).

## Cómo correrla

```bash
cd api
npm install
npm test                      # pruebas en memoria (sin red)

npm run db:up                 # MySQL 8 en Docker (WSL), localhost:13306
TEST_MYSQL=1 npm test         # las mismas pruebas contra MySQL (borran sus datos al final)

npm start                     # http://127.0.0.1:3000 con la config del .env de la raíz
npm run user:add -- coach@example.com "Nombre" 'contraseña-larga'
```

Las variables están en `.env.example` (raíz del repo). La API lee `.env` de la raíz y **no arranca** sin `JWT_SECRET` de 32 caracteres o más.

> **Windows y Hyper-V:** si `13306` cae en un rango reservado (`netsh interface ipv4 show excludedportrange protocol=tcp`), levanta MySQL en otro puerto: `wsl -e env DB_HOST_PORT=23306 docker compose up -d mysql` y pon `DB_PORT=23306` en el `.env` o al correr las pruebas.

| Variable | Para qué | Por omisión |
|---|---|---|
| `JWT_SECRET` | Firma de los JWT (HS256), 32+ caracteres | — (obligatoria) |
| `WORKER_CALLBACK_SECRET` | Bearer del worker en `/internal/*` | vacío: `/internal/*` responde 401 |
| `API_TOKENS` | Tokens fijos `coach_id:token,…` para scripts y pruebas | vacío |
| `REPO` | `mysql` o `memory` | `memory` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | MySQL | `127.0.0.1`, `13306`, `ppcoachtracker`, `ppcoachtracker`, vacío |
| `STORAGE_DIR` | Dónde se guardan los chunks | `api/storage` |
| `HOST`, `PORT` | Dónde escucha | `127.0.0.1`, `3000` |
| `MAX_PART_BYTES` | Tamaño máximo de una parte | 8 MB |
| `MAX_CHUNK_BYTES` | Tamaño máximo de un chunk armado | 160 MB |
| `PP_COACH_TRACKER_REQUIRE_META` | `1`: solo acepta resultados de SAM 3.1 real | `0` |

## Contrato

Todos los errores son `{error: "...", ...extra}`. Un entrenador nunca ve ni toca clips de otro: 404.

Estados del clip: `registered → uploading → queued → segmenting → ready | failed`.

| Método | Ruta | Quién | Qué hace |
|---|---|---|---|
| GET | `/health` | Cualquiera | `{ok, version, db, authenticated, consent_version}` |
| POST | `/auth/login` | Cualquiera | `{email, password}` → `{token, email, name}`. JWT HS256, `sub` = `u<id>`, 30 días. 10 fallos por correo en 15 min → 429 con `Retry-After` |
| POST | `/clips` | Entrenador | Registro idempotente por `(coach_id, client_clip_id)`; ver abajo |
| POST | `/clips/:id/calibration` | Entrenador | Puntos y líneas de la cancha que marcó el entrenador, y los trazos de sus imágenes de referencia (abajo); 409 cuando el worker ya tomó el clip |
| PUT | `/clips/:id/references/:n` | Entrenador | Una imagen de referencia de la cancha: JPEG crudo de hasta 2 MB, `n` de 1 a 6; 400 si no es JPEG, 413 si pasa el tope, 409 cuando el worker ya tomó el clip |
| PUT | `/clips/:id/chunks/:n/video?offset=<bytes>` | Entrenador | Una parte cruda (`application/octet-stream`) de hasta `MAX_PART_BYTES` → `{number, received_bytes}` |
| PUT | `/clips/:id/chunks/:n` | Entrenador | Multipart `yaml` + `sha256`, sin video: cierra el chunk → `{number, sha256, size_bytes}` |
| POST | `/clips/:id/complete` | Entrenador | Multipart `index` (`chunks.yaml`) → como `POST /clips` + `job_id` |
| GET | `/clips/:id` | Entrenador | Estado, `received`, `partial`, `title`, `duration_ms` y `job: {id, status, error}` |
| GET | `/clips/:id/analysis` | Entrenador | `{clip_id, job_id, segmenter, analyst, created_at, result}` o 404 `{error: "analysis not found", status}` |
| POST | `/consent` | Entrenador | `{action: accept\|withdraw, version, at, app_version}` → `{id, action, version, at}` |
| GET | `/consent` | Entrenador | `{consent_version, current: none\|accepted\|withdrawn\|outdated, events: [...]}` |
| GET | `/internal/jobs/next` | Worker | 204, o `{job_id, type, clip: {id, title, duration_ms, chunk_count, chunk_seconds, overlap_ms, dir, index, files, references}, options: {pitch_mode, camera}, calibration, match: {home, away, home_color, away_color, date, venue}}` |
| GET | `/internal/clips/:id/files/:name` | Worker | Solo `chunks.yaml`, `video_part_NNN.(mp4\|yaml)` y `reference_N.jpg`; 404 si no |
| POST | `/internal/jobs/:id/result` | Worker | JSON `{status: done\|failed, error}` o multipart con el archivo `analysis` (JSON) |

### `POST /clips`

```json
{
  "client_clip_id": "…", "title": "…", "duration_ms": 75000,
  "chunk_count": 2, "chunk_seconds": 60, "overlap_ms": 2000,
  "match": {"client_match_id": "…", "home": "…", "away": "…", "home_color": "red", "away_color": "blue", "date": "2026-10-25", "venue": "…"},
  "consent": {"version": "2026-10-27", "accepted_at": "2026-10-27T15:04:05Z", "app_version": "0.1.0"}
}
```

Opcional: `"analysis": {"pitch_mode": "lines" | "no_lines" | "off", "camera": "static" | "moving"}`. `pitch_mode` dice si se calcula la cancha; `camera`, si la cámara queda quieta todo el video (tripié o panorámica: una sola homografía) o se mueve y hace zoom (se sigue cuadro por cuadro). Sin el campo, `null`; otro valor, 400 con `analysis.pitch_mode` o `analysis.camera`. Quedan fijos cuando el worker toma el clip.

Respuesta: `{clip_id, status, chunk_count, chunk_seconds, overlap_ms, pitch_mode, camera, video_deleted, received: [{number, sha256}], partial: [{number, received_bytes}]}`.

- **400** `{error: "invalid fields", fields: [...]}`: `duration_ms` ≤ 6 h, `chunk_count` 1..1000, textos 1..160 (`venue` puede ir vacío), `chunk_seconds` 60..300 (perfiles `dev` = 60 y `demo` = 300), `overlap_ms` 2000..5000.
- **428** `{error: "consent required", consent_version}`: falta el consentimiento, no es la versión vigente (`2026-10-27`) o la fecha es inválida o está más de 1 día en el futuro.
- **428** `{error: "consent withdrawn", consent_version}`: `accepted_at` es anterior o igual al último retiro del entrenador.
- Registra la aceptación como evento y liga el clip a él. El partido es idempotente por `(coach_id, client_match_id)`.
- Si cambia `chunk_count`, `chunk_seconds` u `overlap_ms`, se descartan los chunks y los `.partial`, y el clip vuelve a `registered`.

### `POST /clips/:id/calibration`

```json
{"pitch": {"size": 7, "length_m": 60, "width_m": 40},
 "frames": [{"t_ms": 1200,
             "points": [{"name": "corner_far_left", "x": 0.12, "y": 0.31}, …],
             "lines": [{"name": "touch_near", "points": [{"x": 0.1, "y": 0.8}, …]}, {"name": null, "points": […]}]}],
 "references": [{"n": 1, "source": "photo", "strokes": [{"points": [{"x": 0.2, "y": 0.6}, …]}]},
                {"n": 2, "source": "frame", "t_ms": 4000, "strokes": []}]}
```

`size` 11 o 7; `length_m` 20..130 y `width_m` 15..100 opcionales. De 0 a 20 cuadros, cada uno con `t_ms` o `frame`, de 0 a 40 puntos con nombres de la plantilla (`[a-z0-9_]`, sin repetir) y de 0 a 20 líneas pintadas (al menos un punto o una línea). Una línea es el trazo del dedo sobre una línea de la cancha: de 2 a 64 puntos y el nombre de la línea de la plantilla (`touch_near`, `halfway`, `box_left`…) o `null` cuando el entrenador solo dice "esto es una línea". De 0 a 6 imágenes de referencia (`n` distinto, `source` `photo` o `frame`, `t_ms` opcional) con 0 a 20 trazos; la imagen va aparte en `PUT /clips/:id/references/:n`. Hace falta al menos un cuadro o una referencia, y en total hasta 3000 puntos de trazos. `x`, `y` en 0..1. 400 `{error: "invalid calibration", detail}` si no. Se puede reemplazar mientras el clip está en `registered`, `uploading` o `queued`; después, 409. El worker lo recibe tal cual en `calibration`.

### Subida en partes

1. `PUT /clips/:id/chunks/:n/video?offset=<bytes>`: se escribe al final del `.partial` **solo si `offset` es igual a lo que ya hay**. Si no: **409** `{error: "offset mismatch", expected_offset}`, y la app sigue desde ahí (no es un error para el usuario). **413** si la parte pasa de `MAX_PART_BYTES` o el chunk pasaría de `MAX_CHUNK_BYTES`. **409** si el clip está en análisis (`queued` o `segmenting`).
2. `PUT /clips/:id/chunks/:n`: calcula el `sha256` del `.partial`; si no coincide con el campo **y** con el `sha256:` del YAML → **422** `{error: "sha256 mismatch", expected, got}` y borra el `.partial`. **409** si no hay `.partial`.
3. `POST /clips/:id/complete`: **409** `{error: "missing chunks", missing: [...]}` si falta alguno. Si están todos, crea el trabajo (o reutiliza el abierto) y el clip pasa a `queued`.

### Resultado del worker

- `done` con análisis: debe ser un objeto JSON (400 si no). `segmenter` y `analyst` salen de `result.backends`.
- Con `PP_COACH_TRACKER_REQUIRE_META=1`, un `segmenter` distinto de `meta_sam` → **422** `{error: "simulator result rejected"}` y el clip pasa a `failed`.
- 404 si el trabajo no existe, 409 si no está en `running`.

## Estructura

```
api/
├── db/schema.sql         esquema (Node y PHP)
├── docker-compose.yml    MySQL 8 local
├── src/
│   ├── app.js            createApp({repo, storageDir, tokens, jwtSecret, workerSecret, maxPartBytes, maxChunkBytes, requireMeta})
│   ├── auth.js           scrypt, JWT, API_TOKENS
│   ├── consent.js        versión vigente, validación de fechas, estado
│   ├── storage.js        nombres de archivos, sha256, .partial
│   ├── config.js         variables de entorno y .env de la raíz
│   ├── server.js
│   ├── repo/memory.js    misma interfaz que repo/mysql.js
│   ├── repo/mysql.js
│   └── cli/add-user.js
└── tests/                node:test + supertest (en memoria o TEST_MYSQL=1)
```
