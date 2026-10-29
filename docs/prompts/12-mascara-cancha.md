# 12 · Máscara de la cancha (worker y app)

**Lee antes, completos:** [learnings/01-analisis-de-video.md](../learnings/01-analisis-de-video.md#la-máscara-de-la-cancha) y [12-videos-largos-chunks.md](../12-videos-largos-chunks.md) (el YAML es contrato).

```text
Calcula en cada cuadro qué parte de la imagen es cancha, primero en el
worker y después en la app, que la manda en el YAML de cada chunk.

1. WORKER (pitch.py)
- Rejilla de 160×90 del cuadro en HSV; pasto con tono 35–85, saturación
  > 60 y valor > 40. Cierre de 5×5, la mancha más grande, su contorno
  convexo simplificado a 12 puntos como máximo. Si menos del 70 % del
  polígono es pasto, NO es cancha (cámara detrás de la portería, portero de
  verde, VAR). Devuelve la fracción de pasto y el polígono (0..1).
- Un cuadro sin cancha queda descartado (shots.py); con cancha, una caja
  de SAM cuenta si sus pies caen en el polígono ampliado 0.02 hacia afuera.
  La persona más alta (acercamientos) se mide con TODAS las cajas.
- Ahorro de créditos (opcional, medirlo): si un tramo largo (>= 10 s) de un
  chunk no tiene cancha, no se manda a SAM; se recorta el MP4 a los tramos
  con cancha y se ajusta el map de frames. Solo si el ahorro medido pasa
  del 10 %.
- debug.py guarda el polígono por cuadro; el visor lo dibuja.

2. APP (0.3.9)
- data/PitchMask.kt: lo mismo que pitch.py en Kotlin puro (misma rejilla,
  mismos umbrales, mismo resultado), con pruebas JVM sobre rejillas hechas a
  mano y contra valores calculados con pitch.py.
- analysis/PitchSampler.kt: decodifica cada chunk con MediaCodec a búferes
  (sin superficie), lee de cada cuadro YUV solo los puntos de la rejilla y
  los pasa a RGB (BT.601), con la rotación del video.
- ChunkWorker lo corre después de codificar cada chunk y antes de escribir
  su YAML (el último 15 % del progreso). Si falla, el YAML sale sin `pitch`
  y el chunk sirve igual.
- YAML: sección `pitch` con method "hsv_v1", grid [160, 90], with_pitch y
  frames [[frame, fracción, [x1, y1, …]], …]. La API no cambia.
- Si menos de la mitad de los cuadros tiene cancha, el clip avisa: "En este
  video casi no se ve la cancha; el análisis puede salir vacío. Puedes
  enviarlo igual."

3. WORKER LEE LA DE LA APP
- chunks.py lee `pitch` de cada chunk; Clip.pitch_masks() la entrega por
  cuadro, sin el traslape. Donde no viene (o no se puede leer: method
  desconocido, polígono con menos de 3 puntos o impar), se mide del video.
  `notes` dice cuántos cuadros vinieron de la app y cuántos se midieron.
- tools/make_chunks.py --pitch escribe la sección como la app.
```

## Trampas conocidas

- **El filtro de público estaba al revés** en la primera versión: solo filtraba en cuadros con mucho pasto, y en uno sin cancha dejaba pasar todo.
- **Filtrar antes de medir cambia lo que se mide:** si la persona más alta se mide solo con las detecciones de la cancha, los acercamientos (pies fuera de la imagen) dejan de verse.
- **No mandar a SAM solo lo que se va a descartar de todos modos:** el suavizado de `shots.py` devuelve a contar los tramos cortos sin pasto; si no se mandaron, cuentan con 0 jugadores. Por eso el recorte es solo para tramos largos.
- **Rejilla de 64×36 en la app y 160×90 en el worker no dan lo mismo:** usar la misma desde el principio.
- **La máscara no quita al personal de la orilla:** entre la banda y los anuncios también hay pasto. Para eso hace falta la línea de banda (etapa 13).
- **No subir solo los tramos con cancha** (la fase 4 del plan): ahorraría ~11 % y complica el contrato. No se hizo.

## Criterios de aceptación

- [ ] `npm run worker:test` y las pruebas JVM pasan (polígono sin huecos por jugadores ni líneas, tribuna fuera y banda dentro, cuadro sin pasto, contorno con poco pasto, la sección del YAML leída y validada).
- [ ] Sobre el mismo video, la app (con la misma rejilla) y `pitch.py` coinciden en qué cuadros tienen cancha.
- [ ] Un chunk sin la sección `pitch` sigue funcionando.
- [ ] Medido en un teléfono real: cuánto alarga "Preparando" (límite del plan: 20 %).
