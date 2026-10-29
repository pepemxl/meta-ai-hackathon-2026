# Videos largos en chunks: 1 minuto en desarrollo, 5 minutos en el demo

La versión para análisis de un clip es una copia de 10 fps partida en **chunks**, cada uno con un **YAML** que dice exactamente qué frames del original contiene. Este documento es el contrato entre la app, la API y el worker.

**Decisión (27 de octubre de 2026):**

| Perfil | Duración del chunk | Traslape | Para qué |
|---|---|---|---|
| **Desarrollo** (`dev`) | 60 s | 2 s | Iterar rápido con el [simulador de SAM](13-entorno-desarrollo.md), que corre en CPU, y con clips cortos |
| **Demo** (`demo`) | 300 s (5 min) | 3 s por omisión, de 2 a 5 s | El análisis real con SAM 3.1 que se muestra en el video y en la final |

Los dos son el mismo formato con otros parámetros: `chunk_seconds` y `overlap_ms` viajan en cada YAML, en `chunks.yaml` y en `POST /clips`. **Nadie supone 60 s ni 1 s de traslape**: la app, la API y el worker leen los valores del clip.

## Por qué chunks

Un solo archivo de 10 fps no sirve para un tiempo o un partido:

- **Límite de SAM 3.1:** 15,000 frames por video, o sea 25 minutos a 10 fps. Un tiempo de 45 minutos no entra.
- **Reintentos caros:** si SAM falla al minuto 40, se repetiría el video entero.
- **Subida frágil:** un archivo de 90 minutos ocupa cerca de 1 GB aun a 10 fps.

## Por qué 5 minutos en el demo

El prototipo usaba chunks de 1 minuto. Para el análisis real se pasa a 5 minutos:

1. **Menos uniones entre chunks.** SAM mantiene la identidad de cada jugador solo dentro de una llamada. Cada corte es un lugar donde el jugador 3 puede volverse el 7. Con 5 minutos hay cinco veces menos cortes, y las métricas por track (distancia, posición media, mapa de calor) salen más limpias.
2. **Más contexto para SAM.** Un jugador que sale de cuadro y vuelve dentro del mismo chunk puede conservar su id; en un chunk de 1 minuto es más probable que el regreso caiga en otro chunk.
3. **Sigue lejos del límite.** Un chunk de 5 minutos tiene unos 3,000 frames (más 20 a 50 del traslape), una quinta parte de los 15,000.
4. **Menos llamadas por clip.** Un tiempo de 45 minutos son 9 chunks en lugar de 45, con menos sobrecarga de subida de archivos y de streams.

Lo que cuesta, y cómo se resuelve:

| Costo de los chunks largos | Solución |
|---|---|
| Un chunk de 5 min pesa unos 56 MB a 720p (1.5 Mbps) y unos 112 MB a 1080p (3 Mbps): no pasa por el límite de subida del hosting compartido (32 MB por petición) ni por el del CDN | **La subida va en partes de 8 MB** con su posición dentro del chunk (abajo). El chunk es la unidad de análisis; la parte es la unidad de transporte. Un corte de red repite como mucho 8 MB |
| Si SAM falla en un chunk, se repiten 5 minutos (unos US$1.82 con 3 conceptos) | Reintentos solo de errores temporales; caché por chunk; probar con el perfil de desarrollo y clips cortos antes de gastar |
| Generar un chunk en el teléfono tarda más; si el sistema mata la app, se pierde el chunk a medias | El progreso se muestra dentro del chunk ("Chunk 1 de 2 · 45 %") y se reanuda en el chunk que faltaba. Medirlo en un teléfono real (tarea 9) |
| En desarrollo, el simulador procesa en CPU y un chunk de 5 min tardaría mucho | Por eso el perfil de desarrollo usa 1 minuto |

## Por qué 2 a 5 segundos de traslape

El traslape es la única forma confiable de unir los tracks de dos chunks: cada chunk *n*>1 repite los últimos segundos del anterior, con **los mismos frames del original**, y el worker empareja los tracks en esos frames por IoU de las cajas.

- **1 s (lo del prototipo) es poco:** son 10 frames. Una oclusión o un jugador que cruza a otro en ese segundo deja la unión sin evidencia.
- **2 s es el mínimo** (20 frames) y basta en desarrollo, donde solo se prueba que la unión funciona.
- **3 s por omisión en el demo** (30 frames): suficiente para sobrevivir a una oclusión corta, y cuesta un 1 % más de frames con chunks de 5 min.
- **Hasta 5 s** (50 frames, 1.7 % más) si en las pruebas con SAM las uniones fallan con jugadores rápidos o cámara en movimiento.

Fuera de 2 a 5 s, la API responde 400.

## Archivos

Numeración desde 001 y el mismo nombre base para el video y sus datos.

```
cache/proxies/match_0001/clip_0003/
├── chunks.yaml            # índice: cuántos chunks, cuáles están completos
├── video_part_001.mp4     # 0:00–5:00 a 10 fps
├── video_part_001.yaml    # qué frames del original contiene
├── video_part_002.mp4     # 4:57–8:12 (3 s de traslape)
├── video_part_002.yaml
└── …
```

Un clip corto también se parte: queda con un solo chunk. Así la subida y el worker siempre trabajan con chunks.

## YAML por chunk

Ejemplo del perfil de demo: clip de 8:12, segundo chunk.

```yaml
%YAML:1.0
---
version: 1
clip:
  id: 3
  match_id: 1
  source_file: "match_0001/clip_0003.mp4"
  source_fps: 29.97          # medido del original
  source_frames: 14745       # frames del original
  duration_ms: 492012
chunk:
  number: 2                  # 1..count
  count: 2
  file: "video_part_002.mp4"
  seconds: 300               # duración planeada de los chunks de este clip
  start_ms: 297000           # inicio en el original (incluye el traslape)
  end_ms: 492012             # fin real; el último chunk suele ser más corto
  overlap_ms: 3000           # cuánto repite del chunk anterior (0 en el primero)
encoding:
  fps: 10
  width: 1280
  height: 720
  codec: "h264"
  bitrate: 1500000
  audio: 0
frames:
  count: 1951
  overlap_frames: 30         # los primeros N frames repiten el final del chunk anterior
  # Una fila por frame del chunk: [frame del chunk, frame del original, ms en el original]
  map:
    - [0, 8901, 297000]
    - [1, 8904, 297100]
    - [2, 8907, 297200]
    # …
pitch:                       # opcional: la máscara de la cancha (prompts/12-mascara-cancha.md)
  method: "hsv_v1"
  grid: [160, 90]
  hue: [35, 85]
  with_pitch: 1702           # cuadros con cancha
  # [frame del chunk, fracción de pasto, polígono de la cancha x1, y1, x2, y2, ... (0..1)]
  frames:
    - [0, 0.6200, [0.0000, 0.4100, 0.4800, 0.3300, 1.0000, 0.3800, 1.0000, 1.0000, 0.0000, 1.0000]]
    - [1, 0.0300, []]        # sin cancha
    # …
sha256: "…"                  # del .mp4, para verificar la subida
```

Decisiones de formato:

- **`%YAML:1.0` y estructuras simples** (mapas y listas de números): es lo que acepta `cv::FileStorage` de OpenCV. PyYAML no acepta ese encabezado: el worker quita la primera línea antes de parsear.
- **`map` explícito**, una fila por frame del chunk, con el **frame más cercano del original por tiempo**. Son unos 3,000 renglones y cerca de 75 KB por chunk de 5 min (15 KB con 1 min). Una regla como "inicio + paso" sería más corta, pero se rompe con frame rate variable.
- **`pitch`** pesa unos 300 KB por chunk de 5 min (100 bytes por cuadro). El YAML viaja en la petición que cierra el chunk, sin el video (abajo), así que su tamaño no compite con las partes.
- **Sin nombres de jugadores** ni datos personales: solo números de frame y tiempos ([10-privacidad-jugadores.md](10-privacidad-jugadores.md)).
- **Escrito a mano por la app**, con un generador mínimo en Kotlin y sin librería de YAML. Las pruebas verifican que PyYAML y `cv::FileStorage` lo leen.

## `chunks.yaml` (índice del clip)

```yaml
%YAML:1.0
---
version: 1
clip_id: 3
profile: "demo"              # dev | demo
chunk_seconds: 300
overlap_ms: 3000
count: 2
complete: 1                  # 1 cuando todos los chunks y sus YAML existen
chunks:
  - { number: 1, file: "video_part_001.mp4", start_ms: 0, end_ms: 300000, frames: 3000 }
  - { number: 2, file: "video_part_002.mp4", start_ms: 297000, end_ms: 492012, frames: 1951 }
```

Sirve para saber de un vistazo si el clip está listo para subir y qué falta reintentar.

## Cómo se generan en la app

`analysis/ChunkWorker.kt`, con un trabajo de WorkManager por clip que procesa sus chunks en orden.

1. Leer la duración y los fps del original, y el perfil del clip (`chunk_seconds`, `overlap_ms`).
2. Calcular los cortes, con `S = chunk_seconds` y `T = overlap_ms`: `[0, S)`, `[S − T, 2S)`, `[2S − T, 3S)`… y el último hasta el final.
   - **Alineación:** antes de generar el chunk *n+1*, su inicio se mueve al primer frame que el chunk *n* conservó dentro del traslape. La caída a 10 fps reinicia su ritmo en cada chunk, y sin esta alineación el traslape queda desfasado por un frame del original (33 ms). Con ella, los dos chunks repiten exactamente los mismos frames.
   - Por eso `start_ms` puede quedar unos milisegundos después de lo planeado y `overlap_ms` un poco por debajo de lo pedido.
   - **Último chunk muy corto:** si lo que queda después del último corte es menor que el doble del traslape, se agrega al chunk anterior (un chunk de 5:04 en lugar de uno de 5:00 y otro de 4 s).
3. Para cada chunk que no esté completo:
   - Exportar con Media3 Transformer un recorte del original (`ClippingConfiguration`), a 10 fps, 720p (o 1080p según Ajustes), sin audio y con tasa de bits fija.
   - Abrir el `.mp4` con `MediaExtractor`, leer el tiempo de cada frame y escribir el `.yaml`.
   - Decodificar el chunk y medir la cancha de cada frame ([etapa 12](prompts/12-mascara-cancha.md)). Si falla, el `.yaml` sale sin `pitch` y el chunk sigue sirviendo.
   - Calcular el `sha256`.
4. Reescribir `chunks.yaml` después de cada chunk.

Un trabajo que se interrumpe continúa en el chunk que faltaba, sin rehacer los anteriores; para alinear el siguiente, la app lee el `map` del YAML del anterior. Si el perfil del clip cambia (de `dev` a `demo`), los chunks existentes se descartan y se generan de nuevo.

**Qué perfil usa la app:** el build debug empieza en `dev` y el release en `demo`. En la vista técnica de Ajustes se puede cambiar ("Chunks de 1 min (desarrollo)" o "de 5 min (demo)") y elegir el traslape (2 a 5 s). El perfil se guarda en cada clip al preparar su versión.

La UI muestra "Chunk 1 de 2 · 45 %". El detalle del clip, en la vista técnica, dice "2 chunks de 5 min · 92 MB · 4,951 frames", con el costo estimado en SAM.

## Cómo los usan la API y el worker

### Subida en partes

El chunk se sube en **partes de hasta 8 MB** (`MAX_PART_BYTES`), cada una con su posición:

1. `PUT /clips/{id}/chunks/{n}/video?offset=<bytes>` con el cuerpo crudo (`application/octet-stream`). La API escribe la parte en un archivo `.partial` solo si `offset` es exactamente lo que ya tiene; si no, responde 409 con `expected_offset` y la app sigue desde ahí. Responde `{received_bytes}`.
2. Cuando llegó todo el video, `PUT /clips/{id}/chunks/{n}` con el `.yaml` y el `sha256` (multipart chico, como antes pero sin el video). La API calcula el `sha256` del archivo armado; si no coincide con el campo ni con el del YAML, borra el `.partial` y responde 422. Si coincide, lo renombra a `video_part_NNN.mp4` y marca el chunk como recibido.
3. `GET /clips/{id}` y `POST /clips` devuelven, además de los chunks recibidos, los bytes parciales de cada chunk a medias, para reanudar después de cerrar la app.

Límites: `MAX_PART_BYTES` 8 MB (por debajo de los 11 MB que se probaron a través del CDN), `MAX_CHUNK_BYTES` 160 MB por chunk armado (5 min a 1080p son unos 112 MB). El cuerpo crudo evita el parser de multipart de PHP y los límites de `upload_max_filesize`.

### Análisis

- **Jobs:** un trabajo por clip, que pide SAM por chunk y por concepto. Un fallo definitivo en un chunk hace fallar el trabajo (sin respaldo local); los temporales se reintentan.
- **Unión de tracks:** en los frames de traslape (los mismos del original en los dos chunks) se emparejan los tracks del chunk *n* y el *n+1* por IoU de las cajas. Una pareja recibe el mismo id global si tiene IoU > 0.5 en al menos la mitad de los frames de traslape en que aparecen los dos (y en 5 o más). Los que no se emparejan quedan como tracks nuevos. El umbral se mide con el visor (etapa 11) y se anota en el README del worker.
- **Coordenadas de tiempo:** todo lo que produce el pipeline (tracks, eventos, insights) se guarda con el frame y el tiempo **del original**, traducidos con el `map`. Así "ver en el video" usa el mismo reloj que el clip del teléfono.
- **Máscara de la cancha:** el worker usa la sección `pitch` del YAML y solo mide la cancha donde no viene.
- **Resultado:** registra `video.chunks`, `video.chunk_seconds` y `video.overlap_ms`, para saber con qué perfil se hizo cada análisis.

## Costo

SAM cobra US$0.20 por cada 1,000 frames y concepto; con 3 conceptos (dos equipos y el balón):

| Video | Perfil | Chunks | Frames con traslape | Costo SAM |
|---|---|---|---|---|
| Jugada de 2 min | dev (1 min, 2 s) | 2 | 1,220 | US$0.73 |
| Jugada de 2 min | demo (5 min, 3 s) | 1 | 1,200 | US$0.72 |
| Clip del demo de 8 min | demo | 2 | 4,830 | US$2.90 |
| Tiempo de 45 min | demo | 9 | 27,240 | US$16.34 |
| Partido de 90 min | demo | 18 | 54,510 | US$32.71 |

En desarrollo el simulador no gasta créditos. Con los $150 conviene analizar jugadas y el clip del demo, no partidos completos, y mostrar el costo antes de enviar (la app lo calcula).

## Tareas

| # | Tarea | Dónde |
|---|---|---|
| 1 | `ChunkPlan` con `chunk_seconds` y `overlap_ms` como parámetros: cortes, alineación, último chunk corto; casos de los dos perfiles | `data/Chunks.kt` (Kotlin puro, con pruebas) |
| 2 | Generador de YAML compatible con OpenCV, con `seconds` y `profile` | `data/Chunks.kt` |
| 3 | `ChunkWorker`: export por chunk, tiempos de los frames, YAML, `chunks.yaml`, `sha256`, reanudar, progreso dentro del chunk | `analysis/ChunkWorker.kt` |
| 4 | UI: "Chunk n de N · %", resumen, costo; perfil y traslape en la vista técnica | Detalle del clip, Ajustes |
| 5 | Subida en partes con `offset` y verificación de `sha256` del chunk armado | API (etapas 4 y 6) y app (etapa 5) |
| 6 | Unión de tracks en el traslape, con el umbral proporcional | Worker (etapa 8) |
| 7 | Traducir resultados a frames del original | Worker (etapa 8) |
| 8 | Medir en un teléfono real: tiempo de generación de un chunk de 5 min, tamaño, batería, calidad del traslape | Prueba de campo |

## Antecedente: prueba del prototipo (1 min y 1 s de traslape)

En el prototipo previo (emulador, clip de 1:15) el formato funcionó así, y sigue valiendo como referencia: 2 chunks (600 y 163 frames, 5.3 y 1.5 MB), el traslape con 10 frames idénticos del original **gracias a la alineación** (sin ella había 11 frames desfasados por uno), el `sha256` verificado, PyYAML y `cv2.FileStorage` leyendo todos los YAML, y la reanudación rehaciendo solo el chunk borrado.
