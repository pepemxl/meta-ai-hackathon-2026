# La cancha en metros con una cámara que se mueve: seguir la cámara solo, con SAM en una pasada y con SAM por rondas

**Fecha:** 31 de octubre de 2026.
**Qué se midió:** sobre la toma de la transmisión del clip 1 de [01](01-cv-local-vs-sam31.md#clip-1-la-toma-de-la-transmisión), una cámara que hace paneo, inclinación y zoom, con un solo cuadro marcado por el entrenador:

1. hasta dónde llega la homografía si solo se sigue el movimiento de la cámara sobre el pasto (KLT, sin llamadas a Meta);
2. cuánto mejora si las líneas blancas de SAM 3.1 la vuelven a anclar en un cuadro clave por segundo, en una sola pasada;
3. cuánto mejora si ese reanclaje se repite por rondas, partiendo cada vez de lo que SAM ya corrigió (commit `1207610`).

- **El video:** 15 min 02 s de una transmisión de TV, sin cortes ni repeticiones, analizado como la app lo sube: 1280×720 a 10 fps, 9020 cuadros. Es de terceros y no está en el repo ([08-uso-de-video.md](../08-uso-de-video.md)). Este reporte publica solo números, sin cuadros ni datos que identifiquen el partido.
- **La plantilla:** cancha de 11 con las medidas por omisión, 105×68 m.
- **La marca del entrenador:** 12 puntos en el cuadro 400 (0:40): media cancha, círculo y las esquinas del área grande y chica de un lado. La homografía de ese cuadro queda a 1.9 px de mediana (0.27 m).

## Resumen

| | Solo KLT | SAM, una pasada | **SAM por rondas** |
|---|---|---|---|
| Cuadros con cancha | 39 % (se corta en 5:49) | 100 % | **100 %** |
| Cuadros clave que SAM reancló | – | 417 de 900 | **764 de 900** |
| Minutos con menos de 10 anclas (de 60) | – | 6 (6, 7, 8, 11, 13, 14) | **0** |
| Error en el cuadro 800 (1:20) | 2.18 m | 0.75 m | 0.75 m |
| Error en el cuadro 1200 (2:00) | 2.43 m | 0.20 m | 0.20 m |
| Error en el cuadro 3000 (5:00) | 1.87 m (máx. 7.5) | 0.15 m | 0.15 m |
| Error en el cuadro 6600 (11:00) | sin cancha | 0.81 m (máx. 2.4), seguida | **0.51 m (máx. 1.2), con SAM** |
| Costo en Meta | US$0 | US$0.18 | US$0.18 (las mismas máscaras) |
| Tiempo de ajuste en la máquina de desarrollo | 1 min | 43 min | 59 min |

El error es la mediana de la distancia en la cancha entre las marcas a mano de ese cuadro y donde las pone la homografía. Solo con marcar un cuadro, SAM por rondas da metros con un error de medio metro durante los 15 minutos, por 18 centavos.

## 1. Solo seguir la cámara (KLT)

El worker sigue puntos del pasto entre cuadros y encadena las homografías desde el cuadro marcado. La cadena llega hasta el cuadro 3491 (5:49) y ahí se corta:

- **No es porque se pierda el seguimiento:** en ese cuadro siguen 399 de 400 puntos.
- **Es la prueba de los pies:** el worker deja de confiar en la homografía cuando pone a jugadores a más de 5 m fuera de las bandas. Con la desviación acumulada, 2 de 23 personas caen fuera, y una de ellas es personal fuera de la cancha.
- **Antes del corte ya se desvía:** 2.2–2.4 m en los minutos 1 y 2, con 16–40 px de error en la imagen.

Sin anclas externas, el error de cada paso se suma, y en un par de minutos ya no sirve para métricas en metros.

## 2. SAM en una pasada

- **Qué se manda:** un cuadro clave por segundo (900) con el concepto `white line on a soccer field`. Las máscaras se leen con `meta-sam-parser`. Costó US$0.18.
- **Qué hace el worker:** en cada cuadro clave ajusta la cámara para que la plantilla caiga sobre los píxeles blancos de SAM (`calibration/sam_lines.py`). Si cae bien, el cuadro se vuelve un ancla nueva; si contradice la cadena, la corta. Luego vuelve a encadenar.

Ya cubre los 15 minutos y en los minutos 1 a 5 baja el error de 2 m a 0.2 m. Pero las anclas por minuto caen después del minuto 6:

| Minuto | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Una pasada | 59 | 60 | 59 | 53 | 59 | 56 | 9 | **0** | 6 | 19 | 23 | 2 | 10 | **0** | 2 |
| Por rondas | 59 | 60 | 59 | 53 | 59 | 54 | 58 | 34 | 60 | 48 | 60 | 30 | 54 | 39 | 37 |

**Por qué caen:** la búsqueda de cámara parte de la homografía que trae la cadena de KLT original, que se cortaba en el cuadro 3491. Pasado ese punto, el punto de partida venía de muy lejos y la búsqueda no convergía. El cuadro 6600 quedó con la cancha seguida desde la última ancla, a 0.81 m; a la vista, el círculo central ya se desvía en el cuadro 4800.

## 3. SAM por rondas

El cambio en el worker (`_with_sam_lines`, `MAX_SAM_ROUNDS` = 50):

1. Se reanclan los cuadros clave que tienen cadena.
2. Se vuelve a encadenar desde las anclas nuevas, y esa cadena llega más lejos.
3. Se repite con los cuadros clave que faltan, partiendo de la cadena nueva, hasta que una ronda no agrega nada.
4. A los cuadros que la cadena nunca alcanza se les da un último intento, partiendo del ancla más cercana.

En este video bastaron **4 rondas**. Las anclas pasan de 417 a 764, todos los minutos tienen al menos 30, y el cuadro 6600 ya sale de un ancla de SAM: 0.51 m de mediana y 1.2 m de máximo, contra 0.81 m y 2.4 m. En los cuadros 800, 1200 y 3000 no cambia nada, porque ahí la primera pasada ya los había anclado. No hubo ninguna contradicción con la cadena. Las máscaras de SAM eran las mismas (en caché), así que el costo no cambia; solo el tiempo de ajuste, de 43 a 59 min.

Lo que queda: el cuadro 9000 (15:00) sigue con la cancha seguida y el área grande se ve corrida. El último minuto solo tiene 20 s y la cámara está en un acercamiento del área, con pocas líneas.

## 4. La prueba de los pies, menos estricta, y un tope sin ancla

Después de las rondas se cambió la prueba de los pies (commits `074d6bc` y `dd50197`):

- **Solo cuenta a quien tiene equipo**, y corta la cadena solo si falla 1 s seguido.
- **Quien pasa casi todo el tiempo fuera de las líneas** (los pies a más de 1 m fuera en el 80 % de sus cuadros con cancha) sale de la prueba y queda sin equipo. Es para los jueces de línea, la banca y el cuerpo técnico cuando visten como un equipo y el color no los separa.
- **Tope sin ancla:** con la cámara en movimiento, ninguna homografía se lleva a más de 60 s de su cuadro marcado o anclado por SAM.

En este video ningún track había quedado sin equipo: el visitante viste de oscuro y el cuerpo técnico también. El filtro por posición encontró a 3 personas fuera de las líneas, y sin ellas la cadena de KLT ya no se cortaba:

| Solo KLT | Cobertura | Error en el cuadro 6600 |
|---|---|---|
| Prueba de los pies original | 39 % (hasta 5:49) | sin cancha |
| Sin las 3 personas, sin tope | 87 % (hasta 13:04) | **5.96 m (máx. 13 m)** |
| Sin las 3 personas, con tope de 60 s | 11 % (hasta 1:40) | sin cancha |

Es decir, el corte de 5:49 lo provocaba el personal fuera de las bandas, no la desviación. Sin anclas, la prueba de los pies no detecta una desviación de varios metros, y por eso hace falta el tope.

**Con SAM por rondas no cambia nada:** 100 % de cobertura y los mismos errores (0.75, 0.20, 0.15 y 0.51 m), con 722 cuadros clave anclados y 70 confirmados.

## Conclusiones

1. **Con una cámara que se mueve, seguir el pasto no alcanza.** KLT sostiene la cámara, pero la desviación se suma: más de 2 m en un minuto, y la prueba de los pies la corta a los 6 minutos.
2. **SAM `white line` sirve como ancla, aunque no nombre las líneas.** Ajustando la plantilla a sus píxeles blancos, el error baja a 0.15–0.5 m, por US$0.18 cada 15 minutos (un cuadro por segundo).
3. **El reanclaje tiene que ir por rondas.** En una sola pasada, el alcance de SAM depende de dónde se cortaba la cadena sin SAM. Por rondas, cada ancla nueva lleva el punto de partida más lejos: sin costo adicional, llega a casi el doble de cuadros clave.
4. **Al entrenador le basta con marcar un cuadro** para todo un tiempo de partido de una sola toma, si SAM está activo. Sin SAM, la cancha solo se da hasta 60 s de cada marca.

## Lo que falta

- **El tiempo de ajuste:** 59 min para 15 min de video en la máquina de desarrollo. Para un tiempo completo hay que acotar la búsqueda o repartirla.
- **Cortes y repeticiones:** este tramo no tiene. Una transmisión completa necesita detectar el cambio de toma y reanclar desde cero, con `soccer field` y `center circle` ([02](02-homografia-y-sam-cancha.md)) en lugar de una marca por toma.
- **La verdad en metros:** el error se midió contra marcas a mano en 4 cuadros. Falta repetirlo con un video propio con distancias medidas en la cancha.

## Cómo reproducirlo

Los scripts y los resultados están fuera del repo, en la máquina de desarrollo (`LOCAL_DATA/Results/Test01_homography`). Corren en la imagen del worker, con el código de `worker/` montado en `/worker`:

1. **Los chunks:** el clip a 1280×720 y 10 fps, en 3 chunks, como los sube la app.
2. **`ptz_test.py klt`:** las 12 marcas del cuadro 400 como `touches` de `calibrate`, sin líneas de SAM. Mide contra las marcas a mano de los cuadros 800, 1200, 3000 y 6600 (`TRUTH`), dibuja la cancha sobre 8 cuadros del video y escribe una corrida para el visor (`npm run viewer:up` desde `api/`, botón "Marcas").
3. **`ptz_test.py sam`:** lo mismo, con `white line on a soccer field` en un cuadro clave por segundo (`MODEL_API_KEY` en `.env`). Las respuestas de SAM quedan en caché, así que repetirlo no vuelve a cobrar.
4. **`per_minute.py`:** las anclas de SAM por minuto, desde `calibration_sam.pkl`.

La variante de una pasada es la del commit anterior a `1207610`.
