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
3. **Reglas del hackathon.** Casi siempre exigen tener derechos sobre todo lo que se presenta. Un demo con una transmisión de TV puede descalificar el proyecto o recibir un reclamo de copyright en el video. Confirmarlo en las Reglas Oficiales ([05-plan-hackathon.md](05-plan-hackathon.md), checklist de entrega).
4. **Términos de la Meta API.** Al subir un video a SAM 3.1 o Muse Spark se declara que se tiene derecho a usarlo. Revisar esa cláusula antes de mandar material de terceros.
5. **Partidos amateur.** Aparecen personas identificables, a veces menores. Eso suma un tema de privacidad (en México, la LFPDPPP) además del de copyright. Ver también el riesgo de privacidad de menores en [05-plan-hackathon.md](05-plan-hackathon.md).

## Qué se puede hacer con cada uso

| Uso | Riesgo | Regla del proyecto |
|---|---|---|
| Probar el pipeline en una máquina, sin publicar nada | Bajo en la práctica, aunque técnicamente incumple los términos de YouTube | Permitido para iterar. Los videos van en `LOCAL_DATA/` o `data/`, que no se versionan (`*.mp4` ya está en `.gitignore`) |
| Frames, overlays o resultados en el repo público | Medio | No. Usar solo clips propios para cualquier ejemplo versionado |
| Video demo o pitch con la transmisión | Alto | No |
| Videos con licencia Creative Commons en YouTube | Bajo, respetando la atribución | Permitido; anotar la fuente y la licencia en el README |
| Video de stock con licencia que permite uso comercial (por ejemplo, la Free License de Mixkit) | Bajo | **Excepción permitida para el demo y el pitch**, con las condiciones de [Video de stock](#video-de-stock-mixkit-y-similares) |
| Clips guardados para el demo | Depende del origen | Solo clips propios o con permiso por escrito |

## Fuentes recomendadas

1. **Grabaciones propias** con pp-coach-tracker ([07-plan-android.md](07-plan-android.md)). Es lo mejor para el demo: además, muestra el flujo real de la app. Están planeadas para el día 8 del hackathon.
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

## Checklist antes de publicar

- [ ] Todo el video del demo y del pitch es propio, con permiso por escrito, con licencia Creative Commons o de stock con licencia comercial (sin versionar el archivo original).
- [ ] Las personas que aparecen dieron su consentimiento (tutores en el caso de menores).
- [ ] El repo no contiene videos, frames ni overlays de transmisiones de terceros.
- [ ] El README indica la fuente y la licencia de cualquier material que no sea propio.
- [ ] Cada dataset usado (Kaggle u otro) tiene licencia clara, origen creíble y no viene de una competencia con uso restringido.
- [ ] Las Reglas Oficiales del hackathon no piden algo adicional sobre el contenido.
