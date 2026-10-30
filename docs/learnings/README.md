# Lo aprendido

> **Antecedente.** Este documento viene del diseño y del prototipo previos al hackathon, que tenían un respaldo local (detector YOLOv8n, tracker propio e insights por reglas). La versión que se construye **no tiene respaldo**: solo SAM 3.1 y Muse Spark, y solo dependencias con licencia permisiva. Donde este texto diga otra cosa, mandan [prompts/00-contexto.md](../prompts/00-contexto.md) y el prompt de cada etapa.

Lecciones de construir y medir PP-Coach-Tracker: qué funcionó, qué no y por qué. Cada una viene de algo que pasó (una corrida, un error, una revisión a ojo), con el número o el ejemplo que la respalda. El detalle técnico de cada cambio está en el README de su carpeta; aquí va lo que conviene recordar antes de repetirlo.

| Documento | De qué trata |
|---|---|
| [01-analisis-de-video.md](01-analisis-de-video.md) | Detección, resolución, acercamientos, el balón y la posesión |
| [02-como-medir.md](02-como-medir.md) | Cómo saber si un cambio mejoró algo: corridas, muestras a ojo y sus trampas |
| [03-entorno-y-despliegue.md](03-entorno-y-despliegue.md) | Docker en WSL, memoria, emulador, Cloud Provider sin SSH, permisos y commits |
| [04-video-ancho-y-sam.md](04-video-ancho-y-sam.md) | Video ancho (panorámica, 4K/8K) con SAM 3.1: por qué más resolución no basta, cómo partirlo en videos de 1024 px y unir los tracks |

## Las cinco que más importan

1. **Medir antes de creer.** Tres cambios que "obviamente" ayudaban no ayudaron o lo hicieron de otra forma: el video a 1080p no mejoró la detección, el filtro de Kalman empeoró el balón en su primera versión, y el porcentaje de "balón visto" estaba inflado por balones falsos.
2. **Un número que sube no es una mejora hasta verlo.** El 75 % de balón visto era en buena parte personal de la orilla con chaleco amarillo. Una muestra al azar de 6 a 12 cuadros, revisada a ojo, lo descubrió en minutos.
3. **Una transmisión no es el producto.** La mitad de sus cuadros son acercamientos, repeticiones y tribuna. Las conclusiones sobre transmisiones hay que volver a probarlas con un partido amateur grabado con tripié.
4. **Primero quitar lo que no es juego, después medir el juego.** Descartar acercamientos subió los jugadores por cuadro de 9.2 a 13.7 sin tocar el detector.
5. **El filtro más simple falla en el caso más común.** Un filtro de Kalman que sigue la detección más segura se queda pegado a un balón falso que no se mueve. Hay que dar varios candidatos y elegir la trayectoria por contexto (cerca de los pies de los jugadores).
