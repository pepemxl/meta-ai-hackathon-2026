# App Android pp-coach-tracker

El entrenador graba o importa jugadas, la app prepara una copia de 10 fps en chunks con su YAML, la sube en partes de 8 MB a la API y muestra el análisis que hacen SAM 3.1 y Muse Spark.

## Compilar

Requiere el JDK 17 y el SDK de Android (compileSdk 35). Desde Windows:

```bat
make.bat            :: pruebas JVM + APK de debug
make.bat test       :: solo pruebas
make.bat install    :: instala el APK de debug en el emulador o teléfono
```

O con Gradle: `gradlew testDebugUnitTest assembleDebug`. El build debug se instala aparte (`com.ppcoach.tracker.debug`), empieza con chunks de 1 min (perfil `dev`) y permite HTTP para la API local (`http://10.0.2.2:3000` desde el emulador). El release empieza en el perfil `demo` (chunks de 5 min) y exige HTTPS.

## Qué hace

- **Partidos y clips:** alta y edición de partidos (equipos, colores de camiseta, fecha, lugar), grabar (horizontal, 1080p a 30 fps o 720p, cada toque del botón rojo es un clip), importar de la galería, detalle del clip con reproductor ±5 s, tipo, destacar, notas, compartir, guardar en la galería (Android 10+) y borrar.
- **Almacenamiento:** `library.json` en `files/` con escritura atómica; los videos en `Movies/match_NNNN/clip_NNNN.mp4` de la ubicación elegida (memoria del teléfono, interna privada o SD). Ajustes muestra espacio libre, la ruta y "Mover N clips aquí".
- **Versión para análisis** (`analysis/ChunkWorker.kt`): chunks de 10 fps sin audio con Media3 Transformer (720p a 1.5 Mbps, o 1080p a 3 Mbps), perfil `dev` (60 s, 2 s de traslape) o `demo` (300 s, 2 a 5 s), alineación del traslape, YAML compatible con OpenCV y `chunks.yaml`. Se reanuda saltando los chunks ya válidos.
- **Subida** (`analysis/UploadWorker.kt`): sesión con JWT cifrado (Android Keystore, AES-256-GCM), `POST /clips` con el consentimiento, partes de 8 MB que se reanudan en el offset del servidor, cierre con YAML + sha256 y `complete`. Solo con red, con reintentos.
- **Consentimiento:** diálogo de 4 casillas antes del primer envío (textos de `docs/legal/`), historial en la API (`analysis/Work.kt`, `ConsentWorker`), retiro desde Ajustes que cancela las subidas pendientes.
- **Análisis:** resumen con el degradado Spark, posesión, hallazgos de Muse Spark con "Ver en el video", equipos lado a lado, mapas de calor "como lo ve la cámara", límites y, en la vista técnica, los modelos usados. Un análisis del simulador muestra el aviso "Análisis de desarrollo".
- **Idiomas:** español e inglés (`ui/i18n/Strings.kt`); la primera vez sigue al teléfono.
- **Estilo:** tema claro y oscuro tipo apps de Meta (`docs/diseno.md`), sin colores dinámicos.

## Dónde está qué

```
app/src/main/java/com/ppcoach/tracker/
├── MainActivity.kt, AppGraph.kt      una Activity; biblioteca única del proceso (ViewModel y workers)
├── data/                             Kotlin casi puro, probado en la JVM
│   ├── Models.kt, LibraryCodec.kt, Durable.kt (LibraryStore), StorageLocations.kt
│   ├── Chunks.kt                     perfiles, cortes, mapa de frames, YAML, costo en SAM, ProxySpec
│   ├── ApiClient.kt                  OkHttp + UploadPlan (qué partes faltan)
│   ├── Consent.kt, ClipAnalysis.kt, SettingsStore.kt (SecretBox)
├── analysis/                         ChunkWorker, UploadWorker, Work (cadenas y ConsentWorker)
└── ui/
    ├── CoachViewModel.kt, AppRoot.kt pila de pantallas, barra inferior, hoja Crear
    ├── theme/                        tokens (Tokens.kt), Theme.kt, SparkGradient
    ├── components/                   PpTopBar, PpBottomBar, botones, tarjetas, chips, InsightCard…
    ├── i18n/Strings.kt
    └── screens/                      Partidos, partido, grabar, clip, análisis, ajustes
```

Pruebas JVM en `app/src/test/`: biblioteca y rutas, cortes y YAML, `ApiClient` con MockWebServer y el plan de partes, consentimiento, lectura del análisis, textos completos en los dos idiomas y contraste WCAG de cada par de colores.
