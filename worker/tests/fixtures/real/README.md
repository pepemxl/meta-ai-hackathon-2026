# Canchas reales grabadas con la app

Cuadros de dos canchas reales, grabados por el autor con pp-coach-tracker 0.4.0 en un teléfono, el 3 de noviembre de 2026. Video propio, sin jugadores; los usa [test_real_pitches.py](../../test_real_pitches.py).

| Archivo | Cancha | Clip original | Qué prueba |
|---|---|---|---|
| `yerbabuena1_cerca.jpg` | Yerbabuena 1: pasto con pista de atletismo, desde la grada | `clip_0004.mp4`, 5 s | Los colores fijos encuentran el pasto y dejan fuera la pista |
| `yerbabuena1_lejos.jpg` | Yerbabuena 1, más lejos | `clip_0001.mp4`, 5 s | Pendiente: con sombras de árboles y líneas, el pasto llena el 60 % de su polígono y no cuenta como cancha |
| `yerbabuena2_sol.jpg` | Yerbabuena 2: tierra | `clip_0007.mp4`, 5 s | La cancha se encuentra con el color de la tierra, no con el del pasto |
| `yerbabuena2_sombra.jpg` | Yerbabuena 2, con la sombra de un árbol | `clip_0005.mp4`, 5 s | Una muestra tomada al sol encuentra la cancha que los colores fijos no; una con un toque en la sombra (tonos azulados) cubriría el cielo, así que el worker la ignora y la app ya no acepta ese toque |
| `yerbabuena1_cel_8k.jpg` | Yerbabuena 1 desde la grada, con la cámara del teléfono en 8K | `VID_20261103_080811_8K.mp4`, 7.7 s | Los colores fijos encuentran el pasto también en 8K |
| `yerbabuena1_seca.jpg` | Otra cancha, de pasto seco y amarillo, con la cámara del teléfono | `VID_20261103_080707.mp4`, 5.4 s | Ni los colores fijos de pasto ni los de tierra la encuentran; la muestra de tres toques sí |
| `yerbabuena2_cel_8k.jpg` | Yerbabuena 2, vista amplia, con la cámara del teléfono en 8K | `VID_20261103_081426_8K.mp4`, 32.1 s | Pendiente: el cerro seco y las casas beige del fondo también tienen color de tierra, y el polígono sube hasta arriba del cuadro |

- **Cómo se sacaron:** 640×360, del cuadro tal como se guardó, sin la rotación de 90° que esa versión de la app le ponía por error a lo grabado en horizontal (corregido en 0.4.1).
- **Con la cámara del teléfono:** los tres últimos se grabaron sin la app, para importarlos (HEVC, de 720p a 8K, en horizontal y sin marca de rotación).
- **Los videos originales no se versionan:** están en `LOCAL_DATA/assets/Yerbabuena 1` y `Yerbabuena 2` (10 clips con la app, de 1920×1080 y 3840×2160 a 30 fps) y en las carpetas `… con cel para import test` (16 videos y 22 fotos con la cámara).
