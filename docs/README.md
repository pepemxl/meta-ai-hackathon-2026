# pp-coach-tracker: documentación

Análisis táctico con IA para fútbol amateur, hecho para el **Meta Global AI Developer Hackathon** (Cerebral Valley, del 26 de octubre al 4 de noviembre de 2026).

**Para construirlo, empieza por [prompts/](prompts/README.md).** Ahí está el orden de las etapas, el contexto que se pega en cada sesión del agente y el texto para declarar en la entrega qué es previo y qué se hizo durante el hackathon.

La versión que se construye **no tiene respaldo local**: SAM 3.1 sigue a jugadores y balón, y Muse Spark escribe los insights, los dos por la Meta Model API. Mientras se desarrolla, el worker usa un simulador local de SAM ([13-entorno-desarrollo.md](13-entorno-desarrollo.md)); el demo y producción usan SAM 3.1. Usa solo dependencias con licencia permisiva, y la API de producción vive en **https://ppcoachtracker.pepemxl.com**.

## Para construir

| Documento | Contenido |
|---|---|
| [prompts/](prompts/README.md) | Prompts por etapa para que un agente de IA construya la app, la API, el worker y el despliegue, con criterios de aceptación y trampas conocidas |
| [rules/](rules/README.md) | Reglas Oficiales del hackathon (copia textual en inglés), resumen en español y lo que implican: evaluación, premios, propiedad intelectual, licencias y anti-proyectos |
| [legal/](legal/README.md) | Textos legales (es/en): consentimiento de uso de video al subirlo, aviso de privacidad y formato de consentimiento para jugadores y tutores |
| [10-privacidad-jugadores.md](10-privacidad-jugadores.md) | Privacidad: IDs en el pipeline, nombres opcionales que no salen a terceros, LFPDPPP y GDPR |
| [08-uso-de-video.md](08-uso-de-video.md) | Qué video se puede usar en pruebas y qué nunca se publica (transmisiones, datasets, partidos amateur) |
| [12-videos-largos-chunks.md](12-videos-largos-chunks.md) | Videos en chunks de 10 fps con un YAML por chunk (contrato entre app, API y worker): 1 min en desarrollo y 5 min en el demo, con 2 a 5 s de traslape, y subida en partes de 8 MB |
| [13-entorno-desarrollo.md](13-entorno-desarrollo.md) | Entorno de desarrollo: simulador local de la API de SAM 3.1 (fuera del repo), cómo no se mezcla con SAM real y cómo pasar al demo |
| [11-stats-jugadores.md](11-stats-jugadores.md) | Estadísticas por jugador: qué llena el análisis y qué el entrenador, formato del reporte y cálculos agregados |
| [diseno.md](diseno.md) | Estilo visual de la app, tipo apps de Meta: colores claro y oscuro, tipografía, componentes, navegación e ícono |
| [05-plan-hackathon.md](05-plan-hackathon.md) | Plan día por día con las etapas de los prompts, recortes, riesgos y checklist de entrega |
| [guion-demo.md](guion-demo.md) | Guion del video demo escena por escena, material necesario y plan para la final en vivo |

## Antecedentes (diseño y prototipo previos al hackathon)

Describen el prototipo hecho antes del kickoff, que tenía un respaldo local. Sirven por los contratos, los umbrales medidos y las trampas encontradas; donde contradigan a los prompts, mandan los prompts.

| Documento | Contenido |
|---|---|
| [01-evento.md](01-evento.md) | De qué trata el evento, quién lo organiza, fechas, tracks, premios y reglas |
| [02-propuesta.md](02-propuesta.md) | Texto de la aplicación (problema, solución, alcance) |
| [03-arquitectura.md](03-arquitectura.md) | Diseño inicial: app Android, API en Cloud Provider, worker de IA, modelo de datos y flujo |
| [arquitectura/](arquitectura/README.md) | Arquitectura del prototipo con diagramas: estructura del repo, app, API y modelo de datos, flujos de punta a punta, despliegue y el pipeline de análisis |
| [04-plan-aprendizaje-meta.md](04-plan-aprendizaje-meta.md) | Cómo aprender SAM 3.1, Muse Spark y la Meta Model API |
| [07-plan-android.md](07-plan-android.md) | Plan de la app Android pp-coach-tracker por versiones |
| [09-dataset-quantigo.md](09-dataset-quantigo.md) | Dataset de Quantigo: para qué sirve (evaluar el tracking, no la demo) y plan de evaluación |
| [mejoras/](mejoras/README.md) | Mejoras del análisis medidas en el prototipo: máscara de la cancha en el teléfono, descartar acercamientos, homografía de cada cuadro y otras (balón, árbitro, tracks) |
| [learnings/](learnings/README.md) | Lo aprendido: análisis de video, cómo medir los cambios y el entorno de desarrollo y despliegue |
| [pantallas/](pantallas/README.md) | Capturas y notas del prototipo, versión por versión (0.1.0 a 0.3.10) |
| [planeacion-previa.md](planeacion-previa.md) | Notas de la planeación inicial (parciales) |

Las versiones nuevas de la app, con sus capturas, van en `versions/` ([prompts/09-despliegue.md](prompts/09-despliegue.md#publicar-una-versión-de-la-app)).

## Resumen en una línea

El entrenador graba una jugada con el celular, la app la sube a nuestra API, un worker usa **SAM 3.1** para seguir jugadores y balón, pone sus posiciones en metros sobre la cancha con los puntos que marca el entrenador y **Muse Spark** genera insights tácticos que se ven en la app.

## Fechas clave (hora del Este de EE. UU.)

- **26 de octubre:** kickoff
- **4 de noviembre, 21:00:** entrega de proyectos (20:00 en la Ciudad de México)
- **9 de noviembre:** final en vivo y ganadores
