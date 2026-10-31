# Arquitectura de pp-coach-tracker

Cómo está construido pp-coach-tracker durante el hackathon (app 0.2.0, API 0.2.0, worker 0.2.0) y qué falta. Describe lo que **existe en el código**; lo que todavía no, va marcado como *pendiente*. Los diagramas están en [Mermaid](https://mermaid.js.org/), que GitHub dibuja directamente.

Las reglas de la construcción (sin respaldo local, solo licencias permisivas, un commit por función) están en [prompts/00-contexto.md](../prompts/00-contexto.md), y el detalle de cada pieza, en el prompt de su etapa.

| Documento | Contenido |
|---|---|
| [01-repositorio.md](01-repositorio.md) | Qué hay en cada carpeta y archivo del repo |
| [02-app-android.md](02-app-android.md) | La app: capas, navegación, dónde guarda cada cosa, trabajos en segundo plano y estados del clip |
| [03-api.md](03-api.md) | La API (Node y PHP): rutas, ciclo de una petición, autenticación, modelo de datos, archivos y límites |
| [04-flujos.md](04-flujos.md) | Diagramas de secuencia: login, grabar y preparar, enviar, consentimiento, marcar la cancha, cola de análisis y ver el análisis |
| [05-despliegue.md](05-despliegue.md) | Entorno local con Docker, pruebas, configuración y secretos, y producción (API en Hostinger, worker en este host) |
| [06-pipeline-analisis.md](06-pipeline-analisis.md) | El worker: SAM 3.1, cuadros que cuentan, balón, posesión, cancha en metros, Muse Spark, lo que recibe Meta y el costo |

## Vista general

```mermaid
flowchart LR
    coach(["Entrenador"])

    subgraph phone["Teléfono Android"]
        app["pp-coach-tracker<br/>Kotlin + Compose"]
        files[("Videos, library.json<br/>e imágenes de referencia")]
    end

    subgraph server["API en producción (https://ppcoachtracker.pepemxl.com, Hostinger)"]
        api["API PHP 8.3<br/>sin Composer"]
        db[("MySQL / MariaDB")]
        disk[("storage/<br/>chunks e imágenes por clip")]
        legal["/legal/<br/>textos legales"]
    end

    subgraph ia["Análisis (worker de producción en Docker, en la máquina de desarrollo)"]
        worker["Worker Python<br/>OpenCV + numpy"]
        meta["Meta Model API<br/>SAM 3.1 + Muse Spark"]
    end

    viewer["Visor de corridas<br/>y comparación de dos<br/>(solo local)"]

    coach -->|"graba, marca la cancha,<br/>revisa el análisis"| app
    app <--> files
    app -->|"HTTPS: login, chunks en partes de 8 MB,<br/>puntos y líneas de la cancha, imágenes<br/>de referencia, consentimiento, análisis"| api
    app -.->|abre en el navegador| legal
    api <--> db
    api <--> disk
    worker -->|"GET /internal/jobs/next<br/>descarga chunks e imágenes"| api
    worker -->|"POST /internal/jobs/:id/result"| api
    worker -->|"un chunk y un concepto por petición;<br/>hallazgos con salida estructurada"| meta
    worker -.->|"corridas guardadas"| viewer
```

**No hay respaldo local:** si la Meta Model API falla después de los reintentos, el trabajo termina en `failed` con un mensaje claro. SAM 3.1 y Muse Spark se verificaron con la API real el 30 de octubre ([results/](../results/README.md)). Las pruebas automáticas siguen usando streams sintéticos, y lo que saliera de un simulador local de SAM se marca `sam_simulator` y la API de producción lo rechaza ([13-entorno-desarrollo.md](../13-entorno-desarrollo.md)).

## Qué existe y qué falta

| Pieza | Estado | Dónde (etapa) |
|---|---|---|
| App: partidos, grabar, importar, clips, ubicaciones de los videos, español e inglés, tema claro y oscuro | Listo | `android_native/` (1) |
| App: versión de 10 fps en chunks con YAML (perfiles `dev` de 1 min y `demo` de 5 min), vista simple y técnica | Listo | `analysis/ChunkWorker.kt`, `data/Chunks.kt` (3) |
| App: login, sesión cifrada, subida en partes de 8 MB que se reanuda, estado del análisis | Listo | `analysis/UploadWorker.kt`, `data/ApiClient.kt` (5) |
| App: consentimiento con historial; páginas públicas de los textos legales | Listo | `data/Consent.kt`, `ui/components/ConsentDialog.kt`, `api/php/public/legal/` (7) |
| App: ver el análisis (resumen, posesión, hallazgos, mapas de calor, sección en metros) | Listo | `ui/screens/ClipAnalysisScreen.kt`, `data/ClipAnalysis.kt` (10, en parte) |
| App: máscara de la cancha medida al preparar, en el YAML de cada chunk | Listo | `analysis/PitchSampler.kt`, `data/PitchMask.kt` (12) |
| App: "Calcular la cancha", la pregunta de las líneas, los puntos marcados y la vista previa | Listo | `ui/components/Pitch.kt`, `ui/screens/PitchTouchesScreen.kt`, `data/PitchTouches.kt` (13) |
| App: líneas pintadas e imágenes de referencia de la cancha | Listo | `ui/screens/PitchReferencesScreen.kt`, `data/PitchReferences.kt`, `analysis/ReferenceImages.kt` (14) |
| App: canchas guardadas con su largo y ancho en metros, elegidas con un menú | Listo; sin probar en pantalla | `data/SavedPitches.kt`, `ui/components/PitchPicker.kt` |
| App, API y worker: "La cámara queda fija todo el video" (`analysis.camera`): una homografía para todo el video o seguida cuadro por cuadro | Listo y desplegado; sin probar en pantalla ni con un video de tripié propio | `CameraMode`, `clips.camera`, `calibration/__init__.py` |
| API Node (desarrollo y pruebas) y API PHP (producción), mismo contrato | Listo; la PHP **en producción** desde el 30 de octubre | `api/src/`, `api/php/` (4, 6, 9) |
| Worker: SAM 3.1 por concepto, equipos, métricas en la imagen, Muse Spark con salida estructurada, caché y costo | Listo y **verificado con la API real** (dos clips de 15 min); máscaras con `meta-sam-parser`; ~US$0.36 por minuto | `worker/` (8) |
| Worker: corridas guardadas, cortes, cuadros que cuentan, balón, posesión, árbitro | Listo; con SAM real, el balón en el 85 % de los cuadros y un track por jugador en cada chunk | `cuts.py`, `shots.py`, `ball.py`, `metrics.py` (11) |
| Video ancho o 4K: partir en videos de hasta 1024 px sobre la cancha y unir los tracks | Listo dentro del worker (`PP_TILES=auto`); probado a mano con SAM real (0 → 19 jugadores por cuadro). La app manda como mucho 1080p, que no se parte | `worker/ppcoachtracker_worker/tiles.py`, `tools/make_tiles.py` |
| Worker: máscara de la cancha (de la app o medida) | Listo | `pitch.py` (12) |
| Worker: cancha en metros con los puntos y las líneas del entrenador, propagada por la toma; métricas en metros | Listo; sin probar con un video de tripié propio | `calibration/` (13, 14) |
| Worker: líneas de la cancha con SAM guiado por los trazos del entrenador | Escrito y **apagado** (`PP_PITCH_SAM`); el concepto `white line` y sus máscaras funcionan con SAM real, falta ver si SAM sigue un trazo | `calibration/sam_lines.py` (14) |
| Visor de corridas (cajas, posesión, métricas, hallazgos) y comparación de dos corridas del mismo video | Listo; no dibuja las líneas pintadas ni las máscaras de SAM | `viewer/` (11) |
| Resultados medidos: SAM 3.1 contra un segmentador local de desarrollo | Tres pruebas sobre el mismo partido | [results/](../results/README.md) |
| Plantel y estadísticas por jugador y partido: lo del entrenador gana, totales, por 90 y precisión de pase, importar el reporte por dorsal | Listo; sin probar en pantalla | `data/Stats.kt`, `ui/screens/StatsScreens.kt` (2) |
| Dorsales de los tracks del análisis: el entrenador liga cada track a un jugador y sus minutos pasan a las estadísticas del partido | Listo; sin probar en pantalla | `TrackTagsCard`, `Library.syncVideoMinutes` |
| Pestaña de estadísticas con filtros (todos, últimos 3/5/10, fechas, elegidos), compartidos con la página del jugador; alineación inicial con 9 formaciones de 11 y 4 de 7 | Listo; probado en el emulador | `data/StatsFilter.kt`, `data/StartingLineup.kt`, `ui/screens/StatsTabScreen.kt` (10) |
| Simulador local de SAM | **Pendiente** (fuera del repo) | etapa 8a |
| API en producción y worker desplegado | Listo: API en Hostinger; worker en Docker dentro de WSL en la máquina de desarrollo (se detiene si la laptop se suspende) | etapa 9, [05-despliegue.md](05-despliegue.md) |
| Retención: borrar el video 24 h después del análisis y a los 30 días como máximo | Listo: cron diario en producción a las 04:30 desde el 30 de octubre | `api/php/bin/retention.php` |

## Decisiones que dan forma a todo

1. **El teléfono hace el trabajo pesado de video.** La app genera una copia de 10 fps, máximo 720p (1080p opcional) y sin audio, partida en chunks, y ahí mismo mide dónde está la cancha. Así se sube un tercio de los cuadros, la subida se reanuda por partes y el servidor compartido no necesita ffmpeg ([12-videos-largos-chunks.md](../12-videos-largos-chunks.md)).
2. **Hosting compartido para la API, cómputo aparte para la IA.** El hosting sirve bien PHP + MySQL, pero no procesos largos. El worker **pide** trabajos a la API, así que la API nunca tiene que llamar a nadie.
3. **Un solo contrato, dos implementaciones.** La API Node sirve para desarrollar y probar rápido; la PHP corre en producción. Las dos pasan las mismas pruebas de contrato y comparten `api/db/schema.sql`.
4. **Solo Meta para la IA.** SAM 3.1 sigue a jugadores y balón y Muse Spark escribe los hallazgos; no hay detector, tracker ni reglas locales. Las dependencias son solo de licencia permisiva, más `meta-sam-parser`, el decodificador de Meta para las máscaras de SAM (SAM License) ([THIRD_PARTY.md](../../THIRD_PARTY.md)).
5. **IDs en lugar de nombres.** El pipeline y la Meta API solo ven ids de track y equipos; los nombres no salen del teléfono ([10-privacidad-jugadores.md](../10-privacidad-jugadores.md)).
6. **Sin video sin consentimiento.** Cada clip va ligado a una aceptación registrada, y el servidor guarda el historial de aceptaciones y retiros ([legal/](../legal/README.md)).
7. **Mejor sin metros que con metros falsos.** La cancha sale de lo que marca el entrenador (puntos y líneas pintadas); SAM solo confirma o corrige a partir de ahí, y está apagado hasta probarlo. Las métricas de la imagen siempre están, y las de metros van aparte y dicen de qué parte del video salen ([etapa 13](../prompts/13-cancha-en-metros.md)).
8. **SAM ve 1024 px, no el video completo.** Reduce cada cuadro a 1024 px por el lado largo, así que lo que importa es el tamaño del jugador después de esa reducción (unos 30 px). Un video ancho o 4K se parte en videos de 1024 px con traslape y los tracks se unen después ([learnings/04](../learnings/04-video-ancho-y-sam.md)).
