# 09 · Despliegue en Cloud Provider y publicación de versiones

**Lee antes:** [arquitectura/05-despliegue.md](../arquitectura/05-despliegue.md), [pantallas/README.md](../pantallas/README.md#para-agregar-una-versión) (el formato de cada versión; las nuevas van en `docs/versions/`, que se crea con la primera).

## Desplegar la API

```text
Despliega la API PHP en Cloud Provider en https://ppcoachtracker.pepemxl.com,
en la RAÍZ del subdominio (sin subcarpeta). La app va FUERA de public_html,
en ~/ppcoachtracker_api. El plan no tiene SSH: usa el MCP de Cloud Provider
(busca las operaciones con `search`).

0. Subdominio: con el MCP, revisa que pepemxl.com esté en la cuenta y si
   existe ppcoachtracker.pepemxl.com; si no, dime cómo crearlo (o créalo
   cuando te lo autorice). Averigua su carpeta web real (suele ser
   ~/domains/pepemxl.com/public_html/ppcoachtracker, o
   ~/domains/ppcoachtracker.pepemxl.com/public_html si es un sitio aparte) y
   úsala como WEB_DIR. Verifica que tenga SSL antes de seguir.

1. Herramientas en api/php/tools/:
   - package.sh (APP_ROOT, WEB_DIR, ENV_FILE opcional): deja en dist/ un zip
     ppcoachtracker_api-<fecha>-<commit>.zip con ppcoachtracker_api/ (src, bin, sql,
     VERSION y .env solo si se pasó ENV_FILE), public/ (index.php,
     .htaccess, .user.ini, legal/ y root.php con la ruta de la app) y el
     archivo WEB_DIR; además install.php y deny.htaccess ("Require all
     denied").
   - install_Cloud Provider.php (lo corre un cron, solo por CLI): extrae el zip,
     reemplaza src/bin/sql, copia el .env si venía (chmod 600), crea storage/,
     aplica Schema::apply (y migraciones), crea la cuenta de user.env
     (EMAIL, NAME, PASSWORD) si existe y no existía, copia public/ a la
     carpeta web y BORRA la carpeta temporal (zip y secretos). Nunca imprime
     secretos.
2. Base de datos NUEVA (no reutilices ninguna de una construcción
   anterior): créala con el MCP (hosting_databases_create) como
   <prefijo>_ppcoachtracker, con su propio usuario y una contraseña fuerte
   generada, que no se vuelve a mostrar. Desde la app se llega en
   127.0.0.1:3306. El instalador crea las tablas (Schema::apply).
3. Primera instalación: genera el .env del servidor desde el .env de la raíz
   (PROD_DB_*, PROD_JWT_SECRET, PROD_WORKER_CALLBACK_SECRET, más
   MAX_PART_BYTES, MAX_CHUNK_BYTES y PP_COACH_TRACKER_REQUIRE_META=1) sin
   imprimirlo;
   ese zip lleva secretos: PÍDEME que lo suba yo (o que autorice subirlo).
   Actualizaciones: sin ENV_FILE, el zip no lleva secretos y el servidor
   conserva su .env.
4. Subida: hosting_files_generate-upload-url (TUS; las llaves duran unas
   horas). Primero deny.htaccess como _deploy/.htaccess y COMPRUEBA que
   https://ppcoachtracker.pepemxl.com/_deploy/* responde 403; después
   install.php y el zip.
5. Cron cada minuto: /usr/bin/php <WEB_DIR>/_deploy/install.php.
   Espera a que _deploy desaparezca, lee la salida
   (hosting_cron-jobs_output) y BORRA el cron.
6. Verifica: /health con la versión nueva y db: true (agrega ?n=<algo> por el
   CDN), login con la cuenta, POST /clips sin consentimiento → 428,
   /legal/*.html → 200, y un PUT de una parte de 8 MB
   (…/chunks/1/video?offset=0) a un clip inexistente → 404 de la API (no 413
   del CDN ni de LiteSpeed). No dejes datos de prueba en producción.

Confírmame antes de cada paso que publica algo o borra algo en el servidor.
```

## Trampas conocidas

- **El worker no corre en Cloud Provider:** el hosting compartido no tiene Docker ni procesos largos. El worker va en Modal o en un VPS, con `PP_COACH_TRACKER_API_URL=https://ppcoachtracker.pepemxl.com`, el mismo `WORKER_CALLBACK_SECRET` y `MODEL_API_KEY` como secretos de esa plataforma, y `SAM_API_BASE_URL` **vacío** (SAM real). El simulador de desarrollo nunca se despliega ([13-entorno-desarrollo.md](../13-entorno-desarrollo.md)).
- **Espacio en disco:** con chunks de 5 min, un clip del demo ocupa ~100 MB en `storage/`. Revisar la cuota y borrar los clips de prueba.

- **El clasificador de permisos del agente** puede bloquear subir archivos con secretos o borrar carpetas: no intentar rodearlo; pedirle al usuario que lo haga o que lo autorice.
- **CDN `hcdn`:** cachea respuestas (incluidos 404); verifica con un parámetro distinto en la URL.
- **Un cron olvidado** vuelve a correr el instalador cada minuto: bórralo siempre al terminar.

## Publicar una versión de la app

```text
Publica pp-coach-tracker <versión>:
1. Commit de la función (si no está).
2. Sube versionName y versionCode en android_native/app/build.gradle.kts y,
   si cambió la API, App::VERSION en api/php/src/App.php.
3. gradlew testDebugUnitTest assembleDebug; instala en el emulador y prueba
   contra la API local lo que cambió.
4. Capturas en docs/versions/<versión>/ con la barra en modo demo (adb shell
   settings put global sysui_demo_allowed 1; broadcasts demo: enter, clock
   1000, battery 100, notifications off), reducidas a 1280 px de lado mayor;
   solo datos de prueba o video propio.
5. docs/versions/<versión>/README.md (fecha, versión anterior, probado en,
   qué cambió, capturas, pruebas con resultados reales, notas) y su fila en
   docs/versions/README.md.
6. Commit "Publica pp-coach-tracker <versión> con capturas"; push solo si te
   lo pido. Si cambió el contrato, despliega la API en el mismo momento; si
   solo cambió la app o el resultado del worker, la API no se despliega.
```

## Criterios de aceptación

- [ ] Producción responde con la versión nueva, sin crons de instalación ni carpeta `_deploy`.
- [ ] La versión de la app tiene capturas, README y fila en la tabla de versiones.
