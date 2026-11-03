# pp-coach-tracker 0.4.1

- **Fecha:** 3 de noviembre de 2026.
- **Versión anterior:** [0.4.0](../0.4.0/README.md).
- **Probado en:** compilado y con pruebas unitarias; el release se firmó y **falta probarlo en un teléfono real** (la cámara del emulador es virtual y no sirve para probar la rotación).
- **Versiones:** app 0.4.1 (`versionCode` 5). La API sigue en 0.4.0, sin cambios en el contrato. El worker de producción ya corre el margen de la muestra.

## Qué cambió

- **Grabar en horizontal:** los videos grabados con el celular en horizontal quedaban girados 90°. La cámara se configuraba con la pantalla todavía vertical y no se actualizaba al girar; ahora toma la rotación de la pantalla al empezar cada toma, de cualquiera de los dos lados en que se sostenga el celular. Los clips grabados antes siguen girados.
- **Worker, muestra de la cancha con margen:** la muestra de pasto o tierra del entrenador se usa con ±3 de tono y ±20 de saturación y brillo. Con pocos toques, la muestra no alcanzaba las orillas de los colores de la cancha (una de 4 toques en pasto sintético cubría el 68 % del polígono, bajo el mínimo de 70 %) y el análisis se hacía sin máscara. La nota de limitaciones dice los rangos usados.

## Probado

- **Pruebas:** app (unitarias) y worker 112, incluida una que reproduce la muestra angosta en pasto de dos tonos.
- **Worker real:** el clip de pasto sintético con la muestra de 4 toques, analizado de nuevo, ya encuentra la cancha y SAM la ve en 4 partes.
- **Pendiente:** grabar en horizontal en un teléfono real y comprobar que el video sale derecho en la app y en el análisis.

## Archivo

`android_native/dist/pp-coach-tracker-0.4.1-release.apk` (18.9 MB, no se versiona; lo deja ahí `make.bat release`).
