# App Android pp-coach-tracker

El entrenador graba o importa jugadas, la app prepara una copia de 10 fps en chunks con su YAML, la sube en partes de 8 MB a la API y muestra el análisis que hacen SAM 3.1 y Muse Spark.

## Compilar

Requiere el JDK 17 y el SDK de Android (compileSdk 35). Desde Windows:

```bat
make.bat            :: pruebas JVM + APK de debug
make.bat test       :: solo pruebas
make.bat install    :: instala el APK de debug en el emulador o teléfono
make.bat keystore   :: crea la llave de firma y keystore.properties (una sola vez)
make.bat release    :: APK de release, firmado si existe keystore.properties
```

**Firma del release:** `make.bat keystore` pide la contraseña sin mostrarla (o la toma de `PP_COACH_TRACKER_KEYSTORE_PASSWORD`), crea `keystore/pp-coach-tracker-release.jks` y escribe `keystore.properties`; ninguno se versiona y nunca pisa uno existente. [`keystore.properties.example`](keystore.properties.example) explica cómo hacerlo a mano. Respalda los dos fuera del repo: sin la llave no se puede actualizar la app instalada.

O con Gradle: `gradlew testDebugUnitTest assembleDebug`. El build debug se instala aparte (`com.ppcoach.tracker.debug`), empieza con chunks de 1 min (perfil `dev`) y permite HTTP para la API local (`http://10.0.2.2:3000` desde el emulador). El release empieza en el perfil `demo` (chunks de 5 min) y exige HTTPS.

## Qué hace

- **Partidos y clips:** alta y edición de partidos (equipos, colores de camiseta, fecha, lugar), grabar (horizontal, 1080p a 30 fps o 720p, cada toque del botón rojo es un clip), importar de la galería, detalle del clip con reproductor ±5 s, tipo, destacar, notas, compartir, guardar en la galería (Android 10+) y borrar.
- **Almacenamiento:** `library.json` en `files/` con escritura atómica; los videos en `Movies/match_NNNN/clip_NNNN.mp4` de la ubicación elegida (memoria del teléfono, interna privada o SD). Ajustes muestra espacio libre, la ruta y "Mover N clips aquí".
- **Máscara de la cancha** (`data/PitchMask.kt`, `analysis/PitchSampler.kt`): después de codificar cada chunk se decodifica con MediaCodec, se lee la rejilla de 160×90 de cada cuadro y se escribe la sección `pitch` del YAML con el mismo método que el worker (`hsv_v1`). Si falla, el YAML sale sin ella. Si menos de la mitad de los cuadros tiene cancha, el clip lo avisa.
- **Plantel y estadísticas** (`data/Stats.kt`, `ui/screens/StatsScreens.kt`, [docs/11](../docs/11-stats-jugadores.md)): la pestaña **Plantel** guarda a los jugadores con dorsal (1 a 99, único), posición y un apodo opcional que nunca sale del teléfono. En cada partido, **Estadísticas** (desde el detalle del partido) lleva la hoja de cada jugador: las 17 stats, las del video bloqueadas (🔒) hasta que hay reporte, lo del entrenador gana y "Volver al análisis" lo deshace. **Importar reporte** lee un JSON por dorsal (`samples/analysis-report.example.json`): agrega los dorsales nuevos como medios e informa lo que ignoró. En el análisis de un clip, **Dorsales de los tracks** liga cada track a un jugador, y "Pasar los minutos al partido" suma sus segundos en los clips del partido. La página de cada jugador da los totales, el valor por 90 minutos (solo con partidos que tienen minutos) y la precisión de pase. `library.json` va en la versión 2 y la 1 abre con el plantel vacío.
- **Cámara fija o en movimiento** (`CameraMode`): en el detalle del clip, junto a "Calcular la cancha", la casilla "La cámara queda fija todo el video". Marcada (tripié o panorámica), el worker usa una sola homografía para todo el video; sin marcar (la cámara se mueve o hace zoom), la sigue cuadro por cuadro. Se guarda en el clip y en el partido, para que los videos siguientes empiecen con la misma respuesta; sin respuesta se manda `moving`. Viaja como `analysis.camera` en `POST /clips` y queda fija al enviar.
- **Canchas guardadas** (`data/SavedPitches.kt`, `ui/components/PitchPicker.kt`): en "Puntos de la cancha" un menú elige la cancha: las guardadas (la más reciente primero), fútbol 11 o 7 sin guardar, "Nueva cancha…" y "Editar cancha". Cada una guarda en `library.json` su nombre, 11 o 7 y, si el entrenador la midió, el largo y el ancho en metros (20 a 130 por 15 a 100, el largo mayor); sin medidas se usan las típicas y la pantalla lo avisa. El partido recuerda su cancha y los clips siguientes empiezan en ella; una cancha que se vuelve a visitar no se mide de nuevo. Las medidas viajan como `length_m` y `width_m` en `POST /clips/:id/calibration`, tomadas de la cancha guardada al enviar.
- **Versión para análisis** (`analysis/ChunkWorker.kt`): chunks de 10 fps sin audio con Media3 Transformer (Ajustes → "Video para el análisis": 720p a 1.5 Mbps, 1080p a 3 Mbps, u **Original**, a la resolución del video hasta 2160p con los mismos bits por pixel que 1080p, entre 1.5 y 12 Mbps; una copia grande va en chunks más cortos para no pasar de 140 MB, así que una 4K va en chunks de 1 min y el worker parte el video ancho para SAM), perfil `dev` (60 s, 2 s de traslape) o `demo` (300 s, 2 a 5 s), alineación del traslape, YAML compatible con OpenCV y `chunks.yaml`. Se reanuda saltando los chunks ya válidos.
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
