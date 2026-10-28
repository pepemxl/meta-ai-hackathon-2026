# Aviso de privacidad integral de PP-Coach-Tracker

> Versión `2026-10-27`. Borrador, **no es asesoría legal**; ver [README.md](README.md). Se publica en `https://ppcoachtracker.pepemxl.com/legal/privacidad.html`. [English version](en/privacy-notice.md).

## 1. Quién es el responsable

`[RESPONSABLE: nombre o razón social]`, con domicilio en `[DOMICILIO]` (en adelante, **PP-Coach-Tracker**), es responsable de los datos personales de las cuentas de usuario y **encargado** de los datos que aparecen en los videos que los usuarios suben para su análisis. Contacto de privacidad: `[CORREO DE PRIVACIDAD]`.

Este aviso cumple con la Ley Federal de Protección de Datos Personales en Posesión de los Particulares (LFPDPPP) y su reglamento.

## 2. A quién aplica

- **Usuarios:** entrenadores, clubes o personas que crean una cuenta en la app pp-coach-tracker.
- **Personas en los videos:** jugadores, árbitros y otras personas que aparecen en los videos que suben los usuarios. Respecto de ellas, el usuario que graba es el responsable y quien recaba su consentimiento; PP-Coach-Tracker trata los videos por su cuenta y siguiendo sus instrucciones.

## 3. Qué datos tratamos

| Categoría | Datos | De dónde vienen |
|---|---|---|
| Cuenta | Correo, nombre, contraseña (guardada cifrada con bcrypt), fecha de alta | El usuario |
| Seguridad | Dirección IP y correo de los intentos fallidos de inicio de sesión (15 minutos), registro técnico de peticiones | El servidor |
| Partidos | Nombres de los equipos, colores de camiseta, fecha y lugar del partido | El usuario |
| Video | Imagen de las personas que aparecen (cara, cuerpo, dorsal, ropa), **sin audio**, a 10 fotogramas por segundo y máximo 720p | El usuario, desde la app |
| Analíticos | Posiciones, trayectorias, métricas e insights por jugador, identificados por un ID (por ejemplo, "J3") y dorsal | Generados por PP-Coach-Tracker |
| Etiquetas | Dorsal, posición y, si el usuario quiere, un nombre, apodo o iniciales | El usuario |
| Consentimiento | Cada aceptación y retiro: versión del texto, fecha, IP y versión de la app | La app y el servidor |

**No tratamos datos biométricos**: no hacemos reconocimiento facial ni identificamos a las personas por sus rasgos. Tampoco pedimos datos de salud, ubicación GPS ni contactos. La imagen de menores de edad solo se trata si el usuario declaró contar con el consentimiento de sus padres o tutores.

## 4. Para qué los usamos

**Finalidades principales** (necesarias para el servicio):

1. Crear y proteger tu cuenta, e iniciar sesión.
2. Recibir los videos y generar los analíticos: detección y seguimiento de jugadores y balón, métricas tácticas e insights con inteligencia artificial.
3. Mostrarte los resultados y permitirte etiquetar jugadores.
4. Registrar tu consentimiento y atender solicitudes de derechos ARCO.
5. Detectar abusos, intentos de acceso no autorizado y fallas técnicas.

**Finalidades secundarias:** ninguna. No usamos los datos para publicidad, perfiles comerciales ni para entrenar modelos de inteligencia artificial. Si algún día quisiéramos hacerlo, te pediríamos un consentimiento aparte, que podrías negar sin perder el servicio.

## 5. Con quién los compartimos

| Destinatario | País | Datos | Finalidad |
|---|---|---|---|
| Cloud Provider | `[país de los servidores]` | Todos los datos de la cuenta y los videos | Alojamiento de la API y la base de datos (encargado) |
| `[Modal / VPS]` | `[país]` | Videos durante el análisis | Ejecutar el análisis (encargado) |
| Meta Platforms (Meta Model API) | Estados Unidos | Video o frames (SAM 3.1); métricas y etiquetas **sin nombres** (Muse Spark) | Segmentación de video y redacción de insights (encargado) |

Estas son remisiones a encargados que tratan los datos por cuenta de PP-Coach-Tracker, por lo que no requieren tu consentimiento. Fuera de ellas, solo compartimos datos si lo exige una autoridad competente.

Nunca compartimos los nombres de los jugadores con terceros: viven solo en la cuenta del usuario que los escribió.

## 6. Cuánto tiempo los conservamos

| Dato | Plazo |
|---|---|
| Video | Hasta terminar el análisis y como máximo `[30]` días después de subirlo |
| Analíticos y etiquetas | Mientras el usuario conserve el clip o la cuenta |
| Cuenta | Hasta que el usuario la borre, o `[2]` años sin uso |
| Intentos fallidos de inicio de sesión | 15 minutos |
| Historial del consentimiento | Vida de la cuenta y `[5]` años después |
| Respaldos del servidor | Ciclo de rotación de `[N]` días |

## 7. Tus derechos (ARCO) y cómo ejercerlos

Puedes **acceder** a tus datos, **rectificarlos**, **cancelarlos** (borrarlos) u **oponerte** a su tratamiento, así como **revocar tu consentimiento** o limitar su uso.

- **Usuarios:** desde la app (Ajustes → retirar el consentimiento, y borrar clips o partidos) o por correo a `[CORREO DE PRIVACIDAD]`.
- **Personas que aparecen en un video:** dirígete primero al entrenador o club que te grabó. También puedes escribirnos a `[CORREO DE PRIVACIDAD]` indicando el equipo, la fecha del partido y, si puedes, una foto o descripción que nos permita ubicar el video; se lo comunicaremos al usuario y borraremos el video y sus analíticos si procede.
- **Padres o tutores** pueden ejercer estos derechos por los menores a su cargo, acreditando la representación.

La solicitud debe incluir tu nombre, un medio de contacto, el derecho que ejerces y los datos a los que se refiere. Respondemos en un máximo de **20 días hábiles** y, si procede, lo hacemos efectivo en los 15 días hábiles siguientes.

Si consideras que no atendimos tu solicitud, puedes acudir a la autoridad de protección de datos (Secretaría Anticorrupción y Buen Gobierno).

## 8. Seguridad

- Conexión cifrada (HTTPS) entre la app y el servidor.
- Contraseñas cifradas con bcrypt; sesiones con tokens firmados que vencen a los 30 días y se guardan cifrados en el teléfono.
- El video y la configuración se guardan fuera de la carpeta pública del servidor; cada usuario solo puede ver sus propios clips.
- Límite de intentos de inicio de sesión.
- Los nombres de los jugadores no salen a proveedores de IA ni a registros de errores.

## 9. Cookies

La app no usa cookies ni herramientas de rastreo publicitario. La página de este aviso no usa cookies de análisis.

## 10. Cambios a este aviso

Publicaremos cualquier cambio en `https://ppcoachtracker.pepemxl.com/legal/privacidad.html` con su nueva versión y fecha. Si el cambio afecta las finalidades o los destinatarios, la app te pedirá aceptarlo antes de enviar un video nuevo.

Última actualización: 23 de octubre de 2026.
