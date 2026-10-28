# Análisis de video

Lo aprendido con el worker local (YOLOv8n + tracker IoU) sobre 10 minutos de una transmisión (Egipto–Argentina, cámara lejana). Números en worker/README.md.

## Detección: mosaicos, no resolución

- **YOLO ve 640 px sea cual sea el video.** Un jugador lejano en 1080p queda igual de chico que en 720p al reducirse la imagen. A 720p y a 1080p salieron 7.3 jugadores por cuadro.
- **Lo que ayuda es mirar por partes:** el cuadro completo más 4 mosaicos con traslape. Con eso se pasó a 9.2 jugadores por cuadro y de 32 % a 62 % de balón visto, en 720p y en 1080p casi igual.
- **Cuesta tiempo:** de 0.6 a 3 veces la duración del video en CPU. Antes de desplegar hay que decidir GPU o saltar cuadros.
- **Mosaicos cada 2 cuadros, pero no para el balón.** A los jugadores el tracker los espera un cuadro y se rellenan; el balón detectado cayó de 67 % a 46 %, porque casi siempre lo encuentran los mosaicos. Mirar en los cuadros de en medio solo el mosaico donde estaba el balón lo regresó a 63 %, y el proceso quedó en 1.20× la duración.
- **Recomprimir importa:** la copia de la app (dos pasadas de compresión) dio 6.4 jugadores por cuadro, contra 7.3 del mismo video comprimido una vez. Cada recompresión borra detalle de los jugadores lejanos.
- **Consecuencia en la app:** la opción de 1080p quedó apagada por omisión y con un texto que no promete de más. Solo tendría sentido con mosaicos 3×3 o GPU.

## Qué cuadros cuentan

- **En una transmisión, la mitad no es juego.** El 47 % de los cuadros tenía una persona de más de 1/3 del alto: acercamientos, festejos, el banco, la pantalla del VAR, la tribuna. Un bombo con caras pintadas salió como 6 "jugadores" con equipo.
- **El filtro de público estaba al revés:** solo actuaba en cuadros con mucho pasto. En un cuadro sin cancha dejaba pasar todo.
- **La regla tiene que ir por tramos, no por cuadro:** de 142 tramos de acercamiento, 82 duraban menos de 1 s. Cuadro por cuadro, la línea de tiempo queda picada.
- **"Jugadores chicos detrás" hay que contarlos en la cancha.** En la primera versión, el personal detrás de los anuncios contaba como jugadores chicos y salvaba los acercamientos. Contar solo a quien tiene los pies en el pasto lo arregló.
- **También caen tomas medias de juego** (dos jugadores con el balón). Es el costo de 1/3; si se quiere conservarlas, subir el umbral.
- **Lo descartado no debe unir nada:** un track y una racha de posesión de antes de una repetición no son los de después. Se parten en el corte.
- **Los cortes de cámara se ven en los colores:** el salto del histograma de tono y saturación entre dos cuadros separa bien un corte (0.44 o más) de una toma con movimiento (hasta 0.27); los fundidos quedan en medio. Revisar pares de cuadros por franja fijó el umbral.
- **Una regla por toma solo debe descartar.** Clasificar la toma completa por mayoría tiró juego de una toma que se acercaba sin cortar, y contar completa una toma con algo de acercamiento dejó cuadros vacíos (la caja grande se quita después). Quedó así: una toma corta con dos tercios descartados sale entera; si no, cuadro por cuadro.

## El balón

- **El detector confunde el balón con cabezas y anuncios.** Sobre todo con el personal de la orilla, que lleva chaleco amarillo, a la altura de los anuncios.
- **Un balón necesita pasto alrededor.** Pedir un tercio de pasto en un cuadrado de 4 veces su tamaño quitó 2212 balones falsos. De una muestra de los perdidos, 5 de 6 eran falsos; el real perdido estaba en el aire sobre la tribuna.
- **Un Kalman que sigue la detección más segura se pega a lo que no se mueve.** Un anuncio detectado como balón en cada cuadro "encaja" siempre con su propia predicción. En la primera versión, en 4 de 8 cuadros revisados, el filtro siguió un balón falso y descartó el real.
- **Lo que funcionó:** varios candidatos por cuadro, una trayectoria por candidato (cada una con su filtro), y elegir la trayectoria que dura y pasa junto a los pies de los jugadores.
- **Rellenar huecos solo dentro de una trayectoria.** Una línea recta entre dos trayectorias distintas inventa un pase que no ocurrió.
- **Cuidado con cómo se cuenta lo descartado.** El primer conteo daba 625 detecciones descartadas; eran 141. La primera detección de cada reinicio se contaba como descartada y luego se conservaba. Contar "candidatos menos conservados" evita el error.

## Posesión

- **La racha mínima se mide en cuadros con balón.** Si un cuadro sin balón corta la racha, con el balón visto a ratos casi ninguna racha llega a 5 cuadros y la posesión sale de muy pocos datos.
- **Pero no a través de mucho tiempo sin balón:** más de 2 s sin verlo corta la racha.
- **Un total estable no es una medida estable.** Entre 720p y 1080p, la posesión de Egipto pasó de 52 % / 42 % a 53 % / 53 %, pero en qué cuadros hay posesión coincide solo en un 61 %, igual que antes. El total se estabilizó porque se cuenta en 2.4 veces más cuadros. Cuando las dos corridas cuentan posesión en un cuadro, el equipo coincide en el 97 %: lo frágil es el umbral (balón visto y jugador a menos de 0.06), no la asignación de equipos.
- **La histéresis en el radio ayudó poco.** Ganar a 0.06 y soltar a 0.09 subió la coincidencia cuadro por cuadro de 61 % a 65 %, pero los cambios de posesión pasaron de 18 / 16 a 22 / 31 entre 720p y 1080p: más rachas llegan al mínimo de 5 cuadros. Suavizar un umbral no arregla que el balón no se vea.
- **Más cambios de posesión no es necesariamente peor:** al medir así aparecen posesiones cortas que antes se perdían (de 6 a 18 cambios en 10 minutos). Sin una referencia no se sabe cuántos son reales.

## El árbitro

- **"Lejos de su grupo" no basta cuando un equipo es disperso.** Las camisetas blancas varían tanto con la luz y la sombra que el árbitro de amarillo quedaba dentro del límite del grupo blanco. Un tercer grupo de k-means lo separa; se acepta solo si es chico y está lejos de los dos equipos, para no partir un equipo en dos tonos.

## Tracks partidos

- **Unir tracks después del tracker no sirvió.** El tracker ya espera 10 cuadros a cada jugador; unir en esa ventana solo afloja su umbral. De 16 uniones revisadas, 5 eran el mismo jugador. El color de un track corto es poco confiable: dos uniones pasaron a un jugador del otro equipo.
- **Las detecciones débiles alargan los tracks, pero sobre todo los de la orilla.** Con las de 0.15 a 0.35 solo para continuar tracks, 16 de 24 cajas débiles eran personal y público junto a los anuncios. Un número que mejora (tracks de 5 s o más: 308 → 338) no basta: hay que mirar qué se agregó.

## La homografía (con datos sintéticos)

- **Que encaje no quiere decir que esté bien.** Con el modelo de cámara y 2 rectas, el ajuste deja menos de 1 px de error en las rectas y aun así la cancha sale de 3 a 8 m corrida: la inclinación y el zoom se compensan. Un punto más (el centro o el cruce de la media cancha con el círculo) lo baja a menos de 1 m. Hay que medir el error en la cancha, no en lo que se ajustó.
- **El error crece lejos de lo que se vio.** Con rectas solo en la mitad lejana, la mitad cercana queda decenas de píxeles fuera. Una calidad por cuadro tiene que decir hasta dónde vale, no un solo número.

## La homografía (propagada)

- **Seguir el pasto entre cuadros funciona.** Desde un cuadro tocado, la plantilla siguió sobre las líneas 12 s de transmisión con paneo, a 2–3 px a 5 s. El error suma unos 0.35 px por cuadro: sirve para segundos, no para minutos sin volver a calibrar.
- **La propagación también encuentra cortes.** Un cambio de cámara que el histograma no vio dejó al 9 % de los puntos coincidiendo, y la cadena se cortó ahí.
- **Una prueba de calidad se prueba con los datos reales antes de fijar el umbral.** "Pies en la cancha con 2 m de margen" rechazaba hasta el cuadro bien tocado, por el personal parado a 1–3.5 m de la banda. Con los metros, esa gente ya se puede quitar: está fuera de la cancha.

## La homografía (con las líneas)

- **Buscar la cámara es más fácil que nombrar las rectas.** Con el lugar de la cámara conocido son 3 números, y la medida contra los píxeles blancos dice cuál cámara dibuja bien la plantilla. Lo frágil queda en la búsqueda, no en adivinar cuál recta es cuál.
- **La medida tiene que ir en los dos sentidos.** De la plantilla a las líneas sola, la plantilla se encoge sobre la banda; de las líneas a la plantilla sola, se estira sobre la basura.
- **Revisar si la medida prefiere la respuesta antes de culpar a la búsqueda.** La homografía de los toques tenía el mejor puntaje; el refinado se alejaba de ella porque los tramos de línea que la máscara no ve lo jalaban. Cambiar a pares de puntos con un umbral que baja lo arregló.
- **Un paisaje abrupto rompe la supresión de vecinos.** Un grado de inclinación mueve mucho la banda lejana: el punto de la rejilla junto a la respuesta puntuaba mal y uno vecino, peor todavía, lo bloqueaba.
- **Un dato de un solo cuadro puede estar metros corrido sin que se note ahí.** El lugar de la cámara de un cuadro tocado salía 7 m corrido; cinco cuadros pulidos coincidieron en otro lugar, y con él aparecieron tomas que antes no encajaban.
- **En una transmisión, otra cámara parecida encaja a medias.** Las bandas y la media cancha se parecen desde dos cámaras altas; lo que las distingue es el círculo, y la máscara no siempre lo ve. Un filtro que acepta cuadros malos da metros falsos: mejor apagado hasta probarlo donde importa.

## La homografía (con SAM)

- **Conectar lo nuevo en la pieza más chica.** SAM da máscaras; la fase 3 ya sabía medir una cámara contra píxeles blancos. Darle a esa búsqueda las máscaras de SAM en lugar de las locales deja una sola pieza nueva (leer la máscara) y la misma calidad para los dos.
- **Un formato que no se ha visto se adivina con varias opciones y se guarda crudo.** Si nada se lee, se baja de nivel con un error que dice dónde quedó el stream.
- **Probar el lector con datos realistas, no solo pequeños.** La cadena COCO comprimida de una máscara chica no traía `;`; la de una cancha completa sí, y partía los atributos. Solo salió al pasar la máscara de una cancha entera de punta a punta.

## Las métricas en metros

- **Medir la velocidad entre segundos, no entre cuadros.** A 10 fps, el temblor de la caja de unos píxeles se vuelve medio metro por cuadro en la banda lejana: suma metros que nadie corrió. Con la posición media de cada segundo, los jugadores sintéticos a velocidades conocidas salen a menos de 1 %.
- **Lo que no puede ser, fuera, y contado.** Un paso de más de 11 m/s es un cambio de track; se quita y el resultado dice cuántos (`dropped_steps`). Con los metros, el personal fuera de la banda se quita sin adivinar.
- **Probar de punta a punta con la verdad conocida.** Un segmentador falso que pone jugadores a 1–4 m/s sobre el video sintético comprobó la cadena entera (toques, homografía, pies en metros, métricas, avisos) contra números que se saben de antemano.

## La gente en la orilla

- **El filtro de público mira el pasto bajo los pies.** Quien está pegado a los anuncios tiene los pies junto al pasto y pasa. Algunos reciben equipo y hasta se quedan con la posesión.
- **La máscara de la cancha ([mejora 01](../mejoras/01-mascara-cancha.md)) no la quitó:** entre la banda y los anuncios también hay pasto, y ahí están parados. El pasto dice dónde hay cancha, no dónde termina; para eso hace falta la línea de banda (la homografía).

## La máscara de la cancha

- **Lo que más dio fue tiempo:** una pasada que solo decodifica y mide el pasto (10 s para 10 minutos) evita pasar por YOLO los cuadros sin cancha. El proceso bajó de 2.94 a 1.68 veces la duración del video.
- **Saltarse cuadros solo si se van a descartar de todos modos.** El suavizado de `shots.py` devuelve a contar los tramos cortos sin pasto; si se saltan antes de detectar, cuentan con 0 jugadores. Se saltan solo los que el suavizado deja fuera con el pasto solo, que nunca regresan al agregar acercamientos.
- **Filtrar antes de medir cambia lo que se mide.** Al quitar a quien tiene los pies fuera de la cancha, el acercamiento (pies fuera de la imagen) dejó de verse y los cuadros con 0 jugadores subieron de 3 % a 8 %. La persona más alta se mide con todas las detecciones.
- **Un contorno convexo con poco pasto adentro no es una cancha.** En la cámara detrás de la portería, el portero de verde o el VAR, el contorno cubría casi toda la imagen. Pedir que al menos el 70 % del polígono sea pasto las quitó; revisar a ojo cada franja (menos de 0.6, 0.6–0.75, 0.75–0.85) fijó el umbral.
