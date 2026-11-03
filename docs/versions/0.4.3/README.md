# pp-coach-tracker 0.4.3

- **Fecha:** 3 de noviembre de 2026.
- **Versión anterior:** [0.4.2](../0.4.2/README.md).
- **Probado en:** pruebas unitarias y cuadros de dos canchas reales grabadas con la app; el release se compiló y firmó, y **falta probarlo en un teléfono real**.
- **Versiones:** app 0.4.3 (`versionCode` 7). La API sigue en 0.4.0, sin cambios en el contrato. El worker de producción ya ignora las muestras que mezclan sombra con la cancha.

## Qué cambió

- **Muestra de la cancha en la sombra:** un toque de muestra que cae en sombra azulada o en algo casi gris se rechaza con su propio aviso ("Ese toque cayó en sombra o en algo gris. Toca la tierra donde le da el sol"), en vez de aceptarse.
  - Se rechaza un parche con tono medio azul o violeta (90–170) o con saturación media menor que 20.
  - En la cancha de terracería de Yerbabuena 2, la sombra de un árbol da un tono medio de 97 a 110; la tierra al sol, de 13 a 17, y el pasto, de 41 a 55. Una muestra que juntaba sol y sombra cubría hasta el cielo.
- **En el worker:** una muestra que abarca más de 45 tonos (la que mandan las apps anteriores con un toque en la sombra) se ignora, se usan los colores fijos y la nota de limitaciones lo dice.
- Trae también lo de [0.4.2](../0.4.2/README.md) y [0.4.1](../0.4.1/README.md): girar un video 90° y grabar en horizontal sin que quede girado.

## Probado

- **Pruebas:** app (unitarias), con una nueva con los colores de las sombras reales y con pasto y tierra que deben seguir aceptándose; worker, 116 pasan y 1 falla esperada (el pasto lejano de Yerbabuena 1, pendiente).
- **Canchas reales:** cuadros de Yerbabuena 1 (pasto sintético) y Yerbabuena 2 (terracería), en `worker/tests/fixtures/real`.
- **Pendiente:** tomar la muestra en un teléfono real, girar un clip y grabar en horizontal (de 0.4.1 y 0.4.2).

## Archivo

`android_native/dist/pp-coach-tracker-0.4.3-release.apk` (18.9 MB, no se versiona; lo deja ahí `make.bat release`).
