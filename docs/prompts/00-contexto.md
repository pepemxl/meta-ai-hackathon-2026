# 00 · Contexto común (pegar antes de cada prompt) · versión sin respaldo

```text
Vas a construir una parte de PP-Coach-Tracker, un proyecto del Meta Global AI
Developer Hackathon (26 oct – 4 nov 2026): análisis táctico de fútbol amateur
con IA.

El entrenador graba jugadas con el celular (app Android "pp-coach-tracker"), la
app genera una copia de 10 fps en chunks (1 minuto con 2 s de traslape en
desarrollo, 5 minutos con 3 s en el demo; los valores viajan en cada clip,
nunca se suponen), con la máscara de la cancha de cada cuadro, y la sube en
partes de 8 MB a una API (Node para desarrollo, PHP en
producción en Cloud Provider, en https://ppcoachtracker.pepemxl.com). Un worker en
Python sigue a jugadores y balón con SAM 3.1 de Meta, descarta lo que no es
juego, pone a los jugadores en metros con los puntos de la cancha que marca
el entrenador, calcula métricas por equipo y escribe insights con Muse Spark
de Meta. La app muestra el resultado.

SIN RESPALDO: no hay tracker local, ni detector local (YOLO u otro), ni
insights por reglas. Si la Meta Model API falla después de los reintentos,
el trabajo falla con un mensaje claro. No agregues un respaldo "por si
acaso".

DESARROLLO CON SIMULADOR: mientras se construye, el worker apunta
SAM_API_BASE_URL a un simulador local de la API de SAM (fuera de este repo,
docs/13-entorno-desarrollo.md). Para el worker es solo otra URL: no tiene
código de modelos locales ni escalera de respaldo. Lo que sale del
simulador se marca "sam_simulator" y nunca pasa por SAM real. El demo y
producción usan SAM 3.1.

Nombres: el proyecto y la app se llaman pp-coach-tracker (PP-Coach-Tracker
en textos). No inventes otros nombres ni dominios. Identificadores:
- paquete Android com.ppcoach.tracker; paquete Python ppcoachtracker_worker;
  espacio de nombres PHP PpCoachTracker\;
- variables de entorno con prefijo PP_COACH_TRACKER_ (por ejemplo
  PP_COACH_TRACKER_API_URL);
- base de datos y usuario locales ppcoachtracker; en Cloud Provider, una base
  NUEVA con el prefijo de la cuenta (<prefijo>_ppcoachtracker);
- la API local en la subcarpeta /pp-coach-tracker; en producción en la raíz
  de https://ppcoachtracker.pepemxl.com.

Antes de escribir código, lee:
- docs/README.md (índice) y docs/arquitectura/README.md (vista general y diagramas)
- en las etapas de la app: docs/diseno.md (estilo visual tipo apps de Meta:
  colores, tipografía, componentes y navegación)
- los documentos que cite el prompt de esta etapa

Reglas del proyecto:
1. Idioma: la documentación y los textos para el usuario en español (la app
   también en inglés). Identificadores en inglés. Comentarios en inglés en
   Kotlin, JavaScript y Python; en español en PHP.
2. Privacidad: el pipeline usa IDs y dorsales, nunca nombres de jugadores; los
   nombres no salen del teléfono ni a la Meta API (docs/10-privacidad-jugadores.md).
   El video va sin audio.
3. Secretos: nunca en el código ni en archivos versionados. Van en un .env que
   no se versiona (hay un .env.example con los nombres). No imprimas secretos
   en la salida ni en los logs. Si necesitas uno, pídemelo.
4. Pruebas: cada etapa deja pruebas automáticas que pasan, y no rompe las de
   etapas anteriores. Si una prueba falla, arréglala o explícame por qué; no
   la borres para que pase.
5. Commits: uno por función, con mensaje en español en imperativo
   ("Agrega …"). No hagas push sin que te lo pida. Termina cada mensaje con la
   línea de coautoría que te indique.
6. Entorno: Windows 11 con Git Bash; Docker corre en WSL
   (`wsl -e docker compose …` desde api/). Python 3 del sistema sin OpenCV:
   lo que use OpenCV corre en Docker.
7. Al terminar, dime qué hiciste, cómo lo probaste (con resultados reales) y
   qué quedó pendiente. No digas que algo funciona si no lo probaste.
8. Código del hackathon: todo el código se escribe en esta sesión, desde la
   documentación y este prompt. NO copies ni leas código de una construcción
   anterior (otra carpeta, otro repo, un zip), aunque exista en la máquina:
   la entrega declara qué se hizo antes y qué durante el hackathon. Única
   excepción: la etapa 08a (simulador de SAM), que se hace en la carpeta del
   prototipo y no copia nada a este repo. Las demás etapas no leen esa
   carpeta: solo usan el simulador por HTTP.
9. Video: nunca versiones video de terceros (transmisiones) ni imágenes o
   datos sacados de él; solo para pruebas locales en carpetas ignoradas
   (docs/08-uso-de-video.md). Las capturas y el demo, con video propio,
   sintético o de stock con licencia comercial (por ejemplo, la Free License
   de Mixkit), sin versionar el archivo original.
10. Licencias: solo dependencias con licencia permisiva (MIT, BSD,
   Apache-2.0, ISC) o las propias de la plataforma (Android SDK, AndroidX).
   Nada GPL, AGPL ni LGPL enlazado estático, ni modelos con licencia
   copyleft o no comercial. Los modelos de visión y lenguaje son los de la
   Meta Model API, bajo sus términos, y la única excepción a las licencias
   permisivas es meta-sam-parser (SAM License), el decodificador de Meta
   para las máscaras de SAM 3.1. Antes de agregar una dependencia,
   revisa su licencia y anótala en THIRD_PARTY.md (nombre, versión,
   licencia, para qué); si no está clara, pregúntame. El simulador de
   desarrollo (YOLOv8n, AGPL-3.0) vive fuera del repo, solo en la máquina
   local, y nunca se despliega ni se distribuye.
```
