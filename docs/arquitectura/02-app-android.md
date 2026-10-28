# App Android: pp-coach-tracker

> **Antecedente.** Este documento viene del diseño y del prototipo previos al hackathon, que tenían un respaldo local (detector YOLOv8n, tracker propio e insights por reglas). La versión que se construye **no tiene respaldo**: solo SAM 3.1 y Muse Spark, y solo dependencias con licencia permisiva. Donde este texto diga otra cosa, mandan [prompts/00-contexto.md](../prompts/00-contexto.md) y el prompt de cada etapa.

Kotlin + Jetpack Compose, una sola actividad, sin base de datos: la biblioteca del entrenador es un `library.json` y los videos son archivos MP4. Código en `android_native/`.

## Capas

```mermaid
flowchart TB
    subgraph ui["ui/ (Compose)"]
        root["AppRoot<br/>pila de pantallas, snackbar,<br/>diálogo de consentimiento"]
        screens["screens/<br/>Matches, MatchDetail, Record,<br/>ClipDetail, PitchTouches, ClipAnalysis,<br/>Roster, Stats, Settings"]
        comps["components/ (PitchDialog, ConsentDialog…)<br/>i18n/Strings · theme/"]
    end

    vm["CoachViewModel<br/>estado observable + acciones"]

    subgraph data["data/ (Kotlin, casi todo sin Android)"]
        models["Models · Stats · ClipAnalysis<br/>StartingLineup · PitchTouches · PitchMask<br/>Library, Match, Clip, Player"]
        store["LibraryStore · LibraryCodec · Durable<br/>library.json y rutas"]
        video["VideoFiles · StorageLocations<br/>RecordingSpec · Proxy · Chunks"]
        net["ApiClient (OkHttp)"]
        sec["SettingsStore · SecretBox · Consent"]
    end

    subgraph work["analysis/ (WorkManager)"]
        chunk["ChunkWorker<br/>+ PitchSampler"]
        upload["UploadWorker"]
        consent["ConsentWorker"]
    end

    camx["CameraX"]
    media3["Media3 Transformer"]
    ks["Android Keystore"]
    apiext["API PP-Coach-Tracker"]

    root --> screens --> vm
    comps -.-> screens
    vm --> models
    vm --> store
    vm --> video
    vm --> sec
    vm -->|"login, estado y análisis del clip"| net
    vm -->|"encola"| work
    screens --> camx
    chunk --> media3
    chunk --> video
    upload --> net
    consent --> net
    upload --> sec
    consent --> sec
    sec --> ks
    net --> apiext
```

- **Un solo ViewModel** (`CoachViewModel`) tiene la biblioteca, los ajustes, la pila de navegación y el progreso de los trabajos. Las pantallas leen su estado y llaman sus funciones.
- **Toda escritura de la biblioteca pasa por `commit()`**: cambia el estado en memoria al instante y escribe `library.json` en un solo hilo, en orden, con escritura atómica (`Durable`).
- **`data/` casi no depende de Android**, así que las pruebas JVM cubren chunks, estadísticas, el códec de la biblioteca, el cliente HTTP (con MockWebServer), el consentimiento, la máscara de la cancha, la plantilla y la homografía de los puntos marcados, y la lectura del análisis (también en metros).

## Navegación

No hay librería de navegación: `CoachViewModel` guarda una pila de `Screen` y `AppRoot` dibuja la de arriba. "Atrás" quita una. La pantalla principal tiene dos pestañas, Partidos y Estadísticas (`homeTab`). El filtro de estadísticas (`statsFilter`: todos, últimos N, rango de fechas o partidos elegidos) vive en el ViewModel y lo comparten la pestaña y la página de cada jugador.

```mermaid
flowchart LR
    M["Matches<br/>pestañas Partidos y Estadísticas"] --> MD["MatchDetail<br/>(clips del partido)"]
    M -->|"pestaña Estadísticas<br/>(filtro + orden)"| PS
    M --> R["Roster<br/>(plantel)"]
    M --> S["Settings<br/>(ajustes)"]
    MD --> REC["Record<br/>(CameraX)"]
    MD --> CD["ClipDetail<br/>(ver, preparar, enviar)"]
    MD --> MS["MatchStats<br/>(stats del partido)"]
    MS --> SE["StatEditor<br/>(jugador × partido)"]
    MS --> R
    R --> PS["PlayerStats<br/>(historial del jugador)"]
    PS --> SE
    CD -.->|"primer envío"| CON{{"ConsentDialog"}}
    CD -.->|"al enviar, una vez por partido"| PD{{"PitchDialog<br/>¿calculamos la cancha?"}}
    CD -->|"Marcar puntos de la cancha"| PT["PitchTouches<br/>(un cuadro, puntos con nombre)"]
    CD -->|"análisis listo"| CA["ClipAnalysis<br/>(lo que encontró el worker,<br/>y en metros si hay cancha)"]
```

## Dónde guarda cada cosa

```mermaid
flowchart LR
    subgraph ext["Memoria del teléfono (por omisión)<br/>Android/data/…/files/Movies/"]
        v1["match_0001/clip_0001.mp4"]
    end
    subgraph int["Interna privada<br/>files/"]
        lib["library.json"]
        an["analyses/clip_7_145.json<br/>(copia del análisis)"]
        v2["Movies/match_0001/clip_0002.mp4<br/>(si se eligió esta ubicación)"]
        prefs["shared_prefs/settings.xml<br/>(token cifrado)"]
    end
    subgraph sd["Tarjeta SD (si hay)"]
        v3["match_0002/clip_0003.mp4"]
    end
    subgraph cache["Caché (se puede borrar)<br/>proxies/"]
        px["match_0001/clip_0001/<br/>video_part_001.mp4 + .yaml<br/>chunks.yaml"]
    end
```

| Dato | Formato | Notas |
|---|---|---|
| Partidos, clips, plantel, estadísticas | `library.json` | Tolerante a campos desconocidos; si no se puede leer se aparta como `library.json.bad` sin tocar los videos. Cada clip guarda también su respuesta sobre la cancha (`pitchMode`) y los puntos marcados (`touches`); el partido, la última respuesta |
| Videos originales | MP4 (H.264/AAC, 1080p o 720p, 30 fps) | Cada clip recuerda su ubicación; "Mover N clips aquí" los pasa de una a otra |
| Versión para análisis | Chunks MP4 de 10 fps, máx. 720p (1080p con "Alta resolución" en Ajustes), sin audio, 1 min con 1 s de traslape, más un YAML por chunk (con la máscara de la cancha de cada cuadro) y `chunks.yaml` | En la caché: se regenera si Android la borra |
| Análisis de cada clip | JSON tal como lo devuelve la API | Copia para verlo sin conexión; se reemplaza cada vez que se abre con red |
| Ajustes | SharedPreferences | Idioma, ubicación, vista técnica, servidor, correo, consentimiento |
| Sesión (JWT) | Cifrada con AES-256-GCM, llave en el Keystore (`SecretBox`) | Nunca va en los datos de entrada de WorkManager |

## Trabajos en segundo plano

Cada clip tiene **una sola cadena de WorkManager** (`clip-<id>`), así que preparar y enviar nunca se pisan. El consentimiento tiene su propia cadena (`consent`), que respeta el orden de los eventos.

```mermaid
flowchart LR
    send(["Enviar a análisis"]) --> q{"¿Consentimiento<br/>vigente?"}
    q -->|no| dlg["ConsentDialog"] -->|"Aceptar y enviar"| acc["ConsentWorker<br/>accept"]
    dlg --> pm
    q -->|sí| pm{"¿Respuesta sobre<br/>la cancha?"}
    pm -->|"no (primer video del partido)"| pdlg["PitchDialog"] --> send2(["envía"])
    pm -->|sí| send2
    send2 --> has{"¿Ya hay chunks?"}
    has -->|no| cw["ChunkWorker<br/>Media3 Transformer"] --> uw["UploadWorker"]
    has -->|sí| uw
    uw -->|"POST /clips (con pitch_mode)<br/>POST calibration (si hay puntos)<br/>PUT chunks<br/>POST complete"| api[("API")]
    acc -->|"POST /consent"| api
    wd(["Retirar consentimiento"]) --> wdw["ConsentWorker<br/>withdraw"] -->|"POST /consent"| api
    wd -.->|"cancela"| uw
```

| Worker | Qué hace | Reintentos |
|---|---|---|
| `ChunkWorker` | Corta el original en chunks de 1 min (1 s de traslape), los convierte a 10 fps/720p (o 1080p, según Ajustes) sin audio, lee el tiempo de cada frame y escribe el YAML con el mapa al original y el `sha256`. Con `PitchSampler` decodifica cada chunk y mide la cancha en cada cuadro (`PitchMask`, la misma regla que el worker); si menos de la mitad tiene cancha, el clip lo avisa. Si se interrumpe, sigue en el chunk que faltaba | Lo relanza la cadena |
| `UploadWorker` | Registra el clip (con el consentimiento y `pitch_mode`), manda los puntos marcados a `POST /clips/:id/calibration` (si el servidor los rechaza, el video se envía igual, sin metros), sube solo los chunks que el servidor no tiene con el mismo `sha256` y completa. Lee la sesión cifrada al correr | Con red; backoff exponencial, hasta 8 intentos en errores de red, `5xx`, `408`, `429` y `422`. Se detiene con `401`, `400` y `428` |
| `ConsentWorker` | Manda cada aceptación o retiro a `POST /consent` | Con red; espera también a que haya sesión (`401`); hasta 20 intentos |

## Estados de un clip

```mermaid
stateDiagram-v2
    [*] --> local: grabar o importar
    local --> local: preparar chunks (ChunkWorker)
    local --> uploading: UploadWorker sube chunks
    uploading --> queued: POST /complete
    queued --> segmenting: el worker toma el trabajo
    segmenting --> analyzing: planeado
    segmenting --> ready: resultado done
    analyzing --> ready
    segmenting --> failed: resultado failed
    ready --> [*]
    failed --> queued: volver a enviar
```

- La app guarda el estado en `Clip.status` y el id del servidor en `Clip.remoteId`.
- Al abrir un clip enviado consulta `GET /clips/:id` y actualiza el estado.
- Con el estado `ready`, "Ver análisis" abre `ClipAnalysis`: resumen, posesión, hallazgos, los dos equipos lado a lado y dónde estuvo cada uno; si el resultado trae metros, la sección "En la cancha, en metros" con la cancha vista desde arriba. La pantalla muestra al instante la copia guardada en `files/analyses/clip_<id local>_<id del servidor>.json` y pide `GET /clips/:id/analysis` para tenerla al día. Sin conexión se queda la copia; solo si no hay copia dice por qué no se pudo (todavía no está, sin conexión, sesión vencida). Borrar el clip o el partido borra la copia.
- La vista simple lo muestra como "En cola para el análisis", "Analizando…", "Análisis listo" o "El análisis falló".

## Estadísticas de jugadores

```mermaid
flowchart LR
    rep["Reporte de análisis (JSON)<br/>samples/analysis-report.example.json"] -->|"Importar reporte"| parser["AnalysisReportParser"]
    parser --> pms["PlayerMatchStats.analysis"]
    coach(["Entrenador"]) -->|"StatEditor"| pmsc["PlayerMatchStats.coach"]
    pms --> val["valor = coach ?: analysis"]
    pmsc --> val
    val --> tot["StatsCalc<br/>totales, por 90 min, ratios"]
```

- Las estadísticas que puede dar el video (`Stat.fromAnalysis`) se desbloquean cuando el partido tiene reporte; las demás (goles, tarjetas) las captura el entrenador.
- Lo que escribe el entrenador gana sobre lo del análisis, y borrarlo regresa al valor del análisis. Detalle en [11-stats-jugadores.md](../11-stats-jugadores.md).
- Hoy el reporte se importa como archivo. Cuando exista el worker vendrá de la API.

## Seguridad en el teléfono

- **Sesión cifrada** con una llave del Keystore; en 0.3.0 el token se guardaba en claro, y 0.3.1 lo migra y borra los registros viejos de WorkManager.
- **HTTP en claro solo en debug** (`src/debug/res/xml/network_security_config.xml`) para llegar a la API local en `10.0.2.2`; el release exige HTTPS.
- **Permisos:** cámara, micrófono (el audio del original; la versión para análisis no lleva), internet y estado de la red.
