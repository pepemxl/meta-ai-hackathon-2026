# Video ancho y SAM 3.1: de cero jugadores a 19 por cuadro

Cómo se logró que SAM 3.1 encontrara a los jugadores en una panorámica de la cancha completa (3840×800, como la que grabaría un teléfono 4K u 8K) y qué conviene recordar antes de repetirlo. Los números completos están en [results/01-cv-local-vs-sam31.md](../results/01-cv-local-vs-sam31.md#clip-3-la-panorámica-nativa-en-tres-partes).

## El problema

Una cámara panorámica muestra la cancha de portería a portería en un cuadro de 3840×800. El primer intento fue tratarla como un video normal del teléfono:

1. **Reescalarla a 16:9.** Se redujo a 1920 de ancho y se rellenó con franjas negras hasta 1920×1080; después la app la baja a 720p.
2. **Resultado:** SAM 3.1 devolvió **0 objetos** en las 9 peticiones (3 chunks × 3 conceptos) de 15 min. CV local (YOLOv8n) vio 7 personas por cuadro, la mitad de la banda. La corrida costó lo mismo que una buena: unos US$5.45.

A 720p, el panorama quedaba en una franja de 267 px de alto, y cada jugador en unos 8 a 10 px.

## El proceso, paso a paso

### 1. Descartar que fuera un filtro del worker

Antes de culpar al modelo, se revisó qué respondió SAM. Las respuestas crudas de la caché (`META_CACHE_DIR`) tenían `output_text` vacío y `response.completed` normal: SAM no encontró nada, y ningún filtro del worker (máscara de la cancha, cuadros que cuentan) quitó detecciones. `raw_tracks.json` también estaba vacío.

### 2. Primera hipótesis: más resolución

Si los jugadores eran muy chicos, mandar el panorama a su resolución original debía bastar. Se hizo un piloto de **1 min** (US$0.36) antes de gastar en 15:

- Chunks a 3840×800, H.264 a 4.5 Mbps, la misma tasa por pixel que el perfil de 1080p de la app.
- La primera corrida falló con `stream error: {}`: un error pasajero de Meta, que no se repitió.
- Una petición directa por concepto (un script que imprime los eventos del stream, sin la llave) dio **0 objetos** para `player in white jersey` y para `soccer ball`.
- El evento final traía `"video_frames_processed": 600`: **la respuesta vacía se cuenta y se cobra.**

La hipótesis no se sostuvo.

### 3. La explicación: SAM ve 1024 px, no la resolución original

Las redes de segmentación tipo SAM (SAM, SAM 2, SAM 2.1) no procesan la imagen a su tamaño:

- reducen cada cuadro hasta que su lado largo mide 1024 px y lo rellenan hasta 1024×1024 (con la media y la desviación de ImageNet);
- el encoder es un ViT con parches de 16 px, que da un embedding de 64×64 (256 canales), más mapas de 256×256 para el detalle de la máscara.

Un cuadro de 3840 de ancho se reduce a la cuarta parte. El jugador, que medía de 25 a 45 px en el original, llega al encoder con 7 a 12 px, de modo que reescalar antes o mandar el original da lo mismo. **Lo que importa es el tamaño del jugador después de la reducción a 1024.**

### 4. Comprobarlo con un recorte

Si la explicación era correcta, un recorte del panorama sin reescalar debía funcionar. Se cortó 1 min de 1280×720 del centro, que SAM reduce solo al 80 %, y se corrió el worker completo con los dos segmentadores (US$0.36):

- **SAM: 16 jugadores por cuadro** (el recorte abarca un tercio de la cancha), balón en el 43 % de los cuadros y 30 tracks.
- Los jugadores medían de 35 a 45 px en el recorte y de 28 a 36 px después de la reducción, como los más chicos que SAM sí encontró en la toma normal de la transmisión (29 px).

### 5. Partir la cancha en tres videos de 1024 px

Un solo recorte pierde las porterías. La solución fue partir el panorama en **tres videos de 1024 px de ancho**, el tamaño que SAM ve sin reducir, con traslape para unir los tracks después (`worker/tools/make_tiles.py`):

1. **Encontrar la cancha** (`plan`) con el mismo método que la app (`pitch.py`), en 30 cuadros repartidos por el video. Se toman los percentiles 10 y 90 de los bordes, para que un cuadro con pasto fuera de la cancha no la agrande. Resultado: 3357×710 px.
2. **Colocar las partes.** Tres partes de 1024 px con un traslape `o` cubren `3·1024 − 2·o` pixeles.
   - Si la cancha no cabe a tamaño real, se reduce el video lo justo: `escala = min(1, (n·1024 − (n−1)·o) / ancho_de_la_cancha)`.
   - Con el margen de 0.02 por defecto y 160 px de traslape, la escala era 0.78. Sin margen (el pasto ya rebasa las líneas) y con 128 px de traslape (unas 5 veces el ancho de un jugador), quedó en **0.84**.
   - El alto es el de la cancha: 596 px. SAM rellena el resto del cuadrado.
3. **Cortarlas** (`encode`) con ffmpeg en una sola pasada: recorte de las filas de la cancha, escala, `split` y un recorte por parte, a los fps del original.
4. **Analizar cada parte** como un clip normal: chunks de la app (10 fps, H.264) y el worker.
5. **Unir los tracks** (`merge`):
   - se pasan las cajas de cada parte a las coordenadas del original;
   - dos tracks de partes vecinas son el mismo jugador si sus cajas coinciden (IoU > 0.5) en al menos la mitad de los cuadros en que los dos están en el traslape, y en 5 o más, la misma regla que une los tracks entre chunks;
   - en el traslape queda una caja por jugador: la de la parte cuyo borde queda más lejos, porque cerca del borde la caja puede venir cortada.

### 6. Probar gratis primero, pagar después

- **La unión se probó con el segmentador local**, que no cuesta, en el primer minuto de las tres partes: 170 tracks bajaron a 143 con 27 uniones, y quedaron 0.4 duplicados por cuadro en el traslape.
- **La revisión a ojo** se hizo dibujando las cajas unidas sobre el panorama original: cada jugador caía en su lugar.
- **Solo entonces se corrió SAM** en el mismo minuto (US$1.08):
  - **19 jugadores por cuadro**, balón en el 77 % de los cuadros y 51 tracks que bajaron a 35 con 16 uniones;
  - la parte 1 salió vacía, pero era correcto: en ese minuto solo estaba el portero, con un color que ningún concepto describe.

## Lo que conviene recordar

1. **Pensar en el tamaño que ve el modelo, no en el del video.** Para SAM, el jugador debe medir unos 30 px después de reducir el cuadro a 1024 px por el lado largo. La cuenta es `alto_en_el_video × 1024 / max(ancho, alto)` del video que se manda. Más resolución no sirve si el cuadro también crece.
2. **Un video ancho se parte; no se reduce.** Varias partes de 1024 px con traslape, y los tracks se unen en las coordenadas del original con la misma regla que entre chunks.
3. **Revisar la respuesta cruda antes de culpar al código o al modelo.** La caché de Meta guarda cada stream: en un minuto dice si SAM no vio nada o si algo del worker lo quitó.
4. **Una respuesta vacía se cobra.** `video_frames_processed` la cuenta igual. Un piloto de 1 min (US$0.36 por parte) antes de 15 min evita pagar US$5.45 por cero.
5. **Probar la parte gratuita con el segmentador local.** La unión de tracks, las coordenadas y la revisión a ojo no necesitan SAM.
6. **Un cero puede ser correcto.** La parte vacía tenía solo al portero. Revisar el cuadro antes de concluir que el modelo falló.
7. **El costo se multiplica por las partes.** Tres partes son tres veces US$0.36 por minuto. Conviene mandar solo las partes donde hay cancha y juego.

## Trampas del entorno

- **ffmpeg no está en la imagen del worker y OpenCV no está en el Python de Windows.** Por eso `make_tiles.py` tiene `plan` y `merge` (OpenCV, en el contenedor) separados de `encode` (solo ffmpeg, en el host).
- **Contenedores largos con `docker run -d`.** Cuando la memoria del sistema baja, Claude Code detiene los procesos de fondo de la sesión, incluido el `wsl docker run` que espera al contenedor. El contenedor sigue, pero la salida redirigida a un archivo se pierde. Con `-d` y `docker logs` al final, el log queda completo.
- **Reutilizar la caché exige los mismos chunks byte por byte.** La llave incluye el sha256 del chunk: volver a cortar o recodificar el video vuelve a cobrar.
- **Las capturas con video de la transmisión se quedan en la máquina** ([08-uso-de-video.md](../08-uso-de-video.md)): este documento describe el proceso sin imágenes.
