# pp-coach-tracker: documentación

Un celular que mide la cancha en metros: SAM 3.1 sigue a los jugadores y la calibración del entrenador pone sus posiciones en metros, también en canchas con líneas borradas o poca luz. Hecho para el **Meta Global AI Developer Hackathon** (Cerebral Valley, del 26 de octubre al 4 de noviembre de 2026).

**Video del demo:** [pp-coach-tracker: a phone camera that measures the pitch in meters](https://youtu.be/ZZ1fL0aZ5mI) (YouTube).

**Para construirlo, empieza por [prompts/](prompts/README.md).** Ahí está el orden de las etapas y el contexto que se pega en cada sesión del agente. Qué es previo y qué se hizo durante el hackathon está declarado en [PRIOR_WORK.md](../PRIOR_WORK.md) (en inglés, con un resumen en español).

La versión que se construye **no tiene respaldo local**: SAM 3.1 sigue a jugadores y balón, y Muse Spark escribe los insights, los dos por la Meta Model API. SAM 3.1 y Muse Spark ya se verificaron con la API real ([results/](results/README.md)); mientras se desarrollaba, el worker usó un simulador local de SAM, fuera del repo, para no gastar créditos ([13-entorno-desarrollo.md](13-entorno-desarrollo.md)). Usa solo dependencias con licencia permisiva (más `meta-sam-parser`, de Meta, para las máscaras de SAM). La API de producción vive en **https://ppcoachtracker.pepemxl.com** y el worker de producción corre en Docker en la máquina de desarrollo.

## Para construir

| Documento | Contenido |
|---|---|
| [arquitectura/](arquitectura/README.md) | Cómo está construido con diagramas: estructura del repo, app, API y modelo de datos, flujos de punta a punta, entornos y el pipeline de análisis; qué existe y qué falta |
| [prompts/](prompts/README.md) | Prompts por etapa para que un agente de IA construya la app, la API, el worker y el despliegue, con criterios de aceptación y trampas conocidas |
| [legal/](legal/README.md) | Textos legales (es/en): consentimiento de uso de video al subirlo, aviso de privacidad y formato de consentimiento para jugadores y tutores |
| [10-privacidad-jugadores.md](10-privacidad-jugadores.md) | Privacidad: IDs en el pipeline, nombres opcionales que no salen a terceros, LFPDPPP y GDPR |
| [08-uso-de-video.md](08-uso-de-video.md) | Qué video se puede usar en pruebas y qué nunca se publica (transmisiones, datasets, partidos amateur); el video de stock, el generado con IA y el propio (dos canchas reales) |
| [12-videos-largos-chunks.md](12-videos-largos-chunks.md) | Videos en chunks de 10 fps con un YAML por chunk (contrato entre app, API y worker): 1 min en desarrollo y 5 min en el demo, con 2 a 5 s de traslape, y subida en partes de 8 MB |
| [13-entorno-desarrollo.md](13-entorno-desarrollo.md) | Entorno de desarrollo: el simulador local de la API de SAM 3.1 (el detector local del prototipo, en Docker junto al worker, fuera del repo) con el que se desarrolló sin gastar créditos, cómo no se mezcla con SAM real y cómo pasar al demo |
| [14-modo-demo.md](14-modo-demo.md) | Cuentas demo: la app completa sin subir video ni gastar crédito de Meta, con un clip de prueba y su análisis dentro de la APK |
| [15-flujo-completo.md](15-flujo-completo.md) | El flujo completo de la app en 14 pasos, del partido nuevo a la página del jugador, probado en un emulador con SAM 3.1 y Muse Spark reales, con una lámina de capturas |
| [11-stats-jugadores.md](11-stats-jugadores.md) | Estadísticas por jugador: qué llena el análisis y qué el entrenador, formato del reporte y cálculos agregados |
| [results/](results/README.md) | Resultados medidos sobre video real, con equipos anonimizados: el detector local del prototipo, el mismo del simulador de SAM (YOLOv8n, fuera del repo) contra SAM 3.1, y la cancha en metros; el **hito** de calibrar una cancha de tierra sin líneas visibles con unos cuantos toques del entrenador ([results/04](results/04-hito-cancha-sin-lineas.md)), y un partido completo de 102 min con los dos, tiempo por tiempo ([results/05](results/05-partido-completo-sam-vs-local.md), [results/07](results/07-partido-completo-dos-tiempos.md)) |
| [diseno.md](diseno.md) | Estilo visual de la app, tipo apps de Meta: colores claro y oscuro, tipografía, componentes, navegación e ícono |

## Antecedentes (diseño y prototipo previos al hackathon)

Describen el prototipo hecho antes del kickoff, que tenía un respaldo local. Sirven por los contratos, los umbrales medidos y las trampas encontradas; donde contradigan a los prompts, mandan los prompts.

| Documento | Contenido |
|---|---|
| [learnings/](learnings/README.md) | Lo aprendido: análisis de video, cómo medir los cambios y el entorno de desarrollo y despliegue |

Las versiones de la app, de la 0.1.0 a la 0.4.4, con sus capturas, están en [versions/](versions/README.md) ([prompts/09-despliegue.md](prompts/09-despliegue.md#publicar-una-versión-de-la-app)).

## Resumen en una línea

El entrenador graba una jugada con el celular, la app la sube a nuestra API, un worker usa **SAM 3.1** para seguir jugadores y balón, pone sus posiciones en metros sobre la cancha con los puntos que marca el entrenador y **Muse Spark** genera insights tácticos que se ven en la app.

## Fechas clave (hora del Este de EE. UU.)

- **26 de octubre:** kickoff
- **4 de noviembre, 21:00:** entrega de proyectos (20:00 en la Ciudad de México)
- **9 de noviembre:** final en vivo y ganadores
