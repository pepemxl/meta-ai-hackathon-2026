# CV local (YOLOv8n) contra SAM 3.1 en 15 minutos de partido

**Fecha:** 30 de octubre de 2026.
**Qué se midió:** el mismo clip de 15 min, analizado una vez con el segmentador local de desarrollo y otra con SAM 3.1 real por la Meta Model API.

> **La versión local no es SAM.** Es **YOLOv8n** (detector de COCO, en ONNX con OpenCV), con un tracker por IoU con compensación del movimiento de la cámara y el equipo por k-means del color del torso. Es el mismo motor que, en desarrollo, está detrás del simulador de la API de SAM ([13-entorno-desarrollo.md](../13-entorno-desarrollo.md)): el simulador responde con el formato de SAM, pero por dentro corre YOLO. Aquí se corrió directo, sin el simulador, y en los resultados aparece como `local_cv`. YOLOv8n es AGPL-3.0: vive fuera del repo, solo en la máquina de desarrollo, y nunca va a la app, a la API ni a producción. Producción usa solo SAM 3.1.

## El clip

- **Duración:** 15 min 02 s de la transmisión de TV de un partido profesional. Cámara alta en la tribuna, que sigue la jugada; sin cortes ni repeticiones en este tramo.
- **Equipos anonimizados:**
  - **Equipo A**, local, camiseta blanca;
  - **Equipo B**, visitante, camiseta oscura.
- **El video no está en el repo** ([08-uso-de-video.md](../08-uso-de-video.md)): es de terceros y solo se usó para probar en local. Este reporte publica solo números agregados, sin cuadros, sin nombres y sin datos que identifiquen el partido.

## Cómo se corrió

Las dos corridas leyeron **los mismos chunks**, cortados como los corta la app ([12-videos-largos-chunks.md](../12-videos-largos-chunks.md)):

- 3 chunks de 5 min con 3 s de traslape (perfil `demo`);
- 10 fps a 1280×720, en H.264 a 1.5 Mbps;
- 9,020 cuadros en total;
- la máscara de la cancha en el YAML de cada chunk.

No hubo puntos de cancha, así que todo está en coordenadas de pantalla.

| | CV local (YOLOv8n) | SAM 3.1 |
|---|---|---|
| Detección | YOLOv8n en el cuadro completo y en mosaicos 2×2 (jugadores lejanos), solo dentro de la cancha | 3 conceptos por chunk: `player in white jersey`, `player in black jersey` y `soccer ball` |
| Identidad | Tracker IoU propio | Ids de SAM dentro de cada chunk, unidos en el traslape |
| Equipo | k-means del color del torso; árbitro y porteros quedan sin equipo | El concepto que encontró al jugador |
| Hallazgos | Reglas fijas | Muse Spark (`muse-spark-1.3`) |
| Dónde corre | CPU de la laptop | Meta Model API |

Las dos corridas fueron a la vez. CV local compartió la CPU con SAM, que estuvo casi todo el tiempo esperando a la red.

## La gráfica

![Jugadores por cuadro (promedio por segundo) de CV local y SAM 3.1, y segundos con balón de cada uno](01-cv-local-vs-sam31.svg)

Arriba van los jugadores por cuadro, en promedio por segundo; las líneas punteadas son los cambios de chunk. Abajo, los segundos en que cada corrida vio el balón en al menos un cuadro: CV local en el 36 % de los segundos (17 % de los cuadros) y SAM en el 89 % de los segundos (85 % de los cuadros).

Lo que se ve:

- **CV local** sube y baja todo el tiempo, entre 13 y 29. Cuenta también al árbitro, a los jueces de línea y al personal de la banda, unos 4 por cuadro sin equipo. Pierde jugadores cuando se amontonan: el hueco de los minutos 11 y 12 es una jugada en el área.
- **SAM 3.1** es casi plano, con escalones: una vez que encuentra a un jugador lo sigue durante el resto del chunk, y suma a los nuevos de golpe.
- **El fallo de SAM está al inicio de los chunks 1 y 3.** Durante 21 s y 31 s, respectivamente, solo ve 10 jugadores, todos del equipo A. Es decir, `player in black jersey` no encontró a nadie hasta bien entrado el chunk. En el chunk 2 los dos equipos aparecen desde el primer segundo.

## Resultados

| Métrica | CV local (YOLOv8n) | SAM 3.1 |
|---|---|---|
| Tiempo de proceso | 1,527 s (1.69 veces el video) | 1,380 s (1.53 veces el video) |
| Costo | US$0 (CPU) | **US$5.45** (27,240 cuadros cobrados: 9,080 × 3 conceptos), más centavos de Muse Spark |
| Personas por cuadro | 22.7: A 8.9, B 9.7 y **4.0 sin equipo** | 22.1: A 10.5, B 11.6 y 0 sin equipo |
| Alto medio de la caja (fracción del alto) | 0.060 | 0.054 |
| **Tracks** | **1,231** | **53** |
| Duración mediana de un track | 7.2 s | 268.8 s |
| Cajas en tracks de 60 s o más | 34 % | 100 % |
| **Balón detectado / visto** | 12 % / **17 %** de los cuadros | 80 % / **85 %** de los cuadros |
| Posesión A / B | 51 % / 49 % | 44 % / 56 % |
| Cambios de posesión | 10 (0.67 por min) | 139 (9.25 por min) |
| Posesión más larga A / B | 7.7 s / 8.3 s | 22.1 s / 23.4 s |
| Hallazgos | 2, de reglas | 6, de Muse Spark |

### Coincidencia cuadro por cuadro

En cada cuadro se emparejaron las cajas de jugador de las dos corridas con IoU ≥ 0.3.

| | |
|---|---|
| Cajas que encontraron las dos | 166,414 |
| De CV local que también encontró SAM | 81 % |
| De SAM que también encontró CV local | 83 % |
| Solo CV local / solo SAM | 38,050 / 33,283 |
| Jugadores chicos (menos del 4 % del alto) que solo vio uno | SAM 10,253 · CV local 1,919 |
| Mismo equipo, cuando los dos asignaron uno | 89 % |
| Cuadros con balón: los dos / solo CV local / solo SAM | 1,443 / 115 / 6,231 |
| Balón en el mismo lugar (a menos del 2 % del ancho), cuando lo ven los dos | 71 % |

Promedio de personas por cuadro en cada minuto:

| Minuto | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CV local | 23.2 | 21.0 | 22.7 | 24.0 | 22.8 | 23.5 | 24.5 | 23.4 | 23.6 | 22.8 | 23.2 | 19.1 | 23.6 | 22.8 | 20.2 |
| SAM 3.1 | **16.8** | 22.0 | 23.1 | 23.7 | 23.7 | 21.8 | 23.0 | 22.8 | 23.0 | 23.2 | **14.2** | 21.9 | 22.4 | 23.8 | 26.6 |

### Revisión a ojo

Se revisaron cuatro cuadros con las cajas de las dos corridas encima. Las imágenes no se publican porque son de la transmisión.

- **Saque desde el centro:** CV local marca al árbitro y a los jueces de línea como personas sin equipo. SAM no los marca porque no visten de blanco ni de oscuro. Solo SAM encuentra el balón.
- **Inicio del chunk 2:** a SAM le faltan 6 jugadores del equipo B en un lado de la cancha.
- **Jugada en el área:** SAM separa a los 22 jugadores amontonados frente a la portería. CV local ve 16 y, en cambio, marca al personal de la banda y a los fotógrafos.
- **Medio campo:** las dos corridas coinciden en casi todo. SAM cuenta como equipo B a un juez de línea y a un oficial de la banda con ropa oscura. CV local pone en el equipo A a un jugador del equipo B.

## Conclusiones

1. **La identidad de los jugadores es para SAM.** Tiene 53 tracks en 15 min, casi uno por jugador en cada chunk; CV local tiene 1,231 pedazos de unos 7 s. Con CV local, las métricas por jugador (distancia, minutos, mapas) salen partidas.
2. **El balón también es para SAM:** 85 % de los cuadros contra 17 %. Por eso la posesión de CV local no sirve (10 cambios en 15 min y posesiones de 8 s como máximo). La de SAM es creíble: 139 cambios y posesiones de hasta 23 s.
3. **El conteo promedio empata, pero cada uno falla distinto.** CV local suma árbitros y personal de la banda, y se pierde a los jugadores amontonados. SAM deja fuera a casi todos los oficiales y encuentra más jugadores chicos, pero puede **tardar decenas de segundos en encontrar a un equipo al inicio de un chunk**, y a veces cuenta como jugador a alguien de la banda vestido como un equipo.
4. **Tiempo parecido, uso de la máquina opuesto.** Los dos tardan entre 1.5 y 1.7 veces la duración del video. CV local ocupa toda la CPU; SAM casi no usa la máquina y pasa el tiempo subiendo chunks y esperando a Meta.
5. **Costo de SAM:** unos **US$0.36 por minuto** de video (3 conceptos a 10 fps). Un partido de 90 min costaría unos US$33.

## Qué sigue

- **El inicio de chunk de SAM.** Hay que confirmar en el visor por qué un concepto no encuentra a nadie durante los primeros segundos. Dos soluciones baratas, que habría que medir antes y después con esta misma corrida:
  - repetir el concepto a mitad del chunk;
  - en el traslape, sembrar el chunk nuevo con los jugadores del anterior.
- **El personal de la banda con ropa del color de un equipo.** Revisar si la máscara de la cancha, ampliada 0.02, deja pasar los pies de la banda, o si conviene un tercer concepto para descartarlos (`referee`, `staff`).
- **Repetirlo con un partido amateur grabado con tripié**, que es el uso real de la app: una transmisión no es el producto ([learnings/01](../learnings/01-analisis-de-video.md)).

## Cómo reproducirlo

1. Cortar el video: `worker/tools/make_chunks.py VIDEO SALIDA --profile demo --pitch`, y recodificar los chunks a H.264 con `ffmpeg -c:v libx264 -b:v 1500k`.
2. Correr SAM 3.1: `analyze SALIDA --home-color white --away-color black --debug-dir …` con `MODEL_API_KEY` y `SAM_API_BASE_URL` vacío ([worker/README.md](../../worker/README.md)).
3. Correr el segmentador local (fuera del repo) sobre los mismos chunks, guardando su corrida.
4. Comparar las dos en el visor ([viewer/README.md](../../viewer/README.md#comparar-dos-corridas)): marca las dos corridas y toca **Comparar**. Muestra esta misma gráfica, las cajas lado a lado o solo sus diferencias, y la tabla de coincidencia.

La gráfica sale de los `tracks.json` de las dos corridas: por cada segundo, cuántas cajas de jugador tuvo en promedio cada cuadro y si apareció el balón en alguno.
