# pp-coach-tracker 0.4.2

- **Fecha:** 3 de noviembre de 2026.
- **Versión anterior:** [0.4.1](../0.4.1/README.md).
- **Probado en:** pruebas unitarias y videos reales en la computadora; el release se compiló y firmó, y **falta probarlo en un teléfono real** (los botones nuevos todavía no se han usado en la app).
- **Versiones:** app 0.4.2 (`versionCode` 6). La API sigue en 0.4.0, sin cambios en el contrato.

## Qué cambió

- **Girar un video:** en la pantalla del clip, junto a ±5 s, "Girar a la izquierda" y "Girar a la derecha" giran el video un cuarto de vuelta, para una jugada grabada o importada de lado.
  - Se cambia la marca de rotación del encabezado del MP4 (la matriz de `tkhd`) en el mismo archivo: no se vuelve a codificar, no pierde calidad y el archivo queda del mismo tamaño. El reproductor, la miniatura y la copia para el análisis la respetan.
  - Los puntos de la cancha y la zona de juego giran con el video; la copia para el análisis se borra y se rehace al enviar.
  - Solo mientras el clip no se ha enviado: un análisis ya hecho es del video como estaba.
  - Un video que no se puede girar (no es MP4/MOV, o su pista tiene un espejo) no se toca y la app lo avisa.
- Trae también lo de [0.4.1](../0.4.1/README.md): los videos grabados en horizontal ya no quedan girados 90°.

## Probado

- **Pruebas:** app (unitarias), con 4 nuevas sobre MP4 armados en la prueba: los dos sentidos, las cuatro posiciones, el encabezado largo, la pista de audio intacta, un video espejado rechazado sin tocarlo y los puntos girados.
- **Videos reales:** dos copias (4K panorámico y uno de Meta AI) giradas con el mismo código: `ffprobe` lee la nueva rotación, el tamaño no cambió y el cuadro decodificado sale girado del lado correcto.
- **Pendiente:** girar un clip en un teléfono real, y grabar en horizontal (de 0.4.1).

## Archivo

`android_native/dist/pp-coach-tracker-0.4.2-release.apk` (18.9 MB, no se versiona; lo deja ahí `make.bat release`).
