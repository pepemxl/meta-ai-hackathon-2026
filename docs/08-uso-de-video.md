# Uso de video de terceros (YouTube, transmisiones, partidos amateur)

> Orientación general para el equipo, **no es asesoría legal**. Si hay dudas sobre un caso concreto (sobre todo antes de publicar algo), consultar a un abogado o pedir permiso por escrito.

## Respuesta corta

Descargar partidos de YouTube viola sus términos de servicio, y los derechos del video son del dueño de la transmisión, no de quien lo subió.

- **Pruebas locales del pipeline:** riesgo práctico bajo. Se acepta mientras se desarrolla, sin publicar nada.
- **Demo, pitch, capturas, README o repo público:** solo video propio o con licencia clara.

## Qué entra en juego

1. **Términos de YouTube.** Prohíben descargar videos salvo con las funciones de la propia plataforma o con permiso del titular. Usar `yt-dlp` incumple el contrato con YouTube, aunque eso por sí solo no sea un delito.
2. **Derechos de autor.** Las transmisiones de ligas como Liga MX, LaLiga, Premier o FIFA tienen dueño y se defienden con fuerza. Que alguien las haya subido a YouTube no da ningún permiso.
   - En EE. UU. (las reglas del hackathon probablemente se rigen por su ley) existe el *fair use*. Analizar un video para investigación tiene buenos argumentos, pero se decide caso por caso.
   - En México, la Ley Federal del Derecho de Autor tiene excepciones mucho más estrechas (cita, uso personal, etc.).
3. **Reglas del hackathon.** Casi siempre exigen tener derechos sobre todo lo que se presenta. Un demo con una transmisión de TV puede descalificar el proyecto o recibir un reclamo de copyright en el video. Confirmarlo en las Reglas Oficiales del hackathon.
4. **Términos de la Meta API.** Al subir un video a SAM 3.1 o Muse Spark se declara que se tiene derecho a usarlo. Revisar esa cláusula antes de mandar material de terceros.
5. **Partidos amateur.** Aparecen personas identificables, a veces menores. Eso suma un tema de privacidad (en México, la LFPDPPP) además del de copyright. Ver también [10-privacidad-jugadores.md](10-privacidad-jugadores.md).

## Qué se puede hacer con cada uso

| Uso | Riesgo | Regla del proyecto |
|---|---|---|
| Probar el pipeline en una máquina, sin publicar nada | Bajo en la práctica, aunque técnicamente incumple los términos de YouTube | Permitido para iterar. Los videos van en `LOCAL_DATA/` o `data/`, que no se versionan (`*.mp4` ya está en `.gitignore`) |
| Frames, overlays o resultados en el repo público | Medio | No. Usar solo clips propios para cualquier ejemplo versionado |
| Números agregados de una prueba local, sin cuadros, con equipos anonimizados y sin datos que identifiquen el partido | Bajo | Permitido en [results/](results/README.md) |
| Video demo o pitch con la transmisión | Alto | No |
| Videos con licencia Creative Commons en YouTube | Bajo, respetando la atribución | Permitido; anotar la fuente y la licencia en el README |
| Video de stock con licencia que permite uso comercial (por ejemplo, la Free License de Mixkit) | Bajo | **Excepción permitida para el demo y el pitch**, con las condiciones de [Video de stock](#video-de-stock-mixkit-y-similares) |
| Clips guardados para el demo | Depende del origen | Solo clips propios o con permiso por escrito |

## Fuentes recomendadas

1. **Grabaciones propias** con pp-coach-tracker. Es lo mejor para el demo: además, muestra el flujo real de la app.
   - Pedir consentimiento a los jugadores, y a los tutores si hay menores, antes de grabar.
   - No usar el tier *contributor* de la Meta API con video de menores.
   - Borrar los videos de prueba que no se vayan a usar.
2. **Permiso del autor** de un partido amateur (una liga local o una academia). Un correo con un "sí" basta; guardarlo.
3. **Datasets académicos**, como SoccerNet, SoccerTrack o los datos abiertos de Metrica Sports. Cada uno tiene su propia licencia (algunos son solo para investigación o piden firmar un acuerdo), así que revisarla antes de usar el material en algo público.

## Datasets públicos (Kaggle y similares)

Que un dataset esté en Kaggle no da derechos por sí mismo. Kaggle solo hospeda el archivo. La licencia la pone quien lo subió, y esa persona no siempre es dueña del video.

### Qué revisar en cada dataset

1. **Licencia** (en la página del dataset, sección *License*):

   | Licencia | ¿Sirve para el hackathon? |
   |---|---|
   | CC0 / dominio público | Sí, sin restricciones |
   | CC BY / CC BY-SA | Sí, dando atribución (BY-SA además obliga a compartir lo derivado con la misma licencia) |
   | CC BY-NC | Para el hackathon probablemente sí; si el proyecto se vuelve startup, no (prohíbe el uso comercial) |
   | ODbL / DbCL | Pensadas para bases de datos, no para video; revisar bien antes de usarlo |
   | "Other", "Unknown" o sin licencia | Tratarlo como si no hubiera permiso |

2. **Origen del video.** Muchos datasets de fútbol en Kaggle son recortes de transmisiones de TV o de YouTube. Si el que lo subió no tenía los derechos, poner "CC0" no lo arregla. Desconfiar cuando la descripción no dice de dónde salió el video, o cuando se ve el logo de una cadena o el marcador de la TV.
3. **Datos de una competencia de Kaggle, no de un dataset.** Eso pasa con varias de fútbol, por ejemplo una de la Bundesliga. Esos datos se rigen por las reglas de la competencia, que suelen limitar su uso a esa competencia. No asumir que se pueden usar fuera de ella.
4. **Personas en el video.** Aunque la licencia sea abierta, si aparecen menores o personas identificables en contextos privados, aplica lo mismo que en el resto de esta guía.

### Cómo usarlos en el proyecto

- **Desarrollo y pruebas:** cualquier dataset con licencia clara.
- **Repo:** no subir los videos. Poner el enlace al dataset y un script de descarga; los archivos quedan en `LOCAL_DATA/`, que no se versiona.
- **Demo y pitch:** con CC0 o CC BY sí, acreditando en el video y en el README. Con NC o licencias dudosas, mejor video propio.
- **Registro:** anotar en el README, por cada dataset usado, el nombre, el enlace, la licencia y el autor.

## Video de stock (Mixkit y similares)

Es una excepción a "solo video propio o sintético" para el demo, el pitch y las capturas. Se aplica a clips de bancos de video cuya licencia permite el uso comercial. Cuatro condiciones:

1. **La licencia del clip, no la del sitio.** Cada clip dice qué licencia tiene. En Mixkit, la **Stock Video Free License** sirve: permite descargar, copiar, modificar, distribuir, mostrar en público y transmitir, en proyectos comerciales y no comerciales, con derechos no exclusivos, mundiales, sublicenciables y sin plazo, y no pide atribución. La **Restricted License** no sirve: es solo para proyectos personales sin fines comerciales, y el hackathon da premios y les da a los organizadores una licencia perpetua sobre lo que se entrega.
2. **El archivo original no se versiona.** Los términos de uso de Mixkit prohíben ofrecer el clip tal cual, por separado (venderlo, prestarlo o ponerlo a disposición sin transformarlo). El clip se guarda en `LOCAL_DATA/` y en el repo solo van el enlace y la licencia. El video demo, que muestra la app analizándolo, es una obra transformada.
3. **Ni marcas de agua quitadas ni descarga en masa.** Se baja a mano, clip por clip, desde la página del clip.
4. **Crédito y registro.** No es obligatorio, pero se anota en el README de la entrega y en el video: "Demo footage: Mixkit (mixkit.co), Stock Video Free License", con el enlace de cada clip.

La licencia no dice nada de IA. Mandar el clip a la Meta Model API para analizarlo es procesarlo dentro del proyecto, no entrenar un modelo. Antes de usar otro banco de video, revisar su licencia con estas mismas cuatro condiciones.

### Clips revisados

| Clip | Licencia | Duración | Notas |
|---|---|---|---|
| [Play between two teams in a soccer match (43481)](https://mixkit.co/free-stock-video/play-between-two-teams-in-a-soccer-match-43481/) | Mixkit Stock Video Free License (revisada el 27 de octubre de 2026) | 5 s | Cancha semiprofesional de pasto sintético, dos equipos. Sirve para capturas o un fragmento del demo, pero es muy corto para el análisis completo: las métricas en metros piden 10 s o más de cancha calculada, y el análisis avisa con menos de 30 s que cuentan |

## Contenido generado con IA

Otra excepción para el demo, el pitch y las capturas: imágenes y video de una escena inventada, generados con IA. No reemplaza al video real: sirve para mostrar el flujo de la app (importar, marcar la cancha, enviar, ver el análisis), nunca como prueba de precisión, que se mide con video real ([results/](results/README.md)). Cuatro condiciones:

1. **Los términos de la herramienta, no los de la plataforma en general.** Deben dejar que el autor use lo generado en un proyecto con premios. La Meta Model API sí: lo generado es del usuario y el servicio es para uso comercial. Una herramienta limitada a "uso personal" (como Microsoft Copilot con cuenta gratuita) no sirve sin revisarlo antes.
2. **Se dice siempre.** En cada diapositiva, captura o tramo del video que lo use, con la herramienta: "AI-generated with Meta muse-image-1.0". No se presenta como un partido real.
3. **Los originales no se tocan.** Se guardan en `LOCAL_DATA/assets/`, con sus marcas de IA en los metadatos (IPTC `trainedAlgorithmicMedia`, C2PA), y un `LEEME.md` por grupo con la herramienta, la fecha, la huella de cada archivo y los términos revisados. Solo se versiona lo que tiene términos claros de uso comercial, tal cual, en `docs/assets/` (hoy, las imágenes del grupo 1).
4. **Sin personas ni marcas reales.** La escena es inventada: ni jugadores reconocibles, ni escudos o patrocinadores reales.

### Assets registrados

| Asset | Herramienta | Términos | Notas |
|---|---|---|---|
| 5 imágenes de una toma desde la grada, granates contra azules: tres en pasto, una en pasto sintético y una en tierra (`LOCAL_DATA/assets/Grupo_01/generated-image-1_*.png`, `jugada_01_pasto_sintetico.png`, `jugada_01_sin_pasto.png`) | Meta muse-image-1.0, por la Meta Model API (playground), 2 de noviembre de 2026 | [Meta Model API Terms of Service](https://dev.meta.ai/legal/terms-of-service) (actualizados el 9 de octubre de 2026, revisados el 2 de noviembre): "you own all rights, title, and interests in and to your Inputs and Outputs" (4.1) y "The Services are intended for your commercial use" (1); si una ley pide etiquetar el contenido de IA, cumplirlo es del usuario | Llevan la marca IPTC de contenido generado por IA. Sirven para capturas y diapositivas; la de tierra es la de la prueba de la cancha sin pasto. Respaldadas sin modificar en [assets/grupo_01/](assets/grupo_01/README.md) |
| 3 videos de 10 s de la misma escena, animando la imagen (`LOCAL_DATA/assets/Grupo_01/image_20261102_*.mp4`) | Meta AI (Vibes), 2 de noviembre de 2026 | [Condiciones de las IA de Meta](https://www.facebook.com/policies/other-policies/ais-terms) (vigentes desde el 13 de mayo de 2026): el usuario es responsable del uso de lo generado; **no dicen de quién son los resultados ni si se permite el uso comercial**. Lo compartido queda sujeto a las Normas comunitarias de Meta | Uno de los tres lleva la marca IPTC. Se analizaron con SAM 3.1 + Muse Spark (dos de ellos) para las capturas del análisis. Usarlos con la nota "AI-generated with Meta muse-image-1.0 and Meta AI (Vibes)" y confirmar con las Reglas Oficiales del hackathon |

## Video propio

Es lo que el proyecto prefiere para cualquier ejemplo versionado: grabado por el equipo, con pp-coach-tracker o con la cámara del teléfono para importarlo. Los videos completos se quedan en `LOCAL_DATA/assets/` (pesan cientos de MB); en el repo solo van cuadros reducidos para las pruebas, sin personas identificables.

| Cancha | Clips | Qué hay en el repo | Notas |
|---|---|---|---|
| Yerbabuena 1: pasto sintético con pista de atletismo, desde la grada | 4, de 20 a 57 s; dos a 1920×1080 y dos a 3840×2160, 30 fps | 2 cuadros de 640×360 en [worker/tests/fixtures/real/](../worker/tests/fixtures/real/README.md) | Grabada el 3 de noviembre de 2026 con la app 0.4.0, en horizontal y con la cámara en movimiento. Sin jugadores; un corredor pasa a lo lejos en un par de momentos, fuera de los cuadros versionados |
| Yerbabuena 2: terracería, sin pasto aunque algo mixta (tierra con manchas de pasto seco) | 6, de 10 a 13 s, a 1920×1080 y 30 fps | 2 cuadros de 640×360, en la misma carpeta | El mismo día. Sin personas |
| Yerbabuena 1, con la cámara del teléfono | 5 videos de 15 a 32 s (720p, 1080p, 4K y 8K) y 2 fotos de 4096×3072 | 1 cuadro de 640×360, en la misma carpeta | El mismo día, sin la app: HEVC a 30 fps, en horizontal y sin marca de rotación. Para probar la importación. Dos personas a lo lejos en uno de los videos, fuera de los cuadros versionados |
| Yerbabuena 2, con la cámara del teléfono | 9 videos de 19 a 71 s (720p, 1080p, 4K y dos en 8K) y 20 fotos de 4096×3072, desde varios lados y a ras de la cancha | 1 cuadro de 640×360, en la misma carpeta | Igual que el anterior. Sin personas; solo la sombra de quien graba |

Los clips quedaron con la marca de rotación de 90° que la app 0.4.0 le ponía por error a lo grabado en horizontal: se reproducen de lado, aunque la imagen está derecha. La 0.4.1 corrige la grabación y la 0.4.2 permite girarlos en la app sin recodificar. Los de la cámara del teléfono no tienen ese problema. Al importar un 8K, la copia para el análisis baja a 2160p como máximo.

## Checklist antes de publicar

- [ ] Todo el video del demo y del pitch es propio, con permiso por escrito, con licencia Creative Commons o de stock con licencia comercial (sin versionar el archivo original).
- [ ] Las personas que aparecen dieron su consentimiento (tutores en el caso de menores).
- [ ] El repo no contiene videos, frames ni overlays de transmisiones de terceros.
- [ ] El README indica la fuente y la licencia de cualquier material que no sea propio.
- [ ] Cada dataset usado (Kaggle u otro) tiene licencia clara, origen creíble y no viene de una competencia con uso restringido.
- [ ] Lo generado con IA lo dice en cada diapositiva, captura o tramo del video, con la herramienta, y está registrado arriba con sus términos.
- [ ] Las Reglas Oficiales del hackathon no piden algo adicional sobre el contenido (incluido el generado con IA).
