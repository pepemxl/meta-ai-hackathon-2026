# 08a · Simulador de SAM 3.1 para desarrollo (fuera de este repo)

**Lee antes:** [13-entorno-desarrollo.md](../13-entorno-desarrollo.md) (completo: qué simula, reglas y contrato), el formato de SAM y la caché de la etapa [08](08-worker.md).

**Esta etapa es distinta a las demás:** se trabaja en la carpeta del prototipo previo (`PP_DEV_SAM_SIM_DIR` del `.env`), **no en este repo**. Es la única sesión que puede leer y usar el código del prototipo, como excepción a la regla 8 de [00-contexto.md](00-contexto.md). Nada de lo que se escribe aquí se copia a este repo, y las sesiones de las demás etapas no leen esa carpeta: el worker solo conoce el contrato HTTP.

```text
Crea un simulador local de la API de SAM 3.1 para desarrollar el worker de
pp-coach-tracker sin gastar créditos. Trabaja SOLO en la carpeta
$PP_DEV_SAM_SIM_DIR (el worker del prototipo previo), no en el repo de
pp-coach-tracker. No copies nada de esta carpeta al repo.

Qué es: un servidor HTTP (Python 3.12, http.server o lo que ya use el
prototipo; nada nuevo sin preguntarme) que responde como la Meta Model API en
la parte que usa el worker para SAM, y por dentro corre el segmentador local
del prototipo (YOLOv8n + tracker IoU + equipo por color de camiseta) sobre
cada chunk.

Rutas (bajo /v1, contrato en docs/13-entorno-desarrollo.md del repo):
- POST /v1/files (multipart, purpose=user_data) → guarda el MP4 en
  SIM_DATA_DIR, {id: "file-<sha256[:16]>", object: "file", bytes,
  purpose}.
- POST /v1/responses, model "sam-3.1", con el file_id y el concepto en
  texto, stream true → stream SSE con el formato de SAM de la etapa 08: una
  marca "<Nf>" por cuadro y por objeto su ordinal (id del track) y
  "<|box;x1=..;y1=..;x2=..;y2=..;c=..|>" normalizadas 0..1. Conceptos:
  "player in <color> jersey" (tracks de persona con pies en la cancha cuyo
  torso se parece más a ese color; los RGB de JerseyColor de la app),
  "soccer ball", "soccer player" (todas las personas en la cancha);
  cualquier otro → stream sin objetos.
- El segmentador corre UNA vez por archivo (caché por sha256 en
  SIM_DATA_DIR); los demás conceptos del mismo chunk salen de ahí.
- POST /v1/responses con model "muse-spark-*": solo con SIM_MUSE=1, una
  respuesta fija que valide contra el json_schema del request (summary y un
  insight que diga que es simulado). Si no, 404 {error: "model not served
  by simulator"}.
- GET /health → {ok: true, simulator: "sam", version}.
- Cabecera X-PP-Simulator: 1 en TODAS las respuestas.
- Sin Authorization: Bearer <algo> → 401 (cualquier valor vale; no uses la
  key real).
- Fallas a propósito: SIM_FAIL_RATE (0..1) → 429 con Retry-After: 1 o 500;
  SIM_CUT_STREAM=1 → corta el stream a la mitad una de cada cinco veces.

Configuración: SIM_PORT (8090), SIM_DATA_DIR, SIM_DETECTOR_LOCAL_TILES (1 por omisión,
para que un chunk de 1 min tarde un par de minutos), SIM_FAIL_RATE,
SIM_CUT_STREAM, SIM_MUSE.

Docker: servicio "sam-sim" en el docker-compose de esa carpeta, publicado
en 127.0.0.1:8090 y unido a la red externa "ppcoachtracker-dev" (para que
el worker lo alcance como http://sam-sim:8090/v1). Solo escucha en la
máquina: no lo publiques en 0.0.0.0 ni lo despliegues.

Herramienta: tools/sim_check.py <chunk.mp4> que llama /files y /responses
para cada concepto e imprime cuántos cuadros y objetos vinieron.

Pruebas (pytest): rutas y errores, formato del stream (lo lee un parser
mínimo de prueba), caché por archivo, conceptos, cabecera X-PP-Simulator,
401, fallas a propósito y SIM_MUSE.

Escribe SIMULATOR.md en esa carpeta: qué es, cómo correrlo, variables, y
que el formato es el documentado (no el verificado) hasta capturar un stream
real de SAM.
```

## Trampas conocidas

- **El formato de SAM no está verificado:** el simulador imita lo que dice la etapa 8. Cuando haya key y un stream real, se ajusta el simulador a ese formato (y no al revés).
- **El simulador es más lento que SAM:** con mosaicos (`SIM_DETECTOR_LOCAL_TILES=2`) tarda unas 3 veces la duración del video en CPU. Por eso en desarrollo los chunks son de 1 min ([12](../12-videos-largos-chunks.md)).
- **No dejarlo publicado:** AGPL-3.0 (YOLOv8n). Solo `127.0.0.1` y la red de Docker local ([13-entorno-desarrollo.md](../13-entorno-desarrollo.md#licencias)).
- **Docker en WSL:** `host.docker.internal` no siempre resuelve; por eso la red externa compartida.

## Criterios de aceptación

- [ ] Las pruebas del simulador pasan.
- [ ] `tools/sim_check.py` sobre un chunk de 1 min devuelve cuadros y objetos para los dos equipos y el balón.
- [ ] Desde el contenedor del worker del repo, `curl http://sam-sim:8090/health` responde con `simulator: "sam"`.
- [ ] El repo de pp-coach-tracker no cambió en esta sesión (`git status` limpio allá).
