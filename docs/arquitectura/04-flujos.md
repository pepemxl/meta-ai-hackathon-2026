# Flujos de punta a punta

> **Antecedente.** Este documento viene del diseño y del prototipo previos al hackathon, que tenían un respaldo local (detector YOLOv8n, tracker propio e insights por reglas). La versión que se construye **no tiene respaldo**: solo SAM 3.1 y Muse Spark, y solo dependencias con licencia permisiva. Donde este texto diga otra cosa, mandan [prompts/00-contexto.md](../prompts/00-contexto.md) y el prompt de cada etapa.

Diagramas de secuencia de lo que pasa entre el entrenador, la app, la API y el worker ([06-pipeline-analisis.md](06-pipeline-analisis.md)). Lo marcado como *planeado* todavía no existe.

## 1. Iniciar sesión

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant A as App (Ajustes)
    participant K as Keystore (SecretBox)
    participant API as API
    participant DB as MySQL

    E->>A: dirección del servidor, correo, contraseña
    A->>API: POST /auth/login {email, password}
    API->>DB: fallos recientes por correo / IP
    alt demasiados intentos
        API-->>A: 429 too many attempts (Retry-After)
    else credenciales incorrectas
        API->>DB: registra el fallo
        API-->>A: 401 wrong email or password
    else correctas
        API->>DB: borra los fallos del correo
        API-->>A: 200 {token, email, name}
        A->>K: cifra el token (AES-256-GCM)
        A->>A: guarda en SharedPreferences
        A-->>E: "Sesión iniciada como …"
    end
```

## 2. Grabar y preparar

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant R as RecordScreen (CameraX)
    participant VM as CoachViewModel
    participant L as library.json
    participant CW as ChunkWorker (Media3)

    E->>R: grabar jugada
    R->>VM: reserveClip() → ruta match_NNNN/clip_NNNN.mp4
    R->>R: graba MP4 1080p/30 fps
    R->>VM: onRecorded()
    VM->>VM: lee metadatos (duración, fps, códecs)
    VM->>L: commit(): clip nuevo, estado local
    Note over E,CW: Al enviar (o con "Generar versión" en la vista técnica)
    VM->>CW: cadena única clip-ID
    loop por cada minuto del video (1 s de traslape)
        CW->>CW: recorta y convierte a 10 fps, máx. 720p, sin audio
        CW->>CW: lee el tiempo de cada frame del chunk
        CW->>CW: escribe video_part_NNN.yaml (mapa al original + sha256)
        CW->>CW: reescribe chunks.yaml
    end
    CW-->>VM: ProxyInfo (chunks, frames, tamaño)
    VM->>L: commit(): clip.proxy
```

## 3. Enviar a análisis (subida reanudable)

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant VM as CoachViewModel
    participant UW as UploadWorker
    participant API as API
    participant FS as storage/
    participant DB as MySQL

    E->>VM: Enviar a análisis
    alt sin consentimiento vigente
        VM-->>E: ConsentDialog (ver flujo 4)
    end
    VM->>UW: encola tras ChunkWorker (con red, backoff)
    UW->>UW: lee la sesión cifrada
    UW->>API: POST /clips {clip, match, consent, analysis: {pitch_mode}}
    API->>DB: ¿retiro posterior a la aceptación?
    alt sin consentimiento o retirado
        API-->>UW: 428 consent required / consent withdrawn
        UW-->>VM: falla sin reintentar
    else ok
        API->>DB: upsert partido y clip, registra la aceptación y la liga al clip
        API-->>UW: {clip_id, received: [{number, sha256}]}
    end
    opt el entrenador marcó puntos de la cancha (y no eligió "No calcular")
        UW->>API: POST /clips/:id/calibration {pitch, frames: [{t_ms, points}]}
        API->>DB: clips.calibration
        Note over UW,API: Si la rechaza (400), el video se envía igual, sin metros
    end
    loop chunks que el servidor no tiene con ese sha256
        UW->>API: PUT /clips/:id/chunks/:n (video + yaml + sha256)
        API->>API: sha256 del archivo = campo = YAML
        alt no coincide
            API-->>UW: 422 sha256 mismatch (se reintenta)
        else coincide
            API->>FS: clip_NNNNNN/video_part_NNN.mp4 + .yaml
            API->>DB: clip_chunks, estado uploading
            API-->>UW: {number, sha256, size_bytes}
        end
        UW-->>VM: progreso (bytes enviados)
    end
    UW->>API: POST /clips/:id/complete (chunks.yaml)
    alt faltan chunks
        API-->>UW: 409 {missing}
    else completo
        API->>FS: chunks.yaml
        API->>DB: crea el trabajo (o reutiliza el abierto), clip queued
        API-->>UW: {status: queued, job_id}
    end
    UW-->>VM: remoteId, estado
    VM-->>E: "Enviado ✓ · En cola para el análisis"
```

Si la red se corta a la mitad, WorkManager reintenta; el `POST /clips` siguiente dice qué chunks ya llegaron y solo se suben los que faltan.

## 4. Consentimiento: aceptar y retirar

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant VM as CoachViewModel
    participant CW as ConsentWorker
    participant UW as UploadWorker
    participant API as API
    participant DB as consent_events

    E->>VM: Enviar (sin consentimiento vigente)
    VM-->>E: ConsentDialog (4 casillas, enlaces a /legal/)
    E->>VM: Aceptar y enviar
    VM->>VM: guarda versión + hora (ISO, UTC)
    VM->>CW: accept (cadena "consent", en orden)
    CW->>API: POST /consent {action: accept, version, at, app_version}
    API->>DB: INSERT IGNORE (mismo evento = una fila)
    VM->>UW: envía el clip con consent {version, accepted_at}
    UW->>API: POST /clips (registra la misma aceptación y liga el clip)

    Note over E,DB: Más tarde, en Ajustes
    E->>VM: Retirar consentimiento
    VM->>CW: withdraw
    VM->>UW: cancela las subidas que esperaban
    VM->>VM: borra la aceptación local
    CW->>API: POST /consent {action: withdraw, …}
    API->>DB: guarda el retiro
    Note over UW,API: Una subida vieja que llegue después recibe 428 consent withdrawn
```

`ConsentWorker` espera a tener red y, si la sesión venció, a que el entrenador vuelva a iniciar sesión. El historial se consulta con `GET /consent`.

## 5. Cola de análisis

```mermaid
sequenceDiagram
    participant W as Worker
    participant API as API
    participant DB as MySQL
    participant FS as storage/
    participant M as Meta Model API

    loop cada pocos segundos
        W->>API: GET /internal/jobs/next (Bearer WORKER_CALLBACK_SECRET)
        API->>DB: regresa a la cola los running viejos (PHP)
        API->>DB: toma el queued más viejo → running
        alt no hay
            API-->>W: 204
        else hay
            API->>DB: clip segmenting
            API-->>W: {job_id, clip: {id, chunk_count, files}, match: {equipos, colores}, options: {pitch_mode}, calibration}
        end
    end
    loop cada archivo del clip
        W->>API: GET /internal/clips/:id/files/:nombre
        API->>FS: lee el archivo (lista blanca de nombres)
        API-->>W: mp4 / yaml
    end
    W->>M: SAM 3.1 por concepto
    M-->>W: tracks
    W->>W: cuadros que cuentan, balón, cancha en metros (con los puntos), métricas en la imagen y en metros
    W->>M: Muse Spark (o reglas) con métricas sin nombres
    M-->>W: insights
    W->>API: POST /internal/jobs/:id/result (multipart: status + analysis.json)
    API->>DB: clip_analyses, trabajo done/failed, clip ready/failed
    Note over W,API: Si algo falla, status failed y el error. La carpeta del trabajo se conserva
```

Probado con el worker y la API PHP en Docker. El entrenador lee el resultado con `GET /clips/:id/analysis` (flujo 6).

## 6. Estado del análisis en la app

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant CD as ClipDetailScreen
    participant VM as CoachViewModel
    participant API as API

    E->>CD: abre un clip enviado
    CD->>VM: refreshRemoteStatus(clip)
    VM->>API: GET /clips/:remoteId
    API-->>VM: {status: queued | segmenting | ready | failed}
    VM->>VM: actualiza clip.status (si cambió)
    VM-->>CD: "En cola", "Analizando…", "Análisis listo" o "El análisis falló"
```

Si la consulta falla, el clip conserva el último estado conocido, sin mostrar error.

Con el estado `ready`, "Ver análisis" abre `ClipAnalysisScreen`: muestra al instante la copia guardada del análisis y pide `GET /clips/:id/analysis` para tenerla al día (`ClipAnalysis.parse`, que también lee el bloque en metros).

## 8. Marcar la cancha

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant PT as PitchTouchesScreen
    participant H as Homography (vista previa)
    participant VM as CoachViewModel
    participant L as library.json

    E->>PT: "Marcar puntos de la cancha" (con "Calcular la cancha" encendido)
    E->>PT: elige un cuadro con la barra
    loop cada punto
        E->>PT: toca un punto de la imagen
        E->>PT: elige cuál es en el dibujo de la cancha (11 o 7)
    end
    PT->>H: con 4 o más puntos, ajusta la homografía
    H-->>PT: las líneas de la cancha en violeta sobre el cuadro
    Note over E,PT: Si no caen sobre las de verdad, algún punto tiene el nombre equivocado; con 4 exactos se pide un quinto
    PT->>VM: saveTouches(clip, puntos)
    VM->>L: commit(): clip.touches
```

Los puntos viajan con el envío (flujo 3). En el worker, la homografía del cuadro marcado se lleva a los demás cuadros de su toma siguiendo el pasto, y los pies de cada jugador pasan a metros ([mejora 04](../mejoras/04-homografia-por-cuadro.md)).

## 7. Reporte de análisis y estadísticas

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant MS as MatchStatsScreen
    participant VM as CoachViewModel
    participant P as AnalysisReportParser
    participant L as library.json

    E->>MS: Importar reporte (JSON)
    MS->>VM: importReport(matchId, uri)
    VM->>P: parse
    alt inválido
        P-->>VM: error
        VM-->>E: "Reporte inválido: …"
    else válido
        P-->>VM: AnalysisReport + stats por dorsal
        VM->>VM: liga por dorsal. Un dorsal nuevo se agrega al plantel y las claves de stat desconocidas se ignoran
        VM->>L: commit(): match.analysis, PlayerMatchStats.analysis
        VM-->>E: "Reporte recibido: N jugadores"
    end
    E->>MS: corrige un valor (StatEditor)
    MS->>VM: setStat(…) → PlayerMatchStats.coach
```

*Planeado:* el reporte llegará por la API en lugar de un archivo (`AnalysisReport.via = "api"`), cuando los tracks se liguen a dorsales con el etiquetado en la app.
