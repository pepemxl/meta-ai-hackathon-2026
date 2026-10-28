# Consentimiento para el uso de video en el análisis

> Versión `2026-10-27`. Borrador, **no es asesoría legal**; ver [README.md](README.md). [English version](en/video-consent.md).

Es el texto que acepta el entrenador antes de enviar video a PP-Coach-Tracker. Tiene dos formas:

- **Texto corto para la app:** el diálogo con casillas.
- **Términos completos:** lo que el texto corto resume. Se enlaza desde el diálogo ("Leer los términos completos").

---

## Texto corto para la app

**Antes de enviar tu primer video**

PP-Coach-Tracker usa tus videos solo para generar el análisis de tus partidos. Para enviarlo necesitamos que confirmes:

- [ ] **Tengo derecho a usar este video.** Lo grabé yo o mi club, o tengo permiso de quien lo grabó.
- [ ] **Tengo el consentimiento de las personas que aparecen**, y el de sus padres o tutores si son menores de edad.
- [ ] **Autorizo a PP-Coach-Tracker a procesar el video para generar analíticos**: detectar y seguir jugadores y balón con inteligencia artificial (incluidos modelos de Meta) y calcular estadísticas e insights tácticos.
- [ ] **Leí el [aviso de privacidad](aviso-de-privacidad.md)** y los [términos completos](#términos-completos).

No usamos tu video para entrenar modelos, no lo publicamos ni lo compartimos con otros usuarios. Borramos el video del servidor después del análisis y puedes retirar tu consentimiento cuando quieras desde Ajustes.

[ Cancelar ]   [ Aceptar y enviar ]

**Recordatorio en envíos siguientes** (una línea encima del botón "Enviar"):

> Al enviar confirmas que tienes el consentimiento de las personas que aparecen en el video.

La app en inglés muestra la traducción de [en/video-consent.md](en/video-consent.md#short-text-in-the-app).

---

## Términos completos

### 1. Qué aceptas

Al marcar las casillas y tocar "Aceptar y enviar", tú (el **Usuario**, normalmente el entrenador o el club) aceptas estas condiciones para todos los videos que envíes desde tu cuenta mientras esta versión esté vigente. Si cambian, la app te pedirá aceptarlas otra vez.

### 2. Tus declaraciones

Declaras, bajo tu responsabilidad, que:

1. **Derechos sobre el video.** Grabaste el video, lo grabó tu club o tienes autorización de quien lo hizo. No es una transmisión de televisión ni material de terceros sin permiso.
2. **Consentimiento de las personas.** Antes de grabar informaste a los jugadores, árbitros y demás personas identificables que aparecen, y obtuviste su consentimiento para grabarlos y para que el video se analice con herramientas de inteligencia artificial. Para eso puedes usar el [formato de consentimiento](formato-consentimiento-jugadores.md).
3. **Menores de edad.** Si aparecen menores, el consentimiento lo dio su padre, madre o tutor, y lo conservas.
4. **Evidencia.** Conservas esos consentimientos mientras el video o sus resultados estén en PP-Coach-Tracker y nos los mostrarás si una persona o la autoridad lo pide.
5. **Revocación.** Si alguien retira su consentimiento, borrarás los videos y los resultados donde aparece, o nos pedirás que lo hagamos.

Si alguna declaración resulta falsa, eres responsable frente a las personas que aparecen y frente a PP-Coach-Tracker.

### 3. Para qué usamos el video (finalidad)

Usamos el video **solo para generar los analíticos de tu cuenta**:

1. Recibirlo y guardarlo temporalmente en nuestro servidor.
2. Detectar y seguir a los jugadores y al balón con **SAM 3.1** (Meta Model API).
3. Proyectar las posiciones a la cancha y calcular métricas: mapas de calor, forma del equipo, distancias, presión y eventos.
4. Generar insights tácticos en texto con **Muse Spark** (Meta Model API). A Muse Spark le mandamos métricas y etiquetas como "J3 (#7)", **nunca nombres**. Puede recibir también frames o fragmentos del video para comentarios cualitativos.
5. Mostrarte los resultados en la app y permitirte etiquetar a los jugadores.
6. Diagnosticar fallas del análisis de tu propio video (por ejemplo, si un chunk llegó dañado).

No hacemos con el video nada fuera de esta lista. En particular:

- **No** lo usamos para entrenar ni mejorar modelos de IA, propios o de terceros.
- **No** hacemos reconocimiento facial ni identificamos a las personas por sus rasgos físicos; los jugadores se identifican por dorsal y por la etiqueta que tú les pones.
- **No** lo publicamos, vendemos ni compartimos con otros usuarios o con fines de publicidad.
- **No** lo usamos en demos, materiales de PP-Coach-Tracker ni redes sociales sin un permiso adicional y por escrito.

### 4. Licencia que nos das

Nos das una licencia **no exclusiva, gratuita, revocable y limitada** a la finalidad del punto 3, que incluye copiar el video a nuestros servidores y a los de nuestros proveedores, convertirlo de formato y procesarlo. La licencia termina cuando borras el video o tu cuenta, salvo por las copias de respaldo que se eliminan en su ciclo normal (punto 6).

Los derechos sobre el video siguen siendo tuyos o de quien lo grabó. Los analíticos generados (métricas, tracks e insights) son tuyos para usarlos con tu equipo.

### 5. Proveedores que procesan el video

| Proveedor | Qué recibe | Para qué |
|---|---|---|
| Cloud Provider | Chunks del video y sus metadatos | Servidor de la API y almacenamiento |
| `[Modal / VPS]` | Chunks del video, durante el análisis | Ejecutar el pipeline |
| Meta Platforms (Meta Model API) | Video o frames (SAM 3.1); métricas y etiquetas sin nombres (Muse Spark) | Segmentar y seguir jugadores; redactar insights |

Algunos proveedores están fuera de México (por ejemplo, en Estados Unidos). Les exigimos tratar el video solo para prestarnos el servicio. Con Meta usamos una modalidad de la API en la que **no se permite usar los datos para entrenar sus modelos** `[confirmar en los términos vigentes de la Meta Model API]`.

### 6. Cuánto tiempo lo guardamos

| Dato | Plazo |
|---|---|
| Video (chunks `.mp4`) | Hasta que termina el análisis y, como máximo, `[30]` días después de subirlo. Si el análisis falla, `[30]` días para reintentar |
| Metadatos de los chunks (`.yaml`: tiempos y frames, sin imagen) | Mientras exista el clip |
| Tracks, métricas e insights | Mientras exista el clip o tu cuenta |
| Copia en los proveedores de IA | Lo que dure el procesamiento, según sus términos `[confirmar plazo de Meta]` |
| Historial de este consentimiento (cada aceptación y retiro) | Mientras exista tu cuenta y `[5]` años después, como prueba |

### 7. Borrado y revocación

- Puedes borrar un clip, un partido o tu cuenta completa desde la app, o escribir a `[CORREO DE PRIVACIDAD]`.
- Borrar un clip elimina su video, tracks, métricas e insights del servidor en un plazo de `[7]` días.
- Puedes retirar este consentimiento desde Ajustes. Detiene el análisis de videos nuevos y se cancelan las subidas que esperaban. Lo que ya se procesó se borra si lo pides.

### 8. Calidad de los resultados

Los analíticos se generan automáticamente y pueden contener errores (por ejemplo, confundir a dos jugadores o perder el balón). Son una ayuda para el entrenador, no una evaluación oficial del rendimiento de nadie, y no deben usarse para decisiones médicas, de contratación o disciplinarias sin revisión humana.

### 9. Contenido prohibido

No envíes videos que no sean de práctica o partidos de fútbol, ni videos con desnudez, violencia, vestidores o baños, o grabados sin conocimiento de las personas. Podemos borrar ese contenido y suspender la cuenta.

### 10. Cambios y contacto

Si cambiamos estas condiciones publicamos una versión nueva y la app te pedirá aceptarla antes del siguiente envío. Responsable: `[RESPONSABLE]`, `[DOMICILIO]`, `[CORREO DE PRIVACIDAD]`.
