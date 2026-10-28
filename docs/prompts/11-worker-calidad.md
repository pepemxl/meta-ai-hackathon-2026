# 11 · Worker: medir mejor (visor, cuadros que cuentan, balón)

**Lee antes, completos:** [mejoras/02-descartar-acercamientos.md](../mejoras/02-descartar-acercamientos.md), [mejoras/03-otras-mejoras.md](../mejoras/03-otras-mejoras.md), [learnings/01-analisis-de-video.md](../learnings/01-analisis-de-video.md) (secciones de detección a árbitro) y [learnings/02-como-medir.md](../learnings/02-como-medir.md). Tienen la regla de cada mejora, los umbrales y por qué quedaron así. Se midieron con el detector local de la primera construcción; aquí todo sale de SAM 3.1, así que los umbrales se vuelven a medir.

```text
Mejora el worker de la etapa 8 con lo que salió de revisar corridas reales.
Sin respaldo y sin detector local: todo sale de las cajas de SAM 3.1. Para
iterar sin gastar créditos, las corridas se rehacen desde la caché de
respuestas de Meta (etapa 8).
Cada cambio se mide antes y después con la misma corrida (compare_runs.py)
y se revisa a ojo en el visor; no se acepta un número que mejora sin mirar
qué se agregó o qué se quitó.

1. CORRIDAS GUARDADAS Y VISOR
- debug.py: con DEBUG_DIR o `analyze --debug-dir`, guarda result.json,
  tracks.json (columnas frame, t_ms, track, label, team, x, y, w, h, conf,
  interp, y más adelante mx, my; info por cuadro) y los chunks.
- viewer/: página local (Vite + React + TypeScript), servida en Docker en
  127.0.0.1:8098 (`npm run viewer:up` desde api/). Lista las corridas de
  worker/.local/runs/, reproduce el video con las cajas de cada jugador y
  del balón, la posesión como la cuenta el worker en una línea de tiempo,
  los cuadros descartados marcados y el resultado completo.
- tools/compare_runs.py (tabla Markdown entre corridas: jugadores por
  cuadro, tamaño de cajas, balón, posesión, tracks, tiempo) y
  tools/rescore.py (recalcula balón y posesión de una corrida sin detectar
  de nuevo, desde la caché). make_chunks.py sin quedarse sin memoria en
  videos largos.

2. CONCEPTOS DE SAM
- Mide con compare_runs.py qué conceptos dan más jugadores y balón por
  cuadro sin agregar público ni personal de la orilla ("player in <color>
  jersey" contra "soccer player" + color, "soccer ball" contra "ball"), y
  cuánto cuesta cada uno. Deja los mejores en config, con el resultado en
  worker/README.md.

3. CUADROS QUE CUENTAN (shots.py, cuts.py)
- cuts.py: un corte de cámara cuando el histograma de tono y saturación
  salta 0.44 o más entre dos cuadros (una toma con movimiento llega a
  ~0.27). El tracker empieza de nuevo en cada corte.
- shots.py: un cuadro no cuenta si una persona mide más de 1/3 del alto de
  la imagen (acercamiento, festejo, tribuna), salvo que haya jugadores
  chicos CON LOS PIES EN EL PASTO detrás; tampoco sin cancha. La regla va
  por tramos, no por cuadro; una toma corta con dos tercios descartados se
  descarta entera, si no cuadro por cuadro. Lo descartado no entra a
  ninguna métrica, parte los tracks y no une rachas de posesión a través de
  él. El resultado lista los tramos (video.discarded) y frames_used, y avisa
  si quedan menos de 30 s que cuentan.

4. BALÓN (ball.py)
- Varios candidatos por cuadro, una trayectoria por candidato (cada una con
  su Kalman), y se elige la que dura y pasa junto a los pies. Huecos de hasta
  5 cuadros se rellenan solo DENTRO de una trayectoria (interp=1).
- Descarta balones de SAM sin pasto alrededor (un tercio de pasto en un
  cuadrado de 4 veces su tamaño).

5. POSESIÓN Y EQUIPOS
- La racha mínima (5) se mide en cuadros CON balón, y se corta tras 2 s
  sin verlo. Histéresis: se gana a 0.06 y se suelta cuando nadie del equipo
  está a 0.09.
- teams.py: un tercer grupo de color, chico y lejos de los dos equipos,
  queda sin equipo (el árbitro).

Actualiza las pruebas: un corte se ve y un paneo no; tramo corto que se
rellena; repetición descartada sin unir la posesión; persona frente a la
cámara con jugadores detrás; hueco corto rellenado y largo no; nada a
través de un tramo descartado; balón falso aislado; anuncio con más
confianza que el balón real; histéresis; árbitro de amarillo con un equipo
de blanco.
```

## Trampas conocidas

- **Un Kalman que sigue la detección más segura se pega a un anuncio** que se detecta como balón en cada cuadro: siempre "encaja" con su predicción. Por eso una trayectoria por candidato.
- **Rellenar entre dos trayectorias distintas inventa un pase.** Solo dentro de una.
- **El personal de la orilla** (chalecos amarillos junto a los anuncios) pasa el filtro de público y hasta recibe equipo. Contar "jugadores chicos detrás" solo con los pies en el pasto.
- **Unir tracks partidos después del tracker no sirvió** (con el tracker local de la primera construcción) (5 de 16 uniones eran el mismo jugador), ni alargarlos con detecciones débiles (casi todas eran de la orilla). No repetirlo sin la línea de banda.
- **Contar lo descartado:** la primera versión contaba 625 balones descartados y eran 141 (la primera detección de cada reinicio se contaba dos veces).
- **Video de terceros:** las corridas con una transmisión se quedan en `worker/.local/`; nunca en el repo ni en el demo.

## Criterios de aceptación

- [ ] `npm run worker:test` pasa, con las pruebas nuevas.
- [ ] Sobre el mismo video propio, `compare_runs.py` contra la corrida de la etapa 8 muestra más jugadores por cuadro y más balón visto, sin más personas fuera de la cancha, y el costo por minuto anotado.
- [ ] En el visor, los acercamientos y repeticiones salen marcados como descartados, y la posesión no se une a través de ellos.
