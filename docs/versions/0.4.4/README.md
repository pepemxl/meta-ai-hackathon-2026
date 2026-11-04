# pp-coach-tracker 0.4.4

- **Fecha:** 4 de noviembre de 2026.
- **Versión anterior:** [0.4.3](../0.4.3/README.md).
- **Probado en:** pruebas unitarias de la app y del worker, y un partido completo de 49 min (Test03) recalculado sin llamadas; el release se compiló y firmó con la misma llave que 0.4.3, y **falta probarlo en un teléfono real**.
- **Versiones:** app 0.4.4 (`versionCode` 8). La API sigue en 0.4.0, sin cambios en el contrato. El worker de producción ya corre los cambios de abajo.

## Qué cambió

- **Marcar la cancha:**
  - **Zoom:** botones para acercar y alejar la imagen (hasta 8×) y para moverla arriba, abajo, izquierda y derecha. Los toques siguen cayendo en el punto correcto con cualquier zoom.
  - **Líneas de dos puntos:** en el modo de líneas, "dos puntos" traza una línea recta con dos toques; "libre" sigue trazando con el dedo.
- **Pantallas internas:** el contenido, y con él el botón de guardar de los puntos de la cancha, ya no queda debajo de la barra de navegación de Android.
- **En el worker** (no requiere la app nueva):
  - **Mosaicos:** un jugador visto por los dos mosaicos de un video ancho queda en un solo track. En Test03, la mediana pasó de 50 s a 99 s de cajas por track, y los cuadros con cancha en metros de 40 % a 86 %.
  - **Balón:** se descarta el balón a más de 2 m fuera de las líneas cuando hay cancha en metros, y las líneas de SAM se piden en cuadros clave de todo el video.
  - **Posesión:**
    - un equipo gana el balón al sostenerlo 1 s;
    - se mide en metros donde hay cancha;
    - un pase no se le da a quien pasa cerca;
    - un pase entre compañeros no termina la posesión.

    En Test03, los cambios de posesión bajaron de 5.2 a 1.4 por minuto. Contra 4 min marcados a mano, coinciden en número (7 contra 9) y en el dueño cuadro a cuadro (95 %).
  - **Pausas:** 90 s o más con el balón quieto (la pausa de hidratación, una lesión larga) salen de las métricas, con una nota.

## Probado

- **Pruebas:** app (unitarias); worker, 132 pasan y 2 fallas esperadas.
- **Partido real:** Test03 (49 min), con las respuestas de SAM guardadas: la posesión contra marcas a mano y la pausa de hidratación revisada a ojo (24:09 a 27:16).
- **Pendiente:** el zoom y las líneas de dos puntos en un teléfono real, y la pausa de hidratación de un segundo tiempo.

## Archivo

`android_native/dist/pp-coach-tracker-0.4.4-release.apk` (18.9 MB, no se versiona; lo deja ahí `make.bat release`).
