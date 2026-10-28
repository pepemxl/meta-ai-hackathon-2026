# Cómo medir

## Comparar corridas

- **La misma entrada, un cambio a la vez.** Los mismos 10 minutos cortados una vez (`worker/.local/cmp_720`, `cmp_1080`) permitieron comparar resolución, mosaicos, acercamientos y balón con números directos.
- **`tools/compare_runs.py`** pone varias corridas lado a lado. Conviene agregarle una fila por cada cosa nueva que se mide (cuadros que cuentan, balón detectado) en el mismo cambio que la introduce.
- **`tools/rescore.py`** recalcula el balón y la posesión desde las cajas guardadas, en segundos. Una corrida completa de 10 minutos tarda ~30 min en CPU; para ajustar umbrales de `ball.py` o `metrics.py`, no hace falta detectar de nuevo.
- **Recalcular sobre algo ya filtrado infla los números.** `rescore.py` volvía a pasar por `ball.py` una corrida que ya había pasado, y los huecos rellenados contaban como detecciones (balón visto de 71 % a 73 %). Quitar los rellenados no bastaba: sin los otros candidatos, el filtro unía balones de trayectorias distintas y rellenaba más. Ahora una corrida ya filtrada solo recalcula las métricas; para probar `ball.py` hay que correr el análisis completo.
- **Barrer parámetros antes de ajustarlos a mano.** Cambiar el ruido del Kalman de 0.004 a 0.04 casi no movía nada: eso señaló que el problema era otro (la cuenta, y luego la elección del candidato), no el valor.
- **La estabilidad se mide entre corridas casi iguales** (720p contra 1080p, misma entrada). Con detecciones casi iguales, la posesión de un equipo variaba entre 42 % y 52 %.
- **Comparar cuadro por cuadro, no solo totales.** Que dos corridas den 53 % no dice que coincidan: hay que contar en cuántos cuadros las dos cuentan posesión y si es del mismo equipo (`worker/.local/agreement.py`, no versionado). Así se vio que el total se estabilizó por tener más datos, no por coincidir más.

## Revisar a ojo

- **Una muestra al azar de 6 a 12 cuadros revisada a ojo encuentra lo que los promedios esconden.** Así salieron el bombo como 6 jugadores, el personal de la orilla como balón, y el Kalman siguiendo un anuncio.
- **Sacar los cuadros directo de los chunks, no del visor sin ventana.** En capturas de Edge sin ventana, a veces el video no alcanza a buscar el cuadro: dos cuadros distintos salieron con la misma imagen y otro salió negro. Un script en el contenedor del worker que lee los chunks con `Clip.frames()` da el cuadro exacto (`worker/.local/grab_frames.py`, `grab_dropped.py`, no versionados).
- **Acercar la imagen.** En una cuadrícula de 9 cuadros a 480 px no se distingue un balón de una cabeza. Recortes de 160×90 alrededor de cada caja, al doble de tamaño, sí.
- **Marcar en la imagen qué se quedó y qué se fue** (rojo lo de antes, verde lo de ahora). Así se compara en un vistazo.
- **Las capturas con video de transmisiones se quedan en la máquina** (scratchpad o `worker/.local/`), nunca en `docs/` ([08-uso-de-video.md](../08-uso-de-video.md)).

## Leer los números con cuidado

- **"Balón visto" bajó de 75 % a 63 % y fue una mejora:** lo que se fue eran balones falsos. Hay que separar "detectado" de "rellenado" y revisar qué se quitó.
- **Una métrica nueva necesita su contexto en el resultado:** `frames_used`, `discarded`, `ball.detected` y las notas dicen de dónde sale cada promedio.
- **Sin referencia no hay exactitud, solo consistencia.** Para saber si 18 cambios de posesión son reales hace falta un video etiquetado (Quantigo, o uno propio etiquetado a mano).
