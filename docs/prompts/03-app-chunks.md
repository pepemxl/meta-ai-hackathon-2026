# 03 · Versión para análisis: 10 fps en chunks con YAML (v0.2.2–v0.2.3)

**Lee antes:** [12-videos-largos-chunks.md](../12-videos-largos-chunks.md) (completo: el formato del YAML es contrato con la API y el worker), [arquitectura/02-app-android.md](../arquitectura/02-app-android.md#trabajos-en-segundo-plano).

```text
Agrega a pp-coach-tracker la "versión para análisis" de cada clip, según
docs/12-videos-largos-chunks.md.

Qué genera (en la caché: cacheDir/proxies/match_NNNN/clip_NNNN/):
- video_part_NNN.mp4: 10 fps, alto máximo 720p, H.264 hasta 1.5 Mbps, SIN
  audio. Chunks de S segundos con T ms de traslape (el chunk n>1 empieza T
  antes del final del anterior), según el perfil del clip:
    - dev: S = 60, T = 2000 (por omisión en el build debug);
    - demo: S = 300, T = 3000, T elegible de 2000 a 5000 (por omisión en
      el release).
  data/ChunkProfile.kt con esos valores y su validación. El perfil se guarda
  en el clip al preparar su versión; si cambia, se descartan los chunks y se
  generan de nuevo. Si lo que queda después del último corte es menor que
  2·T, se agrega al chunk anterior. Un clip corto también es un chunk.
  NADA en el código supone 60 s ni un traslape fijo: todo sale del perfil.
- video_part_NNN.yaml, escrito a mano (sin librería), compatible con
  cv::FileStorage de OpenCV:

  %YAML:1.0
  ---
  version: 1
  clip:
    id: 3
    match_id: 1
    source_file: "match_0001/clip_0003.mp4"
    source_fps: 29.97
    source_frames: 14745
    duration_ms: 492012
  chunk:
    number: 2
    count: 2
    file: "video_part_002.mp4"
    seconds: 300
    start_ms: 297000
    end_ms: 492012
    overlap_ms: 3000
  encoding:
    fps: 10
    width: 1280
    height: 720
    codec: "h264"
    bitrate: 1500000
    audio: 0
  frames:
    count: 1951
    overlap_frames: 30
    # [frame del chunk, frame del original, ms en el original]
    map:
      - [0, 8901, 297000]
      - [1, 8904, 297100]
  sha256: "<sha256 del .mp4>"

  El map se escribe DESPUÉS de generar el chunk, leyendo el tiempo de cada
  frame del archivo de salida (MediaExtractor) y emparejándolo con el frame
  más cercano por tiempo del original. Lo medido, no lo planeado.
- chunks.yaml (índice), reescrito después de cada chunk:

  %YAML:1.0
  ---
  version: 1
  clip_id: 3
  profile: "demo"
  chunk_seconds: 300
  overlap_ms: 3000
  count: 2
  complete: 1
  chunks:
    - { number: 1, file: "video_part_001.mp4", start_ms: 0, end_ms: 300000, frames: 3000 }
    - { number: 2, file: "video_part_002.mp4", start_ms: 297000, end_ms: 492012, frames: 1951 }

Cómo: analysis/ChunkWorker.kt con WorkManager y Media3 Transformer (recorte +
conversión por chunk). Si se interrumpe, al reanudar salta los chunks cuyo
YAML ya existe y es válido, y rehace solo el que faltaba (alinea el siguiente
leyendo el map del anterior). Una cadena única de WorkManager por clip
("clip-<id>").

data/Proxy.kt y data/Chunks.kt (Kotlin puro): cortes, mapa de frames, YAML,
costo estimado en SAM 3.1 (US$0.20 por 1,000 frames, 3 prompts por defecto,
máximo 15,000 frames por request).

UI:
- Vista simple por omisión: en el detalle del clip, solo partido, fecha,
  duración y tamaño, y un botón "Enviar a análisis" con barra en dos pasos
  (preparar 0–50 %, enviar 50–100 %), con el progreso dentro del chunk
  ("Chunk 1 de 2 · 45 %": un chunk de 5 min tarda). Sin servidor configurado, "Enviar"
  comparte los chunks con la hoja de Compartir.
- Interruptor "Mostrar detalles técnicos" en Ajustes: chunks, frames, YAML,
  costo en SAM, fps, resolución, archivo, formato de grabación, borrar la
  versión y espacio de las copias temporales. Ahí mismo: "Chunks de 1 min
  (desarrollo)" o "de 5 min (demo)" y el traslape (2 a 5 s).

Pruebas JVM: cortes con traslape en los dos perfiles y con traslapes de 2 y
5 s, último chunk corto agregado al anterior, perfil inválido, mapa de
frames con fps variable, YAML (incluido leerlo de vuelta), costo.

Agrega experiments/check_chunks.py: lee una carpeta de chunks sacada del
teléfono y verifica que PyYAML y OpenCV leen los YAML, que el map es
consistente con el video y que los frames del traslape coinciden.
```

## Trampas conocidas

- **PyYAML rechaza `%YAML:1.0`** (el encabezado de OpenCV): quien lo lea en Python quita la primera línea antes de parsear. Mantener el encabezado, porque OpenCV sí lo exige.
- **Transformer** hay que crearlo, arrancarlo y consultar el progreso en el hilo principal (Looper).
- **El último chunk** suele ser más corto; el `end_ms` es el real, no `start + chunk_seconds`.
- **Un chunk de 5 min tarda en generarse:** si el sistema mata la app, se rehace ese chunk completo. Medir en un teléfono real cuánto tarda y cuánta batería gasta.

## Criterios de aceptación

- [ ] Perfil `dev`, clip de 1:15 → 2 chunks (600 + ~170 frames), el traslape con 20 frames idénticos del original, `sha256` correcto, `check_chunks.py` pasa.
- [ ] Perfil `demo`, clip de 8:12 → 2 chunks (3,000 + ~1,950 frames), traslape de 30 frames idénticos; con traslape de 5 s, 50 frames.
- [ ] Clip de 5:04 en perfil `demo` → 1 chunk (el resto de 4 s se agrega).
- [ ] Borrar el chunk 2 y volver a preparar: solo se rehace ese.
- [ ] Clip de 21 s: ~217 frames contra ~651, sin audio (verificado con ffprobe).
