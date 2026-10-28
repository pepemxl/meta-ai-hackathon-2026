# 01 · App Android base (v0.1)

**Lee antes:** [diseno.md](../diseno.md) (tema, componentes y navegación, completo), [07-plan-android.md](../07-plan-android.md) (v0.1, estructura, almacenamiento, permisos), [arquitectura/02-app-android.md](../arquitectura/02-app-android.md).

```text
Crea la app Android "pp-coach-tracker" en android_native/ (proyecto Gradle con
Kotlin DSL y version catalog, un solo módulo app).

Stack: Kotlin, Jetpack Compose (Material 3), CameraX (video), Media3/ExoPlayer
(reproducir), minSdk 26, targetSdk el más reciente estable, una sola Activity.
Sin base de datos y sin librería de navegación.

Paquete com.ppcoach.tracker. Debug con applicationIdSuffix ".debug" y
versionNameSuffix "-debug" para convivir con el release. versionName "0.1.0".

Arquitectura:
- data/ en Kotlin casi puro (probable en JVM): modelos, códec JSON, rutas.
- ui/CoachViewModel.kt: UN AndroidViewModel con todo el estado (biblioteca,
  ajustes, pila de navegación como lista de `Screen`, mensajes de una línea).
- ui/AppRoot.kt dibuja la pantalla de arriba de la pila; "Atrás" quita una.
- Toda escritura de la biblioteca pasa por commit(next): cambia el estado en
  memoria al instante y escribe library.json en un solo hilo, en orden.

Modelos (data/Models.kt):
- JerseyColor(key, argb): red E53935, white F5F5F5, blue 1E5BD8,
  "light blue" 6EC6FF, black 212121, yellow FDD835, green 2E7D32,
  orange FB8C00, purple 7B1FA2, gray 9E9E9E. La key va en inglés y no cambia
  nunca: entra tal cual en los prompts de SAM ("player in red jersey") y en el
  clasificador de equipos del worker.
- Match(id, home, away, homeColor, awayColor, date "yyyy-MM-dd", venue, notes,
  createdAt). Carpeta match_%04d.
- Clip(id, matchId, fileName "clip_%04d.mp4", title, kind[play|half|full|
  training], source[camera|import], createdAt, durationMs, width, height, fps
  medido del archivo, sizeBytes, notes, starred, status (por ahora "local"),
  storage (ubicación), remoteId=0).
- Library(matches, clips, nextMatchId, nextClipId). Los ids nunca se reutilizan.

Persistencia:
- files/library.json con un códec tolerante (campos desconocidos se ignoran,
  valores desconocidos caen al default). Si no se puede leer, se aparta como
  library.json.bad sin tocar los videos y se empieza vacío.
- Escritura atómica: archivo temporal + fsync + rename (data/Durable.kt), para
  sobrevivir a que el teléfono se apague a media escritura.
- Videos en Movies/match_NNNN/clip_NNNN.mp4 dentro de una ubicación elegible:
  "Memoria del teléfono" (getExternalFilesDir, por omisión, se puede copiar
  por USB), "Memoria interna privada" (filesDir) o tarjeta SD si hay. Cada clip
  recuerda su ubicación; Ajustes muestra espacio libre y clips por ubicación,
  la ruta con botón de copiar, y "Mover N clips aquí" (copia, verifica tamaño
  y luego borra).

Estilo (docs/diseno.md): tema claro y oscuro tipo apps de Meta en ui/theme/
(Color.kt con los tokens y SparkGradient, Type.kt, Shape.kt, Theme.kt que
arma el ColorScheme de Material 3 desde los tokens; colores dinámicos
apagados). Componentes compartidos en ui/components/ (PpTopBar, PpBottomBar,
botones, PpCard, MatchCard, ClipRow, ClipCarousel, StatusChip, chips,
EmptyState, PpSheet). Barra inferior con Partidos, Crear (hoja: grabar,
importar, nuevo partido) y Ajustes; las etapas 2 y 10 agregan Plantel y
Estadísticas. Ajustes → Apariencia (Sistema, Claro, Oscuro). Ningún color
escrito a mano fuera de ui/theme/ salvo los de JerseyColor. Ícono adaptativo
propio (cancha blanca sobre el degradado), nada que recuerde un logo de Meta.

Pantallas: Partidos (lista y alta/edición), Detalle del partido (clips,
filtros por tipo y destacados), Grabar, Detalle del clip (reproductor ±5 s,
datos medidos, notas, tipo, destacar, compartir, exportar a la galería en
Android 10+, borrar), Ajustes (idioma, ubicación, formato de grabación).

Grabar: horizontal a pantalla completa, 1080p a 30 fps (720p si la cámara no da 1080p),
audio si hay permiso, pantalla siempre encendida. Cada toque del botón rojo es
un clip y la pantalla sigue abierta para la siguiente jugada. Importar videos
de la galería también.

Idiomas: TODOS los textos en ui/i18n/Strings.kt como una interfaz Strings con
dos objetos Es y En; CompositionLocal LocalStrings en la raíz. La primera vez
sigue el idioma del teléfono; se cambia en Ajustes. Prueba JVM que verifique
que Es y En implementan todo y que ningún texto está vacío.

Pruebas JVM (JUnit): códec de la biblioteca (ida y vuelta, campos
desconocidos, archivo corrupto), rutas de almacenamiento, textos y
ThemeContrastTest (contraste WCAG >= 4.5:1 de cada par texto/fondo de los
tokens, en claro y oscuro).

Agrega android_native/README.md (cómo compilar, qué hace, dónde está qué) y un
make.bat para pruebas + APK.
```

## Trampas conocidas

- **El fps del contenedor a veces no viene:** medirlo con `MediaExtractor` (tiempos de los frames), y guardar 0 si no se puede, no un valor inventado.
- **`library.json` que no carga:** nunca mostrar una lista vacía antes de leerlo (parecería que se perdieron los partidos); dibujar un fondo vacío hasta que cargue.
- **Exportar a la galería** con MediaStore solo desde Android 10; antes, avisar.

## Criterios de aceptación

- [ ] `gradlew testDebugUnitTest assembleDebug` pasa.
- [ ] En el emulador (API 34): crear partido, grabar con la cámara virtual (MP4 H.264 con audio), ver la miniatura, reproducir, cerrar y reabrir la app con los datos intactos.
- [ ] Cambiar idioma español/inglés; cambiar ubicación, mover un clip y grabar otro que queda en la nueva.
- [ ] Los criterios de aceptación de [diseno.md](../diseno.md#criterios-de-aceptación-para-cada-etapa-de-la-app): contraste, sin colores a mano fuera del tema, sin marcas de Meta.
- [ ] Capturas **en claro y en oscuro** en `docs/versions/0.1.0/` y su README (ver [09-despliegue.md](09-despliegue.md#publicar-una-versión-de-la-app)).
