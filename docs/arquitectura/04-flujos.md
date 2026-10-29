# Flujos de punta a punta

Diagramas de secuencia de lo que pasa entre el entrenador, la app, la API y el worker ([06-pipeline-analisis.md](06-pipeline-analisis.md)). Los flujos de la app (1 a 5 y 7) se probaron en el emulador contra la API local en Docker. La cola de análisis (6) se prueba con las pruebas del worker, contra una API falsa y con streams de SAM sintéticos: falta la key de la Meta Model API.

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
        A->>A: guarda el cifrado en SharedPreferences
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
    participant CW as ChunkWorker
    participant PS as PitchSampler

    E->>R: grabar jugada (o importar un video)
    R->>VM: newClipFile() → reserva el id y la ruta match_NNNN/clip_NNNN.mp4
    R->>R: graba MP4 1080p o 720p, 30 fps
    R->>VM: addClip() al terminar
    VM->>VM: lee metadatos (duración, fps, tamaño); tipo de clip por la duración
    VM->>L: commit(): clip nuevo, estado local
    Note over E,CW: Al enviar (o con "Generar versión" en la vista técnica)
    VM->>CW: cadena única clip-ID
    loop cada chunk (1 o 5 min, con traslape)
        CW->>CW: recorta y convierte a 10 fps, máx. 720p, sin audio (Media3)
        CW->>CW: mide el tiempo real de cada cuadro
        CW->>PS: decodifica el chunk y mide la cancha por cuadro
        PS-->>CW: polígono de la cancha por cuadro
        CW->>CW: escribe video_part_NNN.yaml (mapa al original, pitch, sha256)
        CW->>CW: reescribe chunks.yaml
    end
    CW->>L: commit(): chunks, perfil, parte con cancha (pitchShare)
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
        VM-->>E: ConsentDialog (flujo 4)
    end
    alt primer video del partido sin respuesta sobre la cancha
        VM-->>E: PitchDialog: ¿las líneas se ven bien?
    end
    VM->>UW: encola tras ChunkWorker (con red, backoff)
    UW->>UW: lee la sesión cifrada
    UW->>API: POST /clips {clip, match, consent, analysis: {pitch_mode}}
    alt sin consentimiento o retirado
        API-->>UW: 428 consent required / consent withdrawn
        UW-->>VM: falla sin reintentar
    else ok
        API->>DB: upsert partido y clip, registra la aceptación y la liga al clip
        API-->>UW: {clip_id, received, partial}
    end
    opt hay puntos, líneas o referencias (y no es "No calcular")
        UW->>API: POST /clips/:id/calibration {pitch, frames, references}
        API->>DB: clips.calibration
        loop cada imagen de referencia del partido
            UW->>API: PUT /clips/:id/references/:n (JPEG)
            API->>FS: reference_N.jpg
        end
        Note over UW,API: Si las rechaza (400), el video se envía igual, sin metros
    end
    loop chunks que faltan
        loop partes de 8 MB desde el offset del servidor
            UW->>API: PUT /clips/:id/chunks/:n/video?offset=…
            alt offset distinto
                API-->>UW: 409 {expected_offset} (sigue desde ahí)
            else
                API->>FS: agrega al .partial
                API-->>UW: {received_bytes}
            end
        end
        UW->>API: PUT /clips/:id/chunks/:n (yaml + sha256)
        alt no coincide
            API-->>UW: 422 sha256 mismatch (borra el .partial, se reintenta)
        else coincide
            API->>FS: video_part_NNN.mp4 + .yaml
            API->>DB: clip_chunks
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
```

Si la red se corta a la mitad, WorkManager reintenta; el `POST /clips` siguiente dice qué chunks ya llegaron y cuántos bytes de cada parte, y solo se sube lo que falta.

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
    VM-->>E: ConsentDialog (casillas y enlaces a /legal/)
    E->>VM: Aceptar y enviar
    VM->>VM: guarda versión + hora (ISO, UTC)
    VM->>CW: accept (cadena "consent", en orden)
    CW->>API: POST /consent {action: accept, version, at, app_version}
    API->>DB: el mismo evento queda una vez
    VM->>UW: envía el clip con consent {version, accepted_at}
    UW->>API: POST /clips (registra la misma aceptación y liga el clip)

    Note over E,DB: Más tarde, en Ajustes
    E->>VM: Retirar consentimiento
    VM->>CW: withdraw
    VM->>UW: cancela las subidas pendientes
    VM->>VM: borra la aceptación local
    CW->>API: POST /consent {action: withdraw, …}
    API->>DB: guarda el retiro
    Note over UW,API: Una subida vieja que llegue después recibe 428 consent withdrawn
```

`ConsentWorker` espera a tener red y, si la sesión venció, a que el entrenador vuelva a iniciar sesión. El historial se consulta con `GET /consent`.

## 5. Marcar la cancha

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant PT as PitchTouchesScreen
    participant PR as PitchReferencesScreen
    participant H as Homography (vista previa)
    participant VM as CoachViewModel
    participant L as library.json

    E->>PT: "Marcar puntos de la cancha" (con "Calcular la cancha" encendido)
    E->>PT: elige un cuadro con la barra
    loop cada marca
        alt Tocar puntos
            E->>PT: toca un punto de la imagen
            E->>PT: elige cuál es en el dibujo de la cancha (11 o 7)
        else Pintar líneas
            E->>PT: pasa el dedo sobre una línea
            E->>PT: toca la línea en el dibujo, o "No sé cuál, solo es una línea"
        end
    end
    PT->>H: con 4 marcas o más, puntos + líneas con nombre
    H-->>PT: la cancha calculada en violeta sobre el cuadro
    Note over E,PT: Si no cae sobre la de verdad, alguna marca tiene el nombre equivocado; con 4 exactas se pide una quinta
    PT->>VM: saveTouches(clip, puntos y líneas)
    VM->>L: commit(): clip.touches

    opt la cancha se ve mal (poca luz, líneas borradas)
        E->>PR: "Imágenes de referencia"
        E->>PR: "Tomar foto" (cámara del teléfono) o "Usar este cuadro"
        PR->>VM: guarda el JPEG (enderezado, 1600 px)
        E->>PR: pinta encima las líneas (PaintReferenceScreen)
        VM->>L: commit(): match.references con sus trazos
    end
```

Los puntos, las líneas y las referencias viajan con el envío (flujo 3). En el worker, la homografía del cuadro marcado se lleva a los demás cuadros de su toma siguiendo el pasto, y los pies de cada jugador pasan a metros. Con `PP_PITCH_SAM=1`, SAM busca las líneas dos veces: con el concepto `white line` y con cada trazo como un objeto marcado con puntos, "esto es una línea" ([06-pipeline-analisis.md](06-pipeline-analisis.md#la-cancha-en-metros)).

## 6. Cola de análisis

```mermaid
sequenceDiagram
    participant W as Worker
    participant API as API
    participant DB as MySQL
    participant FS as storage/
    participant M as Meta Model API

    loop cada POLL_SECONDS
        W->>API: GET /internal/jobs/next (Bearer WORKER_CALLBACK_SECRET)
        API->>DB: regresa a la cola los running viejos (PHP)
        API->>DB: toma el queued más viejo → running
        alt no hay
            API-->>W: 204
        else hay
            API->>DB: clip segmenting
            API-->>W: {job_id, clip: {files, references, …}, match, options: {pitch_mode}, calibration}
        end
    end
    loop cada archivo del clip
        W->>API: GET /internal/clips/:id/files/:nombre
        API->>FS: lee el archivo (lista blanca de nombres)
        API-->>W: mp4 / yaml / jpg
    end
    loop cada chunk y concepto (equipo A, equipo B, balón)
        W->>M: SAM 3.1: POST /files + POST /responses (stream)
        M-->>W: cajas por cuadro y objeto
    end
    W->>W: une tracks entre chunks, equipos, cortes, cuadros que cuentan, balón, posesión
    W->>W: cancha en metros (con las marcas del entrenador) y métricas
    opt PP_PITCH_SAM=1 y el entrenador marcó la cancha
        W->>M: SAM 3.1 con las referencias y los cuadros clave: el concepto "white line"
        M-->>W: máscaras de las líneas
        W->>M: SAM 3.1, el mismo video: cada trazo como objeto con puntos
        M-->>W: máscaras de esos objetos (se unen a las anteriores)
    end
    W->>M: Muse Spark con métricas sin nombres (json_schema estricto)
    M-->>W: resumen e insights
    W->>API: POST /internal/jobs/:id/result (multipart: status + analysis)
    API->>DB: clip_analyses, trabajo done/failed, clip ready/failed
    Note over W,API: Si Meta falla tras los reintentos: status failed con el error
```

## 7. Ver el análisis en la app

```mermaid
sequenceDiagram
    actor E as Entrenador
    participant CD as ClipDetailScreen
    participant CA as ClipAnalysisScreen
    participant VM as CoachViewModel
    participant API as API

    E->>CD: abre un clip enviado
    CD->>VM: refreshRemote(clip)
    VM->>API: GET /clips/:remoteId
    API-->>VM: {status: queued | segmenting | ready | failed}
    VM->>VM: actualiza clip.status (si cambió)
    E->>CA: "Ver análisis" (con ready)
    CA->>VM: loadAnalysis(clip)
    VM-->>CA: la copia guardada, al instante
    VM->>API: GET /clips/:id/analysis
    API-->>VM: {segmenter, analyst, result}
    VM->>VM: ClipAnalysis.parse y guarda la copia
    VM-->>CA: resumen, posesión, hallazgos, equipos, metros
    E->>CA: "ver en el video" en un hallazgo
    CA->>CD: abre el clip en ese momento
```

Si la consulta del estado falla, el clip conserva el último conocido, sin mostrar error. Si no hay copia y no se puede bajar, la pantalla dice por qué (todavía no está, sin conexión, sesión vencida).
