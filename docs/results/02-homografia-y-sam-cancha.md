# La cancha en metros con una cámara fija: marcas a mano y conceptos de SAM 3.1

**Fecha:** 31 de octubre de 2026.
**Qué se midió:** sobre la cámara panorámica del clip 3 de [01](01-cv-local-vs-sam31.md), que no hace paneo, inclinación ni zoom:

1. qué tan bien sale la homografía con puntos marcados a mano, con el mismo código del worker;
2. qué devuelve SAM 3.1 real con cuatro conceptos de la cancha;
3. si los puntos que salen solo de SAM dan una cancha parecida a la de las marcas a mano.

- **El video:** el mismo de 01: panorámica de 3840×800 a 25 fps de un partido profesional, cancha de 11. Es de terceros y no está en el repo ([08-uso-de-video.md](../08-uso-de-video.md)). Este reporte publica solo números, sin cuadros ni datos que identifiquen el partido.
- **La plantilla:** cancha de 11 con las medidas por omisión, 105×68 m (`calibration/template.py`).

## Resumen

| Cómo se obtuvo la cancha | Error en la cancha (mediana) | Máximo |
|---|---|---|
| 24 marcas a mano, dejando fuera una a la vez | **0.41 m** | 1.62 m |
| 5 marcas a mano (lo que marcaría un entrenador), medido en las otras 19 | **0.94 m** | 1.14 m |
| 5 puntos que salen **solo de SAM** (sin marcar nada), medido en las 24 marcas | **0.96 m** | 2.36 m |

Con una cámara fija, 5 marcas bien repartidas alcanzan para métricas en metros con un error de alrededor de 1 m, y SAM llega casi a lo mismo sin que el entrenador marque nada. El costo de SAM fue de US$0.16.

## 1. La homografía con marcas a mano

Se marcaron 24 puntos de la plantilla sobre un cuadro original (t = 30 s), en recortes ampliados al doble con una rejilla:

- las 4 esquinas;
- los dos extremos de la media cancha;
- los cruces de la media cancha con el círculo;
- las 8 esquinas de cada lado de las áreas grande y chica.

Los puntos de las áreas sobre la línea de meta se tomaron sobre esa línea, que se ve recta. La homografía la calculó `solve_frame` del worker: RANSAC con una tolerancia de 1.5 % del alto (12 px), como con las marcas de la app.

| Prueba | Error en la imagen | Error en la cancha |
|---|---|---|
| Las 24 marcas (RANSAC aceptó 22) | mediana 5.7 px, máximo 14.7 | – |
| 5 marcas: las 4 esquinas y la media cancha lejana; error en las otras 19 | mediana 8.6 px, máximo 13.4 | mediana 0.94 m, máximo 1.14 m |
| Dejando fuera una marca a la vez (las otras 23 la predicen) | mediana 6.6 px | mediana 0.41 m, máximo 1.62 m |

Al dibujarla sobre el cuadro, las áreas, el círculo y la media cancha caen sobre las líneas pintadas.

**Dónde se equivoca más:** en el lado cercano, con las esquinas de las áreas a 13–15 px. Hay dos causas que no se pueden separar del todo:

- **La unión de la panorámica.** La banda cercana se curva unos 4–5 px de los extremos al centro, y una sola homografía no puede ajustar una línea curva.
- **Las medidas de la cancha.** Probando largos de 100 a 110 m y anchos de 62 a 74 m, el mejor ajuste queda en 108×68 m, con una mediana de 3.4 px, contra 5.7 px con 105×68. Puede que la cancha sea más larga que la plantilla, o que la panorámica la estire.

Una cancha guardada con sus medidas reales (la app ya lo permite) y más marcas lo reducirían.

## 2. Cuatro conceptos de SAM 3.1

- **Qué se mandó:** 50 cuadros (los primeros 5 s, a 10 fps como la app) de dos formas:
  - la panorámica completa, que SAM reduce a 1024 px de ancho;
  - las 3 partes de 1024×596 px de [01](01-cv-local-vs-sam31.md#clip-3-la-panorámica-nativa-en-tres-partes), donde las líneas se ven a su tamaño.
- **Conceptos:** `white line`, `soccer field`, `center circle` y `soccer goal`.
- **Total:** 16 peticiones y 800 cuadros, unos **US$0.16**. El mínimo de 50 cuadros por petición cuesta un centavo, así que no es un problema: con la cámara fija bastan 5 s de video.

| Concepto | Panorámica completa | 3 partes de 1024 px |
|---|---|---|
| `soccer field` | **Muy bien:** una sola máscara de toda la cancha en los 50 cuadros (42 % del cuadro), sin la pista, la tribuna ni los anuncios, y sin los jugadores | Igual de bien, cada parte con su pedazo |
| `center circle` | **Excelente:** la elipse coincide con las marcas a mano a ~2 px | Solo en la parte del centro, como debe ser |
| `soccer goal` | Las dos porterías, pero solo en 25 de 50 cuadros | Cada portería en 49–50 de 50 cuadros |
| `white line` | Pocos pedazos (mediana de 5 objetos): la banda cercana, una línea del área, el arco del área y una línea de la pista | Más líneas (de 4 a 8 objetos por parte): banda cercana, media cancha, líneas de las áreas y arcos; **incompletas** (no está la banda lejana) y con líneas de la pista de atletismo |

Todas las máscaras se leyeron, sin ninguna ilegible (`meta-sam-parser`).

**El círculo central es el punto de referencia más preciso.** La elipse que se ajustó a la máscara tiene su centro en x = 1922 px, contra 1924 de la media cancha marcada a mano. Sus extremos sobre la media cancha quedan en y = 294 y 406 px, contra 294 y 408.5 a mano.

**`white line` sola no sirve para nombrar las líneas.** Sirve como píxeles blancos para afinar la cámara, que es lo que ya hace la búsqueda del worker (`calibration/sam_lines.py`), pero no como una lista de líneas con nombre.

## 3. La cancha solo con SAM

Del contorno de `soccer field` (panorámica completa, cuadro 25) se sacó un cuadrilátero con `approxPolyDP`. Comparado con las esquinas a mano:

| Esquina | Distancia a la esquina a mano |
|---|---|
| Lejana derecha | 2 px |
| Cercana derecha | 17 px |
| Cercana izquierda | 30 px |
| Lejana izquierda | falló: la aproximación a cuatro lados tomó un vértice equivocado, a 636 px |

Las esquinas que funcionan quedan algo afuera de las líneas, porque la máscara también toma el pasto entre la banda y los anuncios.

Con esas 3 esquinas y los dos extremos del círculo sobre la media cancha (5 puntos, ninguno marcado a mano):

- **El worker no los acepta tal como están:** RANSAC encuentra solo 4 que coinciden dentro de 12 px y pide 5.
- **Con un ajuste directo por mínimos cuadrados:** mediana de 8.8 px contra las 24 marcas a mano (máximo 29). En la cancha, **mediana de 0.96 m y máximo de 2.36 m**, este último en la esquina lejana izquierda, que no entró.

## Conclusiones

1. **Con una cámara fija, una homografía de 5 marcas da metros con un error de alrededor de 1 m.** Más marcas y las medidas reales de la cancha lo bajan a medio metro. En una panorámica unida de varias cámaras, la curva de las bandas pone un piso de unos 4–5 px.
2. **SAM sirve para la geometría de la cancha, y es barato:** un centavo por concepto con 50 cuadros.
   - `soccer field` da el contorno, del que salen esquinas aproximadas y podría salir la zona de juego sin que el entrenador marque nada;
   - `center circle` da dos puntos y la media cancha con precisión de píxeles;
   - `soccer goal` da la línea de meta.
3. **SAM no nombra las líneas.** `white line` llega incompleta y con basura fuera de la cancha. Los nombres los tiene que poner el modelo de cámara.

## Lo que falta

- **Que el worker calibre solo.**
  1. Armar el punto de partida con el contorno, el círculo y las porterías.
  2. Afinarlo con la búsqueda de cámara que ya existe contra los píxeles de `white line`, quitando lo que cae fuera de `soccer field`. Eso debería corregir la esquina que falló y los 17–30 px de las otras.
  3. Que el entrenador solo confirme en la app la cancha dibujada.
- **Repetirlo con un tripié propio** (sin panorámica unida) y con una distancia medida en la cancha, para tener la verdad en metros y no solo contra marcas a mano.
- **Probar `center circle` y `soccer field` en una cámara que se mueve** (Test03), donde habría que pedirlos en un cuadro clave por toma.

## Cómo reproducirlo

Los scripts y los resultados están fuera del repo, en la máquina de desarrollo (`LOCAL_DATA/Results/Test02_homography` y `Test02_sam_pitch`). Corren en la imagen del worker, con el código de `worker/` montado en `/worker`:

1. **Marcas a mano:** un cuadro de t = 30 s, las 24 marcas en píxeles y `solve_frame`, `fit_homography`, `project` y `unproject` de `calibration/`. El barrido de medidas usa `template.build(11, largo, ancho)`.
2. **SAM:**
   - los primeros 50 cuadros a 10 fps, en MPEG-4, completos y recortados a las 3 partes de `make_tiles.py`;
   - un `POST /responses` por video y concepto, con el mismo cuerpo que `SamSegmenter._call` (`MODEL_API_KEY` y la URL de Meta);
   - las cajas se leen con `parse_stream` y las máscaras con `sam_lines.parse_masks`.
3. **Comparación:** el cuadrilátero de `soccer field` sale de `cv2.findContours` y `cv2.approxPolyDP`; la elipse de `center circle`, de `cv2.fitEllipse`. Los 5 puntos se ajustan con `solve_frame` o, si los rechaza, con `fit_homography`, y se miden contra las 24 marcas.
