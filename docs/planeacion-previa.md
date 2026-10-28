# Planeación previa (conversación anterior)

> **Antecedente.** Este documento viene del diseño y del prototipo previos al hackathon, que tenían un respaldo local (detector YOLOv8n, tracker propio e insights por reglas). La versión que se construye **no tiene respaldo**: solo SAM 3.1 y Muse Spark, y solo dependencias con licencia permisiva. Donde este texto diga otra cosa, mandan [prompts/00-contexto.md](prompts/00-contexto.md) y el prompt de cada etapa.

> La conversación compartida (https://claude.ai/share/0e9791eb-46bf-4513-8dda-3d1bbe1ff3bb) no se pudo descargar automáticamente. Aquí están solo los fragmentos que se pegaron en la sesión del 20 de octubre de 2026. Si hay más decisiones en esa conversación, pégalas aquí.

## Idea elegida: análisis táctico de fútbol con SAM 3.1

Es el proyecto que mejor encaja con este hackathon. SAM 3.1 segmenta y sigue objetos en video a partir de texto ("jugador de camiseta roja", "balón"), así que puede sustituir buena parte del pipeline de YOLO + ByteTrack que habíamos visto. La idea:

1. Se sube el video de un partido (de una o varias cámaras).
2. SAM 3.1 detecta y sigue a cada jugador y al balón, y las posiciones se proyectan al campo con homografía.
3. Muse Spark analiza las trayectorias y genera un reporte en lenguaje natural: "el lateral izquierdo dejó espacio a su espalda 12 veces", mapas de calor, presión, etc.

## Borrador de la aplicación

Ver [02-propuesta.md](02-propuesta.md).

Consejos que quedaron de esa conversación:

- Si hay código o un prototipo del tracking, poner el link de GitHub: la habilidad técnica vale el 50% de la evaluación.
- No exagerar lo que ya está construido: describir con precisión lo que se hizo y las técnicas usadas.
