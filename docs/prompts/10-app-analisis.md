# 10 · App: estadísticas, alineación y ver el análisis

**Lee antes:** [11-stats-jugadores.md](../11-stats-jugadores.md), [arquitectura/02-app-android.md](../arquitectura/02-app-android.md) y el formato del resultado en la etapa [08](08-worker.md).

```text
Agrega a pp-coach-tracker lo que el entrenador ve después de enviar y lo
que necesita para organizar al equipo. Todo con textos en español e inglés
(ui/i18n/Strings.kt) y la lógica en data/ con pruebas JVM.

1. PESTAÑA DE ESTADÍSTICAS (0.3.5)
- Estadísticas es un destino de la barra inferior (docs/diseno.md), junto a
  Partidos.
- Estadísticas: cada jugador con partidos jugados, minutos, goles,
  asistencias y precisión de pase; se ordena por cualquier estadística, que
  se muestra grande a la derecha con su valor por 90 minutos.
- data/StatsFilter.kt: qué partidos suman (todos; los últimos 3, 5 o 10 del
  equipo por fecha; un rango de fechas de un calendario, un solo día vale;
  o partidos elegidos con casillas, "Todos" y "Ninguno"). Vive en el
  ViewModel y lo comparten la pestaña y la página del jugador. Debajo dice
  cuántos partidos entran, o "Ningún partido entra en este filtro."
- Sin plantel, la pestaña lo dice y ofrece "Ir al plantel".

2. ALINEACIÓN INICIAL (0.3.6)
- data/StartingLineup.kt: formaciones de fútbol 11 (4-4-2, 4-3-3, 4-2-3-1,
  4-1-4-1, 4-5-1, 3-5-2, 3-4-3, 5-3-2, 5-4-1) y de 7 (2-3-1, 3-2-1, 3-1-2,
  2-1-2-1); cada una con su `key` estable para library.json.
- Diálogo con una cancha y cada posición (POR, DEF, MED, DEL): al tocarla
  se elige un jugador del plantel (primero los de esa línea) o se escribe un
  dorsal. Un dorsal está en un solo lugar. "Llenar con el plantel". Al
  cambiar de formación, el portero sigue en la portería y cada jugador
  conserva su línea mientras quepa.
- Se guardan dorsales, nunca nombres (`startingLineup` del partido). El
  tamaño de la cancha (11 o 7) sale de aquí en etapas posteriores.

3. VER EL ANÁLISIS (0.3.6)
- data/ClipAnalysis.kt: parse() acepta la respuesta de GET
  /clips/:id/analysis ({…, result: {…}}) o el resultado solo; los campos
  que falten quedan vacíos; un cuerpo que no es un análisis lanza
  IllegalArgumentException. El heatmap solo cuenta si es de 8×12 y no está
  vacío. Lee backends (modelos usados y usage) sin exigir campos nuevos.
- ClipAnalysisScreen (desde "Ver análisis" con el clip en ready): resumen;
  posesión con una barra del color de cada equipo, balón visto y cambios de
  posesión; hallazgos de más a menos importante (IMPORTANTE, A REVISAR,
  DETALLE, DATO) con el minuto y "Qué hacer"; los equipos lado a lado
  (jugadores visibles, posesión más larga, presión, ancho y alto en la
  imagen); el mapa de calor de cada equipo "como lo ve la cámara"; los
  límites; y en la vista técnica los modelos de Meta usados (SAM 3.1,
  Muse Spark) y cuántos cuadros se mandaron.
- Un análisis con backends.segmenter "sam_simulator" (desarrollo) muestra
  arriba un aviso: "Análisis de desarrollo: simulador, no SAM 3.1", y en la
  vista técnica el segmentador tal cual.
- Copia en files/analyses/clip_<id local>_<id servidor>.json: se muestra al
  instante y se actualiza al abrir con red. Sin copia, dice por qué no se
  pudo (todavía no está, sin conexión, sesión vencida). Borrar el clip o el
  partido borra la copia.

4. VIDEO PARA ANÁLISIS EN 1080p (0.3.7) Y TIPO DE CLIP (0.3.9)
- Ajustes → "Alta resolución (1080p)", APAGADA por omisión: máx. 1080p a
  3 Mbps (~23 MB por minuto; un chunk de 5 min ~112 MB, bajo los 160 MB por
  chunk de la API, y se sube en partes de 8 MB). Aplica a
  las copias nuevas; si un envío se retoma con otro tamaño, rehace los
  chunks. En la vista técnica: altura, bitrate y MB por minuto.
- Un 413 de la API se explica: "una parte del video pesa más de lo que
  acepta el servidor".
- ClipKind.forDuration: un clip de 5 minutos o más empieza como "Tiempo" y
  de 70 o más como "Partido completo"; el título sigue el tipo.
```

## Trampas conocidas

- **La opción de 1080p puede costar más y no está medido que ayude a SAM:** medirlo con el mismo clip en 720p y 1080p (jugadores por cuadro, balón visto, costo) antes de recomendarla. Mientras, va apagada y el texto no promete de más.
- **El análisis es del servidor, la copia es de la app:** no mezclar el id local y el del servidor en el nombre del archivo, o un clip reenviado muestra el análisis viejo.
- **El mapa de calor de la imagen no es un mapa de la cancha:** la nota lo dice ("arriba es lo más lejano"). El mapa sobre la cancha real llega en la etapa 13.

## Criterios de aceptación

- [ ] Pruebas JVM pasan (filtro de estadísticas, formaciones, lectura del análisis con campos faltantes y heatmap inválido).
- [ ] Emulador contra la API local: un clip con un análisis guardado (de la etapa 8) muestra todas las secciones; sin red muestra la copia.
- [ ] Una biblioteca de la etapa 5 abre sin perder nada.
- [ ] Capturas de la pestaña, la alineación y el análisis con datos de prueba, en una versión nueva ([09-despliegue.md](09-despliegue.md#publicar-una-versión-de-la-app)).
