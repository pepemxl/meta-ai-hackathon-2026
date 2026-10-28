# 07 · Consentimiento de uso del video (app + API + textos)

**Lee antes:** [legal/README.md](../legal/README.md) y los textos de [legal/](../legal/) y [legal/en/](../legal/en/) (son documentación, no código: se reutilizan tal cual), [arquitectura/04-flujos.md](../arquitectura/04-flujos.md#4-consentimiento-aceptar-y-retirar).

```text
Implementa el consentimiento del entrenador para usar sus videos en el
análisis, con historial completo, según docs/legal/README.md. La parte de la
API (POST /clips con consent, 428, POST/GET /consent, consent_events) ya
existe en Node y PHP: verifica que esté y no la dupliques.

1. Páginas públicas de los textos: api/php/tools/legal_pages.py (Python +
   paquete markdown, licencia BSD) convierte docs/legal/*.md y docs/legal/en/*.md en HTML
   estático en api/php/public/legal/:
     aviso-de-privacidad.md → privacidad.html
     consentimiento-uso-de-video.md → consentimiento.html
     formato-consentimiento-jugadores.md → formato-jugadores.html
     en/privacy-notice.md → en/privacy.html
     en/video-consent.md → en/consent.html
     en/players-consent-form.md → en/players-form.html
   - Enlaces entre textos resueltos relativos a cada archivo (así "English
     version" ↔ "Spanish original" funcionan); los demás enlaces al repo se
     quitan (el repo es privado). Anclas como GitHub (slugify_unicode).
   - Quita las notas internas ("; ver README.md", "Se publica en …").
   - "- [ ]" → "☐". html lang según el idioma. CSS mínimo inline, modo
     oscuro, sin cookies ni scripts externos, legible en un teléfono.
   - El HTML generado se versiona; tools/package.sh lo incluye.

2. App (versión de texto: data/Consent.kt, VERSION = "2026-10-27", igual que
   en las dos APIs):
   - Antes del primer "Enviar a análisis" (o si la versión aceptada no es la
     vigente) un diálogo ConsentDialog con 4 casillas: derecho sobre el
     video; consentimiento de las personas y de los tutores de menores;
     autorización para el análisis con IA (incluidos modelos de Meta); leí el
     aviso de privacidad y los términos. "Aceptar y enviar" solo con las 4.
     Enlaces "Aviso de privacidad" y "Términos completos" que abren
     <servidor>/legal/… en el navegador, en el idioma de la app (en/ si está
     en inglés); sin servidor configurado, https://ppcoachtracker.pepemxl.com.
     Texto de promesa abajo. Textos exactos: sección "Texto corto para la
     app" de docs/legal/consentimiento-uso-de-video.md y su versión en inglés.
   - Al aceptar se guarda versión y hora (ISO, UTC) en los ajustes, se avisa
     a la API y se envía el clip que esperaba.
   - Cada POST /clips lleva consent {version, accepted_at, app_version}.
   - En los envíos siguientes, una línea encima del botón: "Al enviar
     confirmas que tienes el consentimiento de las personas que aparecen en
     el video."
   - Ajustes → "Privacidad y consentimiento": "Aceptaste los términos de uso
     de video (versión X) el <fecha local>", botón "Retirar consentimiento",
     y enlaces al aviso, los términos y el formato para jugadores y tutores.
   - analysis/ConsentWorker.kt: avisa cada aceptación y cada retiro a POST
     /consent en una cadena única "consent" (APPEND_OR_REPLACE, en orden),
     con red, backoff desde 30 s, hasta 20 intentos; un 401 también se
     reintenta (espera a que se inicie sesión).
   - Retirar: encola el retiro, CANCELA las subidas pendientes
     (cancelAllWorkByTag de UploadWorker) y borra la aceptación local.
   - 428 en una subida: "consent withdrawn" → "retiraste el consentimiento:
     vuelve a enviar para aceptarlo de nuevo"; si no → "cambiaron los
     términos de uso: actualiza la app".

Pruebas: JVM de Consent (URLs por idioma, JSON, versión vigente, 428 no se
reintenta) y de ApiClient.consentEvent con MockWebServer.
```

## Trampas conocidas

- **Repo privado:** los enlaces de la app no pueden apuntar a GitHub; por eso el propio servidor sirve los textos.
- **Retiro solo en el teléfono:** no basta; sin avisar a la API no hay historial (la primera construcción lo tuvo que agregar en 0.3.3).
- **Una subida que esperaba en el teléfono** cuando se retira el consentimiento: además de cancelarla, el servidor la rechaza por la regla de "aceptación anterior al último retiro".
- **El CDN de Cloud Provider cachea los 404:** si pruebas una página antes de desplegarla, después de desplegar agrega `?n=<algo>` a la URL para verificar.

## Criterios de aceptación

- [ ] Las 6 páginas responden 200 en Docker, con `lang` correcto y enlaces entre versiones.
- [ ] Emulador: el diálogo aparece con el botón deshabilitado; con las 4 casillas el clip sube y en la base queda el evento `accept` ligado al clip.
- [ ] Retirar en Ajustes crea el evento `withdraw` en el servidor y el siguiente envío vuelve a pedir el consentimiento.
- [ ] Con la app en inglés, "Privacy notice" abre `/legal/en/privacy.html`.
