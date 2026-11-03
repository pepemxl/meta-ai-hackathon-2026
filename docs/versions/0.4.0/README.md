# pp-coach-tracker 0.4.0

- **Fecha:** 2 de noviembre de 2026.
- **Versión anterior:** [0.3.0](../0.3.0/README.md).
- **Probado en:** el build debug en el emulador `Medium_Phone_API_36.0` (Android 16, API 36), contra la API PHP local con el worker real (SAM 3.1 + Muse Spark); el release se compiló y firmó, y no se instaló en ningún teléfono.
- **Versiones:** app 0.4.0 (`versionCode` 4) y API 0.4.0, desplegada en https://ppcoachtracker.pepemxl.com el 2 de noviembre; el worker de producción corre el mismo código. **El contrato cambió:** esta app manda `analysis.language` y una API anterior a 0.4.0 le responde 400.

## Qué cambió

- **QR de configuración:** el sistema de análisis (servidor y cuenta) se configura escaneando un QR.
- **Zona de juego:** con la cámara fija, el entrenador marca el polígono de la cancha (con puntos donde el borde se dobla y un margen). El análisis ignora a quien queda fuera (gradas, banca, estacionamiento) y no se confunde con otras zonas verdes, como en una toma en diagonal.
- **¿La cancha tiene pasto?** En Nueva cancha y Editar cancha: pasto (natural o sintético) o tierra. En una cancha de tierra, el análisis busca el color de la tierra para la máscara y para el balón.
- **Muestra del pasto o la tierra:** un modo nuevo en Puntos de la cancha.
  - Cada toque en el cuadro toma los colores de un cuadrito; hasta 8 toques.
  - En morado se ve lo que el análisis tomará como cancha.
  - Un toque sobre un jugador o una línea se rechaza.
  - La muestra se guarda con la cancha y el análisis busca esos colores; sirve con pasto apagado (sintético al atardecer) que los colores fijos no encuentran.
- **Idioma del análisis:** el resumen y los hallazgos de Muse Spark, las notas y las limitaciones salen en el idioma de la app (español o inglés).
- **Cuenta demo:** todo se guarda en el teléfono, nada se sube ni gasta crédito, y la app trae un clip de prueba con su análisis ya hecho ([14-modo-demo.md](../../14-modo-demo.md)).
- **Correcciones:**
  - la app usa el tamaño real del video de cada chunk y avisa cuando el codificador del teléfono redujo la copia para el análisis;
  - elegir, editar o muestrear una cancha la deja como la del partido, para que su superficie y su muestra viajen con el clip;
  - el texto de la zona de juego dice "el color de la tierra" en una cancha de tierra.

## Probado

- **Pruebas:** app 81, worker 111, API Node 24 (en memoria y contra MySQL) y API PHP 27.
- **En el emulador, de punta a punta:** un clip de cancha de tierra con muestra de 5 toques llegó a la API con `surface = dirt` y la muestra; el análisis real vio el balón en el 100 % de los cuadros y en sus limitaciones dice "Cancha de tierra" y la muestra. Lo mismo con pasto sintético y una muestra de 4 toques.
- **Idioma:** el mismo clip analizado con `language = en` dio el resumen, los hallazgos, las notas y las limitaciones en inglés.

## Capturas

Las capturas de esta versión (pasto y tierra con el overlay morado, el toque rechazado, la zona de juego en diagonal, los análisis y el visor con las cajas de SAM) están en `LOCAL_DATA/presentacion/`, sin versionar todavía: varias usan imágenes generadas con IA que hay que acreditar antes de publicarlas.

## Archivo

`android_native/dist/pp-coach-tracker-0.4.0.apk` (18.9 MB, no se versiona): release firmado con la llave de `keystore.properties`, con el clip de prueba de la cuenta demo.
