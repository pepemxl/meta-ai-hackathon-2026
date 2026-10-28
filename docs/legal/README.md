# Notas legales de PP-Coach-Tracker

> Borradores para el MVP del hackathon, **no son asesoría legal**. Antes de abrir la app a usuarios reales (sobre todo con jugadores menores de edad) un abogado debe revisarlos y llenar los datos marcados con `[corchetes]`.

Estos textos cubren el momento en que el entrenador **sube un video**: qué acepta, qué hacemos con el video y cómo lo demostramos después. La parte de diseño (IDs en lugar de nombres, sin biometría, retención corta) está en [../10-privacidad-jugadores.md](../10-privacidad-jugadores.md).

| Documento | Para quién | Dónde aparece |
|---|---|---|
| [consentimiento-uso-de-video.md](consentimiento-uso-de-video.md) | El entrenador que sube el video | Diálogo antes del primer envío a análisis, con casillas; texto corto en la app y completo en la web |
| [aviso-de-privacidad.md](aviso-de-privacidad.md) | El entrenador y las personas que aparecen en el video | `https://ppcoachtracker.pepemxl.com/legal/privacidad.html` y enlace desde el login y Ajustes |
| [en/](en/) | Traducción al inglés de los tres textos (la versión en español prevalece) | `https://ppcoachtracker.pepemxl.com/legal/en/…`; la app en inglés enlaza a estas |
| [formato-consentimiento-jugadores.md](formato-consentimiento-jugadores.md) | Jugadores adultos y padres o tutores de menores | Formato imprimible que el entrenador recaba **antes de grabar** y guarda él |

## Quién es quién

| Papel (LFPDPPP) | Quién | Por qué |
|---|---|---|
| **Responsable** del video del equipo | El entrenador o club que graba | Decide grabar, a quién y para qué; tiene la relación con los jugadores y recaba su consentimiento |
| **Encargado** | PP-Coach-Tracker (`[RESPONSABLE]`) | Trata el video solo para generar los analíticos que pide el entrenador |
| **Subencargados** | Cloud Provider (servidor y base de datos), proveedor del worker (Modal o VPS) y Meta (SAM 3.1 y Muse Spark por la Meta Model API) | Infraestructura y modelos de IA |
| **Responsable** de la cuenta | PP-Coach-Tracker | Datos de registro del entrenador (correo, nombre, contraseña cifrada) |

Esa división es la que sostiene el consentimiento: PP-Coach-Tracker no puede pedir permiso a cada jugador, así que el entrenador **declara** que lo tiene, y nosotros limitamos el uso del video a lo que él pidió.

## Qué debe hacer la app

1. **Antes del primer "Enviar a análisis"** mostrar el diálogo de [consentimiento-uso-de-video.md](consentimiento-uso-de-video.md#texto-corto-para-la-app) con las cuatro casillas. El botón "Aceptar y enviar" se habilita solo con todas marcadas.
2. **En cada envío** (después del primero) una línea recordatoria encima del botón: "Al enviar confirmas que tienes el consentimiento de las personas que aparecen". Sin casillas, para no cansar.
3. **Si cambia la versión del texto**, volver a mostrar el diálogo completo.
4. Enlaces al aviso de privacidad en el login y en Ajustes, y un botón **"Borrar mis datos"** en Ajustes (derechos ARCO).
5. El aviso que ya existe al crear un partido (v0.2) se queda: es el recordatorio en el momento de grabar.

## Cómo se registra el consentimiento

Hay que poder demostrar **quién aceptó, qué texto y cuándo**. Así lo hacen la app y la API (las dos, Node y PHP):

- **Historial completo.** Cada aceptación y cada retiro es una fila de `consent_events` (en `api/db/schema.sql`): acción, versión, `at` (reloj del teléfono), `received_at` (reloj del servidor), IP y versión de la app, todo en UTC.
- **La app avisa cada evento** a `POST /consent` en cuanto pasa, con reintentos: espera a tener red y, si no hay sesión, a que se inicie. `GET /consent` regresa el historial del entrenador.
- **Cada envío** manda `consent: { "version": "2026-10-27", "accepted_at": "<ISO 8601>", "app_version": "0.3.3" }` en `POST /clips`, y `clip_consent_events` liga el clip a esa aceptación.
- **Rechazos (`428`):** sin consentimiento, con una versión que no es la vigente o con una fecha inválida (`consent required`; la app dice "cambiaron los términos de uso: actualiza la app"), o con una aceptación de antes del último retiro (`consent withdrawn`).
- **Al retirar,** la app cancela las subidas que esperaban; si alguna llega de todos modos, el servidor la rechaza por la regla anterior.
- `GET /health` dice la versión vigente (`consent_version`).
- **0.3.2 guardaba solo la primera aceptación por versión** (`consents` y `clip_consents`). La migración de `Schema::migrate()` las pasa a `consent_events` y borra las tablas viejas solo si todo quedó copiado; las aceptaciones repetidas de esa época no quedaron registradas.
- El texto de cada versión se queda en este repo (historial de git) y no se edita: un cambio de fondo es una versión nueva.

### Cambiar el texto

1. Editar los documentos de esta carpeta y su traducción en `en/`, y agregar la fila en "Versiones de los textos".
2. Cambiar la versión en los tres lugares: `api/src/consent.js`, `api/php/src/Consent.php` y `Consent.VERSION` en la app (`android_native/.../data/Consent.kt`), y los textos cortos de la app (`Strings.kt`).
3. Regenerar las páginas públicas: `cd api/php && python tools/legal_pages.py`.
4. Desplegar la API y publicar la app juntas: con la API nueva, la app vieja recibe `428` hasta que se actualiza.

## Versiones de los textos

| Versión | Fecha | Cambio |
|---|---|---|
| `2026-10-27` | 23 de octubre de 2026 | Primera versión: consentimiento de uso de video, aviso de privacidad y formato para jugadores y tutores |
| `2026-10-27` | 23 de octubre de 2026 | Traducción al inglés (`en/`). Aclaraciones sin cambio de fondo: se guarda cada aceptación y retiro, y el consentimiento se retira desde Ajustes |

## Antes de abrir a usuarios reales

- [ ] Abogado revisó los tres documentos y llenó los `[corchetes]`.
- [ ] Revisamos los términos de datos de la Meta Model API: sin el tier *contributor* y sin uso para entrenamiento con video de menores. Si no se puede garantizar, cambiar el texto.
- [ ] Publicado (desplegar la API con `public/legal/`) el aviso de privacidad en `https://ppcoachtracker.pepemxl.com/legal/privacidad.html`.
- [x] La API exige `consent` y guarda la tabla `consents`; la app muestra el diálogo y lo manda.
- [ ] Funciona el borrado: de un clip, de un partido y de la cuenta completa, incluidos los chunks en `storage/`.
- [ ] Cron de retención que borra los chunks después del análisis (pendiente en ../../api/php/README.md).
- [ ] Correo de privacidad (`[CORREO DE PRIVACIDAD]`) creado y monitoreado; respuesta a solicitudes ARCO en 20 días hábiles.
- [ ] Para el demo del hackathon: solo video propio con consentimiento firmado ([../08-uso-de-video.md](../08-uso-de-video.md)).

## Marco legal considerado

- **LFPDPPP** (Ley Federal de Protección de Datos Personales en Posesión de los Particulares), publicada en el DOF el 20 de marzo de 2025. La autoridad ya no es el INAI, sino la Secretaría Anticorrupción y Buen Gobierno. Confirmar con el abogado el reglamento vigente.
- **Ley Federal del Derecho de Autor, art. 87:** el retrato de una persona solo puede usarse o publicarse con su consentimiento (o el de sus representantes). Aplica a la imagen de los jugadores.
- **Código Civil Federal:** el consentimiento de un menor lo da quien ejerce la patria potestad o la tutela.
- **GDPR**, solo si hay usuarios o jugadores en la Unión Europea. Los textos ya usan su lenguaje (finalidad, base legal, supresión) para no reescribirlos.
