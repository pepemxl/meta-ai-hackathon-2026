# Prompts para construir pp-coach-tracker con un agente de IA

Con estos prompts, un agente de programación (Claude Code, Muse Code u otro) escribe todo pp-coach-tracker durante el hackathon, partiendo de un repo que solo tiene `docs/`: la app Android, la API (Node para desarrollo y PHP para Cloud Provider), el worker de análisis y el visor.

**Por qué así:** los organizadores aclararon que el código escrito antes del kickoff (26 de octubre de 2026) se puede usar, pero la entrega tiene que **declarar qué es previo y qué se hizo durante el hackathon** ([reglas](../rules/README.md)). Antes del kickoff se diseñó el proyecto y se hizo un prototipo. De ese trabajo solo se conserva la documentación de `docs/`; todo el código se escribe de nuevo con estos prompts.

Tres decisiones atraviesan todas las etapas:

1. **Sin respaldo:** SAM 3.1 sigue a jugadores y balón, y Muse Spark escribe los insights. No hay detector local (YOLO u otro), tracker local ni insights por reglas. Si la Meta Model API falla después de los reintentos, el trabajo falla con un mensaje claro.
2. **Solo licencias permisivas** (MIT, BSD, Apache-2.0, ISC), y cada dependencia queda anotada en `THIRD_PARTY.md`. No hay modelos locales con licencias copyleft.
3. **Un solo nombre:** la app, el proyecto y los identificadores se llaman **pp-coach-tracker**. La API de producción vive en **https://ppcoachtracker.pepemxl.com**, con una base de datos propia.

## Cómo usarlos

1. **Repo de partida:** este repo, con `docs/` y un `.gitignore` como el de [`04-api-node.md`](04-api-node.md#gitignore). El historial de git es la prueba de qué se hizo durante el hackathon: commits chicos y con fecha real, sin reescribir la historia.
2. **Cada sesión del agente empieza con [`00-contexto.md`](00-contexto.md)** y después el prompt de la etapa. Una etapa por sesión (o por PR), en orden: cada una deja pruebas que la siguiente no debe romper.
3. **No avanzar sin cumplir los criterios de aceptación** de la etapa. Están al final de cada prompt y son verificables (pruebas, comandos, capturas).
4. **Todo el código se escribe en la sesión**, desde `docs/` y el prompt (regla 8 de [`00-contexto.md`](00-contexto.md)). Si hace falta un detalle que no está en la documentación, se agrega a `docs/` y se dice en la entrega.
5. **Los secretos nunca van en el prompt.** El agente pide al usuario lo que haga falta (contraseñas, llaves) o lo lee de un `.env` que no se versiona.

**El estilo visual de la app** (tipo apps de Meta: colores, tipografía, componentes, navegación) está en [diseno.md](../diseno.md); todas las etapas de la app lo siguen y la 1 crea el tema.

Los documentos marcados como **Antecedente** (arquitectura, mejoras, aprendizajes, planes) describen el prototipo previo, que sí tenía respaldo local. Sirven por los contratos, los umbrales medidos y las trampas que costó encontrar; donde contradigan a los prompts, mandan los prompts. Las capturas del prototipo están en [pantallas/](../pantallas/README.md); las de esta construcción van en `docs/versions/`.

## Etapas

| # | Prompt | Qué deja listo | Depende de |
|---|---|---|---|
| 0 | [00-contexto.md](00-contexto.md) | Contexto común para pegar antes de cada prompt | — |
| 1 | [01-app-base.md](01-app-base.md) | App Android: partidos, grabar, importar, clips, almacenamiento, idiomas | — |
| 2 | [02-app-plantel-stats.md](02-app-plantel-stats.md) | Plantel, estadísticas por jugador, importar el reporte de análisis | 1 |
| 3 | [03-app-chunks.md](03-app-chunks.md) | Versión de 10 fps en chunks con YAML (1 min en desarrollo, 5 min en el demo, traslape de 2 a 5 s), vista simple y técnica | 1 |
| 4 | [04-api-node.md](04-api-node.md) | API Node + MySQL: login, subida reanudable en partes de 8 MB, cola de trabajos, consentimiento, resultado del análisis | — |
| 5 | [05-app-subida.md](05-app-subida.md) | App: login, sesión cifrada, subida con WorkManager, estado del análisis | 3, 4 |
| 6 | [06-api-php.md](06-api-php.md) | La misma API en PHP para Cloud Provider, con pruebas de contrato por HTTP | 4 |
| 7 | [07-consentimiento.md](07-consentimiento.md) | Páginas públicas de los textos legales (es/en), diálogo e historial de consentimiento en la app | 5, 6 |
| 8a | [08a-simulador-sam.md](08a-simulador-sam.md) | Simulador local de la API de SAM 3.1 para desarrollo, **fuera de este repo** (sobre el tracker del prototipo) | — |
| 8 | [08-worker.md](08-worker.md) | Worker de análisis solo con Meta: SAM 3.1 (en desarrollo, el simulador), métricas, Muse Spark, caché y costo medido | 4, 8a |
| 9 | [09-despliegue.md](09-despliegue.md) | La API en https://ppcoachtracker.pepemxl.com con una base nueva, y cómo publicar una versión de la app | 6, 7 |
| 10 | [10-app-analisis.md](10-app-analisis.md) | App: pestaña de estadísticas, alineación inicial, ver el análisis, 1080p, tipo de clip | 2, 5, 8 |
| 11 | [11-worker-calidad.md](11-worker-calidad.md) | Worker: corridas guardadas y visor, conceptos de SAM, cuadros que cuentan, cortes, balón, posesión, árbitro | 8 |
| 12 | [12-mascara-cancha.md](12-mascara-cancha.md) | Máscara de la cancha en el worker y en la app (en el YAML de cada chunk) | 3, 11 |
| 13 | [13-cancha-en-metros.md](13-cancha-en-metros.md) | La pregunta de la cancha, los puntos del entrenador, la homografía propagada, las métricas en metros; líneas con SAM (opcional) | 6, 10, 12 |
| 14 | [14-referencias-y-lineas-pintadas.md](14-referencias-y-lineas-pintadas.md) | Líneas pintadas por el entrenador en el cuadro tocado, imágenes de referencia de la cancha y SAM guiado por esos trazos (apagado por omisión) | 13 |

Las etapas 1–3 (app) y 4 (API) se pueden hacer en paralelo con dos agentes; también la 8 y las 10–12 una vez que existe lo que piden.

## Orden sugerido para el hackathon

La evaluación pesa **impacto 30 %, uso de las herramientas de Meta 25 %, técnica 20 % y demo 25 %** ([reglas](../rules/README.md#cómo-se-evalúa-el-proyecto)). **El desarrollo no depende de la key:** el worker se construye contra el simulador de SAM ([13-entorno-desarrollo.md](../13-entorno-desarrollo.md)) con chunks de 1 min. La key hace falta para verificar el formato real de SAM, medir el costo y analizar el clip del demo en chunks de 5 min.

1. **Días 1–2 (26–27 oct): la columna.** 8a → 4 → 6 → 8 contra el simulador. En cuanto haya key: un minuto propio contra SAM real, el parser y el simulador ajustados al formato real, y el costo medido. Desplegar la API (9, primera parte) en cuanto pase la 6.
2. **Días 3–4 (28–29 oct): la app de punta a punta.** 1 → 3 → 5 → 7, más la parte de "ver el análisis" de la 10.
3. **Días 5–6 (30–31 oct): calidad y cancha.** 11 → 12 → 13 (pasos 1 a 4).
4. **Días 7–8 (1–2 nov): el worker desplegado y el demo.** El worker en Modal ($100 en créditos) o un VPS, contra SAM real; la app en perfil `demo` (chunks de 5 min); video demo con video propio según el [guion](../guion-demo.md); ensayo del demo en vivo con un análisis ya hecho a la mano.
5. **Si sobra tiempo:** 2 y el resto de la 10 (plantel, estadísticas, alineación), el paso 5 de la 13 (líneas con SAM).
6. **3 de noviembre: congelar.** El 4, solo la entrega (cierra a las 21:00 ET, 20:00 en la Ciudad de México).

**Qué se puede recortar** sin perder el núcleo: el plantel y las estadísticas por jugador (2 y parte de la 10), la API Node (si se va directo a la PHP, a costa de pruebas más lentas), la opción de 1080p y el visor.

> **Anti-proyectos:** la página del evento pide no construir *"Sports analyzers or coaches"*. Trabajamos con el supuesto de que este proyecto no cuenta como tal, por su enfoque de instrumento de medición ([reglas](../rules/README.md#lo-que-implica-para-pp-coach-tracker)); mientras llega la respuesta de los organizadores, las etapas propias del deporte (2 y parte de la 10) van al final.

## Lo que cuesta no tener respaldo

- **Cada análisis real gasta créditos** ($150 de la Meta Model API). Se itera con el simulador; con la key se mide el costo de un minuto, y en adelante se usa la caché. El clip del demo (2 chunks de 5 min) cuesta unos US$3; un partido completo se manda solo cuando el costo se conoce.
- **Sin key no hay análisis real.** El simulador permite construir y probar todo el flujo, pero no prueba que el worker entienda a SAM: eso solo se sabe con un stream real. Las pruebas automáticas no llaman ni a Meta ni al simulador: usan una API falsa con streams guardados.
- **El simulador no es respaldo:** vive fuera del repo, no se despliega y sus resultados se marcan `sam_simulator` (la API de producción los rechaza).
- **Si Meta se cae durante el demo, el análisis en vivo falla.** Hay que tener a la mano un análisis ya hecho (la app guarda una copia) y decirlo en el video.
- **Los umbrales medidos en el prototipo** ([mejoras/](../mejoras/README.md), [learnings/](../learnings/README.md)) se midieron con otro detector: con las cajas de SAM se vuelven a medir.

## Dependencias esperadas (todas permisivas)

| Parte | Dependencias | Licencias |
|---|---|---|
| Worker | numpy, opencv-python-headless, PyYAML | BSD-3, Apache-2.0 (el wheel trae FFmpeg con LGPL enlazado dinámicamente), MIT |
| API Node | express, multer, mysql2, jsonwebtoken; pruebas: supertest | MIT |
| API PHP | ninguna (PHP 8.3 sin Composer) | — |
| Páginas legales | markdown (Python) | BSD-3 |
| App | Kotlin, Jetpack Compose, CameraX, Media3, WorkManager, OkHttp (MockWebServer en pruebas); el cifrado usa el Android Keystore del sistema | Apache-2.0 |
| Visor | React, Vite, TypeScript | MIT, Apache-2.0 |
| Modelos | SAM 3.1 y Muse Spark por la Meta Model API | Términos de la Meta Model API |

Antes de entregar hay que confirmar las versiones y licencias reales en `THIRD_PARTY.md`.

## Texto para la entrega (callout)

Para el README del repo y el formulario. Ajustar las fechas y la lista al terminar; que diga exactamente lo que pasó.

```markdown
## Prior work and what was built during the hackathon

**Before the hackathon (Oct 20–25, 2026):** we designed pp-coach-tracker and
built a first prototype. From that work we kept only documentation: the
design docs, improvement plans and lessons learned in `docs/`, the legal
texts in `docs/legal/`, screenshots of the prototype in `docs/pantallas/`,
and the step-by-step build prompts in `docs/prompts/`. Commit <hash> is the
starting point: docs only, no application code.

**During the hackathon (Oct 26 – Nov 4, 2026):** all the code in this
repository was written from scratch with an AI coding agent driven by those
prompts: the Android app, the API (Node and PHP, deployed at
https://ppcoachtracker.pepemxl.com), and the analysis worker, where SAM 3.1
tracks players and the ball and Muse Spark writes the coaching insights, with
no local model fallback. Also the pitch calibration and the metrics in meters.
To save credits during development, the worker was exercised against a local
stand-in for the SAM API built on our pre-hackathon prototype tracker
(YOLOv8n); it is not part of this repository or the submission, and every
analysis in the demo and in production comes from SAM 3.1.
The git history shows every step.

**Third-party components:** permissive licenses only (MIT, BSD, Apache-2.0);
see THIRD_PARTY.md. Vision and language models are Meta's, via the Meta Model
API.
```

## Cómo verificar la construcción

- **API:** las pruebas de contrato pasan contra Node en memoria, Node con MySQL y PHP por HTTP (incluida la subida en partes de un chunk de 5 min); `https://ppcoachtracker.pepemxl.com/health` responde con `db: true`.
- **App:** las pruebas JVM pasan y los flujos de [arquitectura/04-flujos.md](../arquitectura/04-flujos.md) funcionan en el emulador contra la API local.
- **Worker:** `npm run worker:test` pasa sin red. Con el simulador, un clip de chunks de 1 min termina en `ready` con `sam_simulator`. Con la key, el clip del demo en chunks de 5 min termina en `ready` con `backends.segmenter = "meta_sam"` y `backends.analyst = "muse_spark"`.
- **Licencias:** `git grep -i -E "yolo|ultralytics|onnx"` no encuentra nada fuera de `docs/`, y `THIRD_PARTY.md` lista todas las dependencias.
- **Documentación de arquitectura:** al terminar, actualizar [arquitectura/](../arquitectura/README.md) a lo que se construyó y quitarle el aviso de antecedente.
