# 13 · La cancha en metros (homografía)

**Lee antes, completo:** [mejoras/04-homografia-por-cuadro.md](../mejoras/04-homografia-por-cuadro.md) (el diseño, cada fase con sus resultados y lo que cambió al construirla), [learnings/01-analisis-de-video.md](../learnings/01-analisis-de-video.md) (secciones de homografía y métricas en metros), [arquitectura/04-flujos.md](../arquitectura/04-flujos.md) (flujos 3 y 5) y [arquitectura/06-pipeline-analisis.md](../arquitectura/06-pipeline-analisis.md#la-cancha-en-metros).

Es la etapa más grande: hazla en este orden, con un commit (o PR) por paso y sus pruebas.

```text
Pon a los jugadores en metros sobre la cancha. Regla de todo el trabajo:
MEJOR SIN METROS QUE CON METROS FALSOS. Las métricas de la imagen siempre
están; las de metros van aparte y dicen de qué parte del video salen.

PASO 1 · LA PREGUNTA DE LA CANCHA (app, API y worker; 0.3.8)
- Al tocar "Enviar a análisis" (después del consentimiento), una vez por
  partido: "¿Calculamos la cancha?" con "Sí, se ven bien" (lines), "Se ven
  poco o no hay" (no_lines) y "No calcular la cancha" (off); casilla "Usar
  esta respuesta para los demás videos de este partido" marcada. En la
  página del video, la sección "Calcular la cancha" con la respuesta, el
  interruptor y "Cambiar respuesta"; bloqueada al preparar, subir o enviar.
  Se guarda en library.json (pitchMode del clip y del partido).
- API (Node y PHP): POST /clips acepta analysis: {pitch_mode} (lines,
  no_lines u off; sin el campo, null; otro valor, 400). clips.pitch_mode
  VARCHAR(16) NULL, migración en Schema::migrate. GET /internal/jobs/next lo
  da en options.pitch_mode. Prueba de contrato en las dos.
- Worker: JobOptions.pitch_mode; off = nada de cancha; no_lines = solo los
  puntos del entrenador; lines o null = puntos y, si está encendido, SAM
  para las líneas (paso 5).

PASO 2 · PLANTILLA Y SOLVER, CON DATOS SINTÉTICOS (worker, sin video)
- calibration/template.py: cancha de 11 (105×68) y de 7 (60×40), medidas
  configurables; origen en el centro, x a lo largo, y a lo ancho (y > 0 la
  banda lejana). Puntos con nombre (corner_far_left, halfway_near, center,
  circle_far, spot_left, post_near_left, box_far_left_goal, box_far_left,
  goal_area_…) y líneas con nombre.
- calibration/solver.py: homografía desde puntos y rectas (DLT normalizado
  con las dos, refinado sobre la distancia en píxeles), RANSAC, y el modelo
  de cámara (lugar fijo; paneo, inclinación y zoom): Camera.homography,
  Camera.fit, Camera.params_for(H, start) y Camera.from_homography(H),
  que ajusta las 7 cifras de la cámara a una homografía real.
- Pruebas: proyectar la plantilla con una cámara de transmisión simulada,
  ruido de 0.5–1 px, y recuperarla: con puntos (< 3 px), con rectas vistas
  sin sus extremos, RANSAC con dos marcas confundidas, la cámara de un
  cuadro, y el modelo de cámara con 2 rectas y un punto (< 1 m).

PASO 3 · LOS PUNTOS DEL ENTRENADOR (app, API y worker; 0.3.9)
- App: con "Calcular la cancha" encendido, "Marcar puntos de la cancha"
  abre PitchTouchesScreen: una barra elige el cuadro; se toca un punto de
  la imagen y luego cuál es en un dibujo de la cancha (11 o 7, según la
  alineación del partido o a mano). data/PitchTouches.kt: PitchTemplate con
  los MISMOS nombres que template.py (prueba que lo compara), y una
  Homography de vista previa que dibuja la plantilla en violeta con 4 o más
  puntos. Con exactamente 4 pide un quinto: con 4 cualquier nombre encaja.
  Con "Se ven poco o no hay" y sin puntos, la página avisa que así no se
  calcula la cancha. Los puntos se guardan con el clip (touches) y
  UploadWorker los manda justo después de registrar el clip; si el servidor
  los rechaza, el video se envía igual.
- API: POST /clips/:id/calibration {pitch: {size, length_m?, width_m?},
  frames: [{t_ms | frame, points: [{name, x, y}]}]}: de 4 a 40 puntos por
  cuadro, sin nombres repetidos, x, y en 0..1 (calibration.js /
  Calibration.php); 409 cuando el worker ya tomó el clip. Se guarda en
  clips.calibration (TEXT) y el trabajo lo trae como `calibration`.
- Worker (calibration/__init__.py, propagate.py): la homografía del cuadro
  tocado (si la mediana del error pasa de 1.5 % del alto, no se usa y una
  nota lo dice), propagada por su toma con KLT sobre el pasto (dentro del
  polígono, fuera de las cajas, en una copia de 640 px) y findHomography con
  RANSAC; la cadena se corta en un corte o donde coinciden menos del 30 %
  (o menos de 30) puntos, y donde menos del 90 % de los pies cae en la
  cancha con 5 m de margen. Cada jugador gana mx, my. tracks.json guarda la
  homografía de cada cuadro, quién la dio y la plantilla; el visor la
  dibuja ("Líneas calculadas"). `analyze --touches archivo.json` para
  probar sin la API. El resultado trae el bloque `calibration`.

PASO 4 · MÉTRICAS EN METROS (worker y app; 0.3.10)
- metrics.pitch_metrics: solo sobre los cuadros que cuentan con
  homografía, y solo con 10 s o más de ellos. Velocidades entre la posición
  MEDIA DE CADA SEGUNDO; un paso de más de 11 m/s es un cambio de track y
  no cuenta (dropped_steps); los pies a más de 2 m fuera de las líneas no
  cuentan. Por equipo: meters_per_minute, speed_p95, shape (cx, cy,
  length, width), heatmap 12×8 sobre la cancha (la banda lejana arriba),
  player_seconds; por track: distance_m, seconds, top_speed. Bloque `pitch`
  del resultado con length_m, width_m, approximate (medidas por omisión),
  seconds y frames_with_homography.
- Avisos: la limitación de "coordenadas de la imagen" se cambia por de qué
  porcentaje salen los metros, y otra dice si la cancha es la de omisión.
  Muse Spark recibe los agregados por equipo en metros (pitch_meters).
- App: ClipAnalysis lee el bloque; sección "En la cancha, en metros" con
  metros por jugador por minuto, velocidad en los piques (km/h), ancho y
  largo del equipo (los que se ven), y la cancha vista desde arriba con el
  mapa de cada equipo y las líneas.

PASO 5 · LÍNEAS CON SAM (opcional, APAGADO por omisión)
- calibration/sam_lines.py (PP_PITCH_SAM=1): los cuadros clave en un MP4
  corto, dos conceptos (líneas de la cancha y círculo central), las
  máscaras de SAM; lector tolerante del formato (RLE de COCO numérico o
  comprimido, PNG, del cuadro o de la caja) y stream crudo guardado.
- Con el lugar de la cámara de un cuadro tocado, busca paneo, inclinación
  y zoom para que la plantilla caiga sobre las líneas de SAM, medido en
  los dos sentidos; corrige la propagada, busca en tomas sin ella y corta
  la propagación donde las líneas la contradicen. Sin detector local de
  líneas: si SAM falla, no hay líneas y queda la propagación de los puntos
  (una nota lo dice).
- Haz este paso solo si sobra tiempo; antes, el uso de SAM para jugadores y
  balón y de Muse Spark (etapa 8) con la key real.
```

## Trampas conocidas

- **Que encaje no quiere decir que esté bien:** con 2 rectas y el modelo de cámara el error en las rectas es < 1 px y la cancha sale 3–8 m corrida. Medir el error en la cancha, no en lo que se ajustó.
- **Con 4 puntos cualquier nombre encaja:** la homografía pasa exacta por ellos aunque uno esté mal; solo el dibujo lo muestra. Por eso el quinto punto.
- **`Camera.from_homography` con la fórmula cerrada dejaba la cancha 55 px corrida** con una homografía real: ajustar las 7 cifras.
- **"90 % de los pies en la cancha con 2 m de margen" rechazaba hasta el cuadro bien tocado** por el personal a 1–3.5 m de la banda: 5 m.
- **La velocidad cuadro a cuadro suma metros que nadie corrió** (el temblor de la caja en la banda lejana): por segundos.
- **Sin ningún punto marcado no se puede calibrar** un solo cuadro: el lugar, la inclinación y el zoom se compensan. Nada automático sin puntos.
- **Las líneas en una transmisión aceptaban cuadros malos** (con el detector local de la primera construcción) (otra cámara alta parecida, paneos amplios, cortes no detectados): por eso quedaron apagadas. Probar las de SAM con un video de tripié propio antes de encenderlas.
- **Una cadena COCO comprimida trae `;` y `=`:** los atributos de una máscara de SAM se separan solo donde sigue `nombre=`.
- **El lector de SAM se prueba con una máscara de cancha completa,** no solo con una chica: así salió el error del `;`.

## Criterios de aceptación

- [ ] Todas las pruebas pasan (API Node y PHP, app y worker), sin llamar a Meta.
- [ ] Video sintético propio de una cancha de 7 con cámara fija ([learnings](../learnings/01-analisis-de-video.md#las-métricas-en-metros)): 5 puntos tocados en el emulador, el worker calcula la cancha en el 100 % de los cuadros, y con jugadores simulados a velocidades conocidas el resultado da esas velocidades (± 1 %).
- [ ] La app muestra "En la cancha, en metros" y su vista desde arriba; capturas con el video sintético.
- [ ] Con `pitch_mode` en `off` no hay `calibration` ni `pitch` en el resultado, aunque haya puntos.
