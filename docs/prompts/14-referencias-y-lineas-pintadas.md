# 14 · Imágenes de referencia y líneas pintadas

**Lee antes:** [13-cancha-en-metros.md](13-cancha-en-metros.md) (los puntos del entrenador, el solver, la propagación y el paso 5 de las líneas con SAM) y [arquitectura/06-pipeline-analisis.md](../arquitectura/06-pipeline-analisis.md#la-cancha-en-metros) (la búsqueda de la cámara contra los píxeles blancos).

Cuando hay poca luz o las líneas de la cancha están borradas, los puntos no siempre se ven y SAM no sabe qué buscar. Esta etapa deja que el entrenador **muestre** las líneas: las pinta con el dedo sobre un cuadro del video o sobre fotos de la cancha. Un commit por pieza (worker, API, app), cada uno con sus pruebas.

```text
REGLA: MEJOR SIN METROS QUE CON METROS FALSOS (la de la etapa 13). Lo que
pinta el entrenador es una marca más, que se mide igual que un punto.

1 · LÍNEAS PINTADAS EN EL CUADRO TOCADO (worker)
- En la pantalla de puntos, el entrenador pasa el dedo encima de una línea
  y dice cuál es (touch_near, halfway, box_left…: los nombres de las líneas
  de template.py) o "solo es una línea" (name null).
- calibration/__init__.py solve_frame: a cada trazo con nombre se le ajusta
  una recta (solver.fit_line, mínimos cuadrados totales); uno que se dobla
  más de 1.5 % del alto o mide menos de 5 % no cuenta. Puntos + líneas con
  nombre ≥ 5 (con 4 cualquier nombre encaja). Solo puntos: RANSAC como antes.
  Con líneas: ajuste conjunto (DLT con las dos), se quita la peor marca
  mientras algo pase de 1.5 % del alto y queden más de 5, y al final se
  recuperan las que encajan. solver.well_posed rechaza lo que deja la
  cancha libre (líneas de una sola dirección).
- Los trazos sin nombre no entran al solver: solo le muestran a SAM dónde hay una línea.

2 · API (Node y PHP, igual)
- POST /clips/:id/calibration: por cuadro 0..40 puntos y 0..20 líneas
  {name | null, points: 2..64 {x, y}} (al menos uno de los dos); además
  references: 0..6 {n distinto 1..6, source photo|frame, t_ms?, strokes:
  0..20 {points}}. Hace falta un cuadro o una referencia; hasta 3000 puntos
  de trazos en total; cuerpo de hasta 256 KB.
- PUT /clips/:id/references/:n: JPEG crudo hasta 2 MB (400 si no empieza
  con FF D8 FF, 413 si pasa, 409 cuando el worker ya tomó el clip). Se
  guarda como reference_N.jpg junto a los chunks.
- El trabajo trae clip.references (los nombres que hay en disco) y
  /internal/clips/:id/files/reference_N.jpg los sirve como image/jpeg.

3 · SAM GUIADO POR LOS TRAZOS (worker, PP_PITCH_SAM=1, apagado)
- calibration/sam_lines.py: un MP4 a 1 fps con las imágenes de referencia
  (con franjas negras al tamaño del video) y después un cuadro clave por
  segundo más los cuadros tocados; se escribe mientras pasan los cuadros
  para la propagación (nunca todos en memoria).
- Dos peticiones con ese video, porque SAM 3.1 no combina una frase con
  puntos o cajas (dev.meta.ai/docs/sam/segmenting):
  · el concepto (SAM_LINE_CONCEPT, "white line": frase sustantiva corta);
  · los trazos: cada uno es un objeto con hasta 8 puntos positivos a lo
    largo, en su cuadro del video, como tokens en el texto
    ("<0f>1<|point;x=..;y=..;w=..;h=..|>..."; object_prompt()). Cada punto
    se mueve al píxel más brillante cercano (1.2 % del alto): un dedo es más
    ancho que una línea lejana. Hasta 16 objetos: los trazos más largos.
  Las máscaras de las dos se unen; si una falla, queda la otra. Cada
  petición cobra al menos 50 cuadros.
- Máscaras: el formato documentado data=H,W,<marca><base85> de la caja del
  objeto (el alfabeto y el empacado, supuestos) y un lector tolerante de
  otros (COCO, bits empacados, PNG); stream crudo guardado.
- Con el lugar de la cámara del primer cuadro tocado: si la homografía
  propagada ya cae sobre los blancos, se confirma; si no, se ajustan paneo,
  inclinación y zoom contra ellos en los dos sentidos, con truncado de 30 a
  2 px y el filtro de calidad de la fase 3. Lo que pasa es un cuadro clave
  nuevo; un cuadro con líneas en dos direcciones lejos de la propagada la
  corta. Con "Se ven poco o no hay" solo se llama si el entrenador pintó
  algo. Si SAM falla: nota y la cancha de los puntos.
- Bloque calibration.sam: cuadros clave, cuadros cobrados, referencias,
  trazos (y los enviados), encontrados, confirmados, usados y contradichos.

4 · APP
- Match.references (hasta 6, en filesDir/references/match_NNNN/): "Tomar
  foto" con la app de cámara (TakePicture + FileProvider; se reescala a
  1600 px y se endereza según el EXIF) o "Usar este cuadro" del clip.
  PaintReferenceScreen para pintar encima; se guardan los trazos.
- PitchTouchesScreen: "Tocar puntos" / "Pintar líneas". Al soltar el trazo,
  "¿Qué línea es?" en el dibujo (el segmento más cercano) o "No sé cuál,
  solo es una línea". Las líneas con nombre cuentan como marcas y entran a
  la vista previa (Homography.fitPointsAndLines: se resuelve la inversa,
  donde un punto sobre una recta es una fila lineal).
- PitchTemplate.namedLines con los MISMOS nombres que template.py (fixture
  compartido worker/tests/fixtures/pitch_template_lines.json).
- UploadWorker: CalibrationRequest.build (el cuadro y las referencias del
  partido, n = 1..6) y PUT de cada imagen; si el servidor lo rechaza, el
  video se envía igual.
```

## Trampas conocidas

- **Una línea que pasa por el centro de los puntos perdía una ecuación.** De las tres filas que da cada línea en el DLT se tomaban las dos primeras; con puntos simétricos la de medio campo pasa por el origen normalizado y la primera fila es cero. Se toman las dos más fuertes. Salió con las marcas reales del emulador, no con las pruebas sintéticas.
- **El dibujo de la cancha brincaba al terminar un trazo** (aparecía el botón "solo es una línea" y el aviso cambiaba de dos renglones a uno): el toque siguiente caía en otra línea. El botón queda siempre en su lugar al pintar y el aviso tiene dos renglones mínimo.
- **Una cadena COCO comprimida puede traer `;c=`:** los atributos de la máscara se separan solo antes de un nombre conocido (`counts`, `size`, `png`…).
- **La regla `data/` del `.gitignore` dejaba fuera el paquete `data` de la app** (modelos, cliente de la API, pruebas) desde la etapa 1: se ancló a la raíz (`/data/`). Revisar `git ls-files -o -i --exclude-standard` al agregar carpetas.
- **Con 5 marcas casi todo encaja:** las marcas a ojo del emulador dieron error 0 en la imagen y la cancha 2.4 m corrida contra la cámara real del video sintético. Más marcas, y repartidas, la mejoran; el error en la imagen no lo dice.

## Criterios de aceptación

- [x] Pruebas: worker (83), API Node (23) y PHP (25), app JVM (48), sin llamar a Meta.
- [x] En el emulador: puntos y líneas pintadas con vista previa, una foto con la cámara virtual y un cuadro del video con trazos; el clip enviado a la API PHP local guarda las líneas y las dos imágenes, y el worker calcula la cancha con esas marcas. Capturas en [versions/0.1.0](../versions/0.1.0/README.md).
- [x] Los trazos como objetos con puntos, en una petición aparte del concepto, como pide la documentación de SAM 3.1 ([arquitectura/06](../arquitectura/06-pipeline-analisis.md#la-meta-model-api)).
- [ ] Con la key de SAM 3.1: verificar las dos peticiones y las máscaras (`sam_lines.object_prompt`, `decode_mask`), ver si SAM sigue un trazo de una foto a los cuadros del video, y medir con un video propio de noche o con líneas gastadas cuántos cuadros confirma o corrige SAM y cuánto cuesta.
