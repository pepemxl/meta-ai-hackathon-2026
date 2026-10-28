# Arquitectura

> **Antecedente.** Este documento viene del diseño y del prototipo previos al hackathon, que tenían un respaldo local (detector YOLOv8n, tracker propio e insights por reglas). La versión que se construye **no tiene respaldo**: solo SAM 3.1 y Muse Spark, y solo dependencias con licencia permisiva. Donde este texto diga otra cosa, mandan [prompts/00-contexto.md](prompts/00-contexto.md) y el prompt de cada etapa.

> Diseño original del hackathon. Lo que ya está construido, con diagramas de cada pieza y de los flujos, está en [arquitectura/](arquitectura/README.md).

## Vista general

```
┌──────────────────┐  1. grabar / subir   ┌─────────────────────────┐
│  App Android     │ ───────────────────► │ API PP-Coach-Tracker          │
│  (Kotlin/Compose)│ ◄─────────────────── │ (Cloud Provider, PHP o Node) │
│  - grabar        │  2. estado, tracks,  │ - auth, partidos, clips │
│  - calibrar      │     insights         │ - jobs + MySQL          │
│  - etiquetar     │                      │ - almacenamiento video  │
│  - ver insights  │                      └──────────┬──────────────┘
└──────────────────┘                                 │ 3. encola job (webhook)
                                                     ▼
                                          ┌─────────────────────────┐
                                          │ Worker de IA (Python)   │
                                          │ Modal o VPS Cloud Provider   │
                                          │ - ffmpeg (fps, faststart)│
                                          │ - SAM 3.1 (Meta API)    │
                                          │ - homografía (OpenCV)   │
                                          │ - métricas tácticas     │
                                          │ - Muse Spark (Meta API) │
                                          └──────────┬──────────────┘
                                                     │ 4. callback con resultados
                                                     ▼
                                               API → MySQL
```

### ¿Por qué un worker separado?

El hosting compartido de Cloud Provider está bien para una API PHP/Node con MySQL, pero **no para procesos largos** como ffmpeg, OpenCV o leer streams de SAM durante minutos. Opciones:

1. **Modal** (recomendado si nos dan los $100 en créditos): una función Python serverless que la API invoca por webhook. No hay que administrar servidores.
2. **VPS de Cloud Provider** (KVM): Python, ffmpeg y un cron o cola que consulta la tabla `jobs`. Sirve si ya lo tenemos o si Modal no está disponible.
3. **Plan C para el demo:** un script local que consulta la API (`GET /jobs?status=queued`). Es feo pero funciona.

El modelo pesado (SAM 3.1) corre **hospedado por Meta**, así que el worker no necesita GPU. Si la Meta API falla, el worker baja por una escalera de respaldo (SAM 3 propio, tracker local con OpenCV, cache); ver el plan de respaldo del prototipo previo, que no aplica a esta versión.

## Componentes

### 1. App Android

La app se llama **pp-coach-tracker** y vive en `android_native/`. Plan por versiones en [07-plan-android.md](07-plan-android.md).


- **Stack:** Kotlin, Jetpack Compose, CameraX (grabación), Media3/ExoPlayer (reproducción), Retrofit + OkHttp, WorkManager (subida en segundo plano y reanudable), Room (cola local).
- **Pantallas:**
  1. **Partidos:** lista y alta (equipos, colores de camiseta, fecha).
  2. **Grabar jugada:** CameraX a 1080p/30fps con guía para "teléfono fijo en tripié, ver la cancha completa o media cancha". Clips cortos (de 15 s a 2 min).
  3. **Subidas:** progreso, reintentos y estado del job (`uploaded → queued → segmenting → analyzing → ready / failed`).
  4. **Calibrar cancha:** en el primer frame, el usuario toca 4 o más puntos conocidos (esquinas del área, círculo central, intersección de la media cancha) y elige a qué punto del plano corresponde cada uno. Con eso se calcula la homografía.
  5. **Etiquetar jugadores (post-tagging):** video con cajas o máscaras dibujadas encima. Al tocar un track se le asigna dorsal, nombre y posición, y se pueden fusionar dos tracks del mismo jugador.
  6. **Insights:** minimapa 2D animado, heatmap por jugador o equipo, forma del equipo, lista de insights de Muse Spark con botón "ver en el video" que salta al timestamp.
- **Subida:** multipart por chunks (por ejemplo, de 5 MB) con WorkManager. Se puede comprimir antes con Media3 Transformer a 720p para ahorrar datos y almacenamiento.

### 2. API (Cloud Provider)

- **Stack:** depende de lo que dominemos mejor y de lo que soporte el plan contratado:
  - **PHP (Laravel o Slim) + MySQL:** funciona en cualquier plan de Cloud Provider.
  - **Node.js (Express o Fastify) + MySQL:** requiere un plan con soporte para Node.js.
- **Auth:** JWT simple (un entrenador = una cuenta).
- **Endpoints (MVP):**

| Método | Ruta | Uso |
|---|---|---|
| POST | `/auth/login` | Token |
| GET/POST | `/matches` | Listar y crear partidos |
| POST | `/matches/{id}/clips` | Registrar un clip (metadatos) → `clip_id` y URL o ruta de subida |
| PUT | `/clips/{id}/upload` | Subida por chunks |
| POST | `/clips/{id}/calibration` | Puntos de homografía |
| POST | `/clips/{id}/process` | Encola el job |
| GET | `/clips/{id}` | Estado y resultados |
| GET | `/clips/{id}/tracks` | Tracks (cajas por frame, posiciones en cancha) |
| PATCH | `/tracks/{id}` | Post-tagging (jugador, dorsal, merge) |
| POST | `/clips/{id}/analyze` | Volver a correr Muse Spark después de etiquetar |
| GET | `/clips/{id}/insights` | Insights |
| POST | `/internal/jobs/{id}/result` | Callback del worker (con secreto compartido) |

- **Almacenamiento de video:** disco de Cloud Provider para el MVP, con un límite por clip. Si el espacio no alcanza, usar un bucket compatible con S3.

### 3. Worker de IA (Python)

El pipeline de cada clip:

1. **Preprocesar:** la app ya sube una **versión de 10 fps** (máx. 720p, H.264, sin audio) generada en el teléfono, así que se manda un tercio de los frames y mucho menos peso ([07-plan-android.md](07-plan-android.md)). El worker igual pasa el archivo por `ffmpeg` (MP4/H.264 con `+faststart`, 10 fps) para normalizar lo que llegue por otra vía y recorta a un máximo de 15,000 frames.
2. **Subir a Meta:** `POST /v1/files` con `purpose=user_data` → `file_id`.
3. **Segmentar y trackear con SAM 3.1:** una llamada por concepto, porque **SAM 3.1 sigue como máximo 16 objetos por frame** y una cancha completa tiene 22 jugadores, árbitros y el balón.
   - `"player in red jersey"` (equipo A, ≤11)
   - `"player in white jersey"` (equipo B, ≤11)
   - `"soccer ball"`
   - opcionales: `"goalkeeper"`, `"referee"`
   - Los prompts se arman con los colores que registró el entrenador al crear el partido.
4. **Parsear la salida:** stream SSE con líneas `<Nf>` + ordinal + `<|box;...|>` + `<|mask;...|>`. Por cada frame y objeto se guarda la caja y el **punto de pie** (centro inferior de la caja o punto más bajo de la máscara). Las máscaras completas no se guardan en la base de datos: solo cajas y, si acaso, un PNG de overlay por frame clave.
5. **Homografía:** `cv2.findHomography` con los puntos de calibración. Los puntos de pie se proyectan a coordenadas de cancha en metros (105×68). Se suavizan con Kalman o media móvil para quitar jitter.
6. **Métricas** (con numpy y shapely):
   - Heatmap por jugador y por equipo.
   - Centroide, ancho y profundidad del bloque, y altura de la línea defensiva.
   - Distancias entre líneas y huecos (espacios > X m entre defensores).
   - Presión: jugadores rivales a menos de 5 m del balón y tiempo hasta presionar.
   - Velocidades y distancia recorrida (aproximadas).
   - Eventos candidatos: pérdidas y recuperaciones (cambio de equipo más cercano al balón), balón en el último tercio.
7. **Muse Spark:** se le manda un JSON compacto con métricas, eventos (con timestamps) y etiquetas de jugadores (solo `player_id`, dorsal y posición, nunca nombres; ver [10-privacidad-jugadores.md](10-privacidad-jugadores.md)), y se pide **salida estructurada**: lista de insights `{titulo, descripcion, jugadores, t_inicio, t_fin, severidad, recomendacion}` más un resumen para el entrenador en español.
   - Opcional: mandarle también el video o frames clave (Muse Spark acepta video) para comentarios cualitativos.
8. **Callback** a la API con tracks, métricas e insights.

### 4. Meta Model API: lo que necesitamos saber

- Base URL `https://api.meta.ai/v1`, `Authorization: Bearer $MODEL_API_KEY`, compatible con el SDK de OpenAI (`base_url=...`).
- **SAM 3.1:** `model: "sam-3.1"` en `POST /v1/responses`. El contenido lleva `input_text` (frase corta) + `input_video` (`file_id` o `video_url`).
  - Metadatos útiles: `include_confidence: "true"` y `mask_encoding: "one_bit"` (más ligero).
  - Límites: MP4/MOV con H.264/H.265, ≤15,000 frames, ≤16 objetos por frame.
  - Costo: **$0.20 por 1,000 frames de video.** Un clip de 2 min a 10 fps tiene 1,200 frames; con 3 prompts son 3,600 frames, unos **$0.72**.
- **Muse Spark:** `model: "muse-spark-1.3"`, usando las APIs Responses o Chat Completions con salida estructurada.

## Modelo de datos (MySQL)

```
users(id, email, password_hash, name)
teams(id, user_id, name, jersey_color)
players(id, team_id, number, position, display_name NULL,
        consent_status)   -- ver 10-privacidad-jugadores.md
matches(id, user_id, home_team_id, away_team_id, date, venue)
clips(id, match_id, file_path, duration_s, fps, width, height,
      status, error, created_at)
calibrations(id, clip_id, image_points_json, pitch_points_json, homography_json)
  -- hecho como clips.calibration (JSON con los puntos, api/README.md); la
  -- homografía la calcula el worker en cada cuadro (docs/mejoras/04)
jobs(id, clip_id, type[segment|analyze], status, started_at, finished_at,
     cost_frames, log)
tracks(id, clip_id, prompt, ordinal, team_id NULL, player_id NULL,
       merged_into NULL)
track_points(track_id, frame, t_ms, x1,y1,x2,y2, foot_x, foot_y,
             pitch_x, pitch_y, conf)
metrics(id, clip_id, scope[team|player], ref_id, key, value_json)
insights(id, clip_id, title, body, players_json, t_start_ms, t_end_ms,
         severity, recommendation)
```

`track_points` puede crecer mucho (1,200 frames × ~23 objetos ≈ 28k filas por clip). Para el MVP está bien. Si se vuelve pesado, se guarda como un JSON comprimido por track.

## Decisiones y limitaciones del MVP

- **Cámara fija en tripié.** Con cámara en movimiento la homografía cambia en cada frame. Eso queda como *stretch* (detección automática de líneas o keypoints de cancha).
- **Calibración manual** con 4 o más puntos en la app. Es lo más rápido y confiable para la demo.
- **Identidad dentro del clip, no entre clips.** SAM preserva la identidad dentro de una llamada. Para unir identidades entre clips está el post-tagging manual.
- **El color de camiseta separa a los equipos** gracias al prompt de texto, sin entrenar un clasificador.
- **Stretch goals:** fusión multicámara, homografía automática, re-ID entre clips, detección de eventos con Muse Spark viendo el video, y exportar el reporte a PDF.
