# El partido completo: 102 min con SAM 3.1 y con el detector local

**Fecha:** 4 y 5 de noviembre de 2026.
**Qué se midió:** los dos tiempos de un partido profesional, cada uno analizado con el detector local de desarrollo (YOLOv8n, fuera del repo) y con SAM 3.1 real por la Meta Model API, con las mismas reglas del worker en los dos: tracks unidos entre mosaicos, la posesión de la [versión 0.4.4](../versions/0.4.4/README.md) y las pausas fuera de las métricas.

- **El video:** la cámara táctica, que se panea toda la toma, analizada como la sube la app: chunks de 5 min a 1280×720 y 10 fps. Es de terceros y no está en el repo ([08-uso-de-video.md](../08-uso-de-video.md)). Los equipos van anonimizados: **A** de rojo y **B** de blanco.
- **Primer tiempo:** 49 min 6 s, el de [05](05-partido-completo-sam-vs-local.md), vuelto a correr con las respuestas de SAM guardadas (solo se pagó Muse Spark).
- **Segundo tiempo:** 53 min 21 s, corrido de cero.
- **La cancha en metros:** 8 marcas a mano en un solo cuadro de cada tiempo, seguidas por el movimiento de la cámara y reancladas con el concepto `white line` de SAM.

## Las estadísticas del partido (SAM 3.1)

| | Primer tiempo | Segundo tiempo | **Partido** |
|---|---|---|---|
| Duración del video | 49.1 min | 53.4 min | **102.5 min** |
| Pausas fuera de las métricas | 24:09, 3 min 7 s (hidratación) | 23:16, 100 s (hidratación); 47:43, 136 s (parece un gol) | 3 pausas, 7 min 3 s |
| Minutos que cuentan | 46.0 | 49.4 | **95.4** |
| **Posesión** | A 56.0 %, B 44.0 % | A 61.8 %, B 38.2 % | **A 58.7 %, B 41.3 %** |
| Cambios de posesión | 69 (1.41/min) | 55 (1.03/min) | **124 (1.21/min)** |
| Posesión más larga | B 84 s | A 66 s | B 84 s |
| Presión en la imagen (rivales cerca del balón) | A 1.14, B 0.98 | A 1.38, B 0.94 | A presiona más en los dos |
| Jugadores por cuadro | 20.8 | 20.9 | 20.8 |
| Tracks de jugadores | 186 | 269 | 455 |
| Cuadros con balón | 72.5 % | 79.2 % | 76.0 % |
| Cuadros con la cancha en metros | 86.0 % | 48.2 % | **66.3 %** |
| Metros por minuto por jugador | A 108.5, B 108.4 | A 102.9, B 101.2 | **A 106.4, B 105.7** |
| Sprint del equipo (percentil 95) | A 16.0, B 16.3 km/h | A 16.5, B 16.9 km/h | – |
| Costo de SAM | unos US$36 | unos US$39 | **unos US$75** |

- **A tuvo el balón casi 6 de cada 10 minutos,** más en el segundo tiempo, y presionó más en los dos. Muse Spark lo lee igual en cada tiempo.
- **Los dos equipos corren parecido:** unos 106 m por minuto por jugador, de 5 a 7 % menos en el segundo tiempo. Las medidas de la cancha son las de omisión (105×68 m), así que las distancias son aproximadas, y en el segundo tiempo salen del 49 % del video.
- **El juego se cargó del lado de B:** el centro de los dos equipos se va hacia una portería en el primer tiempo y hacia la otra en el segundo, porque cambian de lado, y B queda siempre más cerca de ella, como el equipo que defiende. Es consistente con la posesión de A. **Sin verificar:** no se marcó qué portería defiende cada uno.
- **La detención de las 47:43** se revisó a ojo: los jugadores se juntan cerca del centro y luego se forman para un saque de centro. El worker no detecta goles; la saca de las métricas como una pausa.

## SAM 3.1 contra el detector local

| | Detector local | SAM 3.1 |
|---|---|---|
| Tracks de jugadores | 12,276 | **455** |
| Duración mediana de un track (1.º / 2.º tiempo) | 4.3 s / 3.9 s | **90 s / 77 s** |
| Cajas en tracks de más de 60 s | 7 % | **97–98 %** |
| Jugadores por cuadro (1.º / 2.º tiempo) | 17.6 / 15.9 | 20.8 / 20.9 |
| Jugadores sin equipo por cuadro (1.º / 2.º tiempo) | 0.1 / **3.9** | 0.9 / 0.9 |
| Cuadros con balón | 54.9 % | 76.0 % |
| Posesión de A (1.º / 2.º tiempo) | 40 % / 68 % | 56 % / 62 % |

- **Identidad:** con SAM, un track es un jugador durante más de un minuto; con el detector local, unos segundos. Sin eso no hay distancia por jugador ni un track ligado a un dorsal.
- **Donde los dos ven a alguien coinciden:** SAM encuentra el 77 % de las cajas del detector local, el equipo coincide el 92–94 % de las veces y los pies quedan a 0.19–0.21 m.
- **La posesión del detector local no sirve:** pasa de 40 % a 68 % para A de un tiempo al otro, y sale de un balón que casi nunca es el de SAM (13 a 18 % de los cuadros a menos de 25 px).
- **En el segundo tiempo el detector local deja sin equipo a 3.9 jugadores por cuadro,** contra 0.1 en el primero. SAM, que pide el color en el concepto, se queda en 0.9. **Probable, sin verificar:** la sombra de la grada, que en el segundo tiempo cubre media cancha.

## Lo que falta: la cancha en metros del segundo tiempo (48 %)

En los huecos la cancha se ve con todas sus líneas: el video no está mal. Lo que pasa:

| Hueco | Último ancla antes | Arrastre al cortarse | Primer ancla después |
|---|---|---|---|
| 10:13 a 12:06 | 9:44 | 29 s | 13:06 |
| 14:07 a 22:10 | 13:07 | **60 s** | 23:10 |
| 30:09 a 33:17 | 29:09 | **60 s** | 34:07 |
| 35:27 a 43:27 | 34:27 | **60 s** | 44:27 |
| 46:47 al final | 45:47 | **60 s** | ninguno |

- **Causa:** con la cámara en movimiento, una homografía se arrastra a lo más 60 s desde su ancla, porque más lejos la deriva ya es de metros (`MAX_CARRY_SECONDS`, [03](03-homografia-camara-en-movimiento.md)). Si en un minuto SAM no da un ancla que encaje, la cadena se corta. Las líneas de SAM se ajustan **partiendo de la homografía que llega a ese cuadro**, así que sin cadena no hay de dónde partir, hasta que la cámara vuelve a una toma parecida a una conocida. Cada hueco termina 60 s antes de la siguiente ancla buena: la cadena se rehace hacia atrás desde ahí.
- **Por qué en este tiempo pasa más:** en el primer tiempo la cadena se cortó 2 veces por el límite; aquí 4. SAM encontró líneas en 3,199 de los 3,201 cuadros clave, pero el ajuste aceptó solo 380 (12 %). **Probable, sin verificar:** la sombra de la grada y los acercamientos a un área, con menos líneas a la vista.
- **Siguiente paso:**
  - **En la app:** que el entrenador marque un cuadro por hueco. El worker ya acepta varios cuadros marcados; la app hoy manda uno. Es lo más barato y sigue la regla de no inventar metros.
  - **En el worker:** cuando la cadena se pierde, probar las líneas de SAM desde las homografías de otros momentos con una toma parecida, y no solo desde la más cercana en el tiempo.

## Tiempo de proceso

- **Detector local:** 3 h 44 min en CPU (1 h 30 min + 2 h 14 min).
- **SAM 3.1:** 1 h 50 min de llamadas en el primer tiempo; 7 h 14 min en el segundo, con una pausa a mano para grabar el video del demo y el posproceso en CPU.
