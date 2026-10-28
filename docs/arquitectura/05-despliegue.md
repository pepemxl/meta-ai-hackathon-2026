# Entornos y despliegue

> **Antecedente.** Este documento viene del diseño y del prototipo previos al hackathon, que tenían un respaldo local (detector YOLOv8n, tracker propio e insights por reglas). La versión que se construye **no tiene respaldo**: solo SAM 3.1 y Muse Spark, y solo dependencias con licencia permisiva. Donde este texto diga otra cosa, mandan [prompts/00-contexto.md](../prompts/00-contexto.md) y el prompt de cada etapa.

## Entornos

```mermaid
flowchart TB
    subgraph dev["Laptop (Windows 11 + WSL)"]
        studio["Android Studio / gradlew"]
        emu["Emulador Android<br/>API 34"]
        subgraph docker["Docker en WSL (api/docker-compose.yml)"]
            mysql[("mysql:8<br/>127.0.0.1:13306")]
            phpc["php:8.3-apache<br/>127.0.0.1:8097/pp-coach-tracker"]
            wk["worker (profile worker)<br/>Python + OpenCV"]
            vw["viewer<br/>127.0.0.1:8098"]
        end
        node["API Node<br/>127.0.0.1:3000 (npm start)"]
    end

    subgraph prod["Cloud Provider (cuenta compartida)"]
        sub["ppcoachtracker.pepemxl.com<br/>Let's Encrypt + CDN hcdn"]
        web["public_html/pp-coach-tracker/<br/>index.php, .htaccess, .user.ini, root.php, legal/"]
        appdir["~/ppcoachtracker_api/<br/>src, bin, sql, VERSION, .env (600), storage/"]
        maria[("<prefijo>_ppcoachtracker<br/>127.0.0.1:3306")]
        cron["Cron (solo para instalar)"]
    end

    phone["Teléfono del entrenador<br/>(app release o debug)"]

    emu -->|"http://10.0.2.2:8097/pp-coach-tracker<br/>o :3000"| phpc
    emu --> node
    phpc --> mysql
    wk -->|"http://php/pp-coach-tracker<br/>/internal/*"| phpc
    vw -.->|"lee worker/.local/runs/"| wk
    node --> mysql
    phone -->|HTTPS| sub --> web --> appdir
    appdir --> maria
    cron -.-> appdir
```

| | Local | Producción |
|---|---|---|
| API | Node (`npm start`) o PHP en Docker (`npm run php:up`) | PHP en `https://ppcoachtracker.pepemxl.com` |
| Base | MySQL 8 en Docker (volumen persistente) | MySQL/MariaDB de Cloud Provider, `127.0.0.1:3306` desde la app |
| Configuración | Variables de entorno de `docker-compose.yml` (valores de prueba) | `~/ppcoachtracker_api/.env`, fuera de `public_html`, chmod 600 |
| HTTPS | No (`PP_COACH_TRACKER_ALLOW_HTTP=1`; la app debug permite HTTP) | Sí; `.htaccess` redirige HTTP → HTTPS |
| Cuentas | `npm run user:add` / `php bin/user.php add` | `user.env` en el instalador, o `php bin/user.php` |

## Configuración y secretos

```mermaid
flowchart LR
    envroot[".env de la raíz<br/>(laptop, no se versiona)<br/>PROD_DB_*, PROD_JWT_SECRET,<br/>PROD_WORKER_CALLBACK_SECRET, PROD_COACH_*"]
    gen["genera el .env del servidor<br/>(solo cuando cambia)"]
    srvenv["~/ppcoachtracker_api/.env<br/>DB_*, JWT_SECRET, WORKER_CALLBACK_SECRET,<br/>STORAGE_DIR, MAX_CHUNK_BYTES"]
    cfg["Config::load()<br/>getenv gana sobre el .env"]

    envroot --> gen -->|"ENV_FILE=… package.sh<br/>(zip con secretos: subirlo a mano)"| srvenv
    srvenv --> cfg
```

- Las actualizaciones normales **no llevan `.env`**: `package.sh` sin `ENV_FILE` arma un zip sin secretos y el servidor conserva el suyo.
- `root.php` (en la carpeta web) le dice a `index.php` dónde está la app; `.htaccess` lo niega a la web, igual que `.user.ini`.
- Nada de secretos en el repo: `.env.example` solo tiene los nombres.

## Cómo se despliega la API

Cloud Provider compartido no da SSH en este plan, así que la instalación usa la API de archivos (TUS) y un cron de una sola corrida.

```mermaid
sequenceDiagram
    participant D as Desarrollador (laptop)
    participant H as Cloud Provider (MCP / hPanel)
    participant F as public_html/pp-coach-tracker/_deploy/
    participant C as Cron
    participant I as install.php
    participant S as ~/ppcoachtracker_api + carpeta web

    D->>D: cd api/php && APP_ROOT=… WEB_DIR=… sh tools/package.sh
    Note right of D: dist/ppcoachtracker_api-FECHA-COMMIT.zip,<br/>install.php, deny.htaccess
    D->>H: hosting_files_generate-upload-url
    H-->>D: URL TUS + llaves (unas horas)
    D->>F: sube deny.htaccess como .htaccess
    D->>F: curl /_deploy/* → debe dar 403
    D->>F: sube install.php y el zip
    D->>H: hosting_cron-jobs_create (cada minuto)
    C->>I: /usr/bin/php …/_deploy/install.php
    I->>I: extrae el zip
    I->>S: reemplaza src/, bin/, sql/ y VERSION
    I->>S: .env si venía en el zip (chmod 600)
    I->>S: Schema::apply + migraciones
    I->>S: cuenta de user.env, si se subió
    I->>S: copia public/ a la carpeta web (incluye legal/)
    I->>F: borra _deploy/ (zip y secretos incluidos)
    D->>H: hosting_cron-jobs_output (lee el resultado)
    D->>H: hosting_cron-jobs_delete
    D->>S: curl /health → versión nueva, db: true
```

Detalle y comandos: `api/php/README.md`.

## Cómo se publica la app

```mermaid
flowchart LR
    feat["commit de la función"] --> bump["subir versionName y versionCode<br/>(y App::VERSION de la API PHP)"]
    bump --> build["gradlew testDebugUnitTest assembleDebug"]
    build --> emu["instalar en el emulador<br/>y probar contra la API local"]
    emu --> shots["capturas en docs/versions/VERSIÓN/<br/>(modo demo de la barra)"]
    shots --> notes["README de la versión<br/>+ fila en docs/versions/README.md"]
    notes --> rel["commit de la versión → push"]
    rel --> dep["desplegar la API si cambió"]
```

- Pasos completos en [docs/versions/README.md](../pantallas/README.md#para-agregar-una-versión).
- Si cambia el contrato (como el consentimiento en 0.3.2), la API y la app se publican juntas. Si solo cambia la app o el resultado del worker (como 0.3.10), la API PHP se queda en su versión y no se despliega.
- El release se firma con la llave de `android_native/keystore.properties`, que no se versiona.

## Pruebas

| Qué | Comando | Contra qué |
|---|---|---|
| API Node | `npm test` | Repositorio en memoria |
| API Node con MySQL | `TEST_MYSQL=1 npm test` | MySQL de Docker; las pruebas borran sus datos |
| API PHP | `npm run php:up && npm run php:migrate && npm run test:php` | PHP + MySQL en Docker, por HTTP, `.htaccess` incluido |
| Worker | `npm run worker:test` | pytest en Docker: chunks, tracker, equipos, cortes, cuadros que cuentan, balón, cancha (plantilla, solver, propagación, líneas, SAM con streams inventados), métricas en la imagen y en metros, reglas, escalera y un trabajo contra una API falsa |
| App | `gradlew testDebugUnitTest` | JVM: chunks, stats, biblioteca, cliente HTTP (MockWebServer), consentimiento, máscara de la cancha, puntos y homografía, análisis, textos |
| Chunks reales | `python experiments/check_chunks.py <carpeta>` | Chunks sacados del teléfono: PyYAML y OpenCV |
| Meta API | `python3 experiments/smoke_test.py` | Muse Spark y SAM 3.1 con `MODEL_API_KEY` |
| Producción | `curl https://ppcoachtracker.pepemxl.com/health` | Versión y base de datos |

## El worker en producción (pendiente)

Cloud Provider compartido no puede correr el worker (procesos largos, OpenCV). La misma imagen de `worker/` va en un VPS o en Modal, con `PP_COACH_TRACKER_API_URL=https://ppcoachtracker.pepemxl.com` y el `WORKER_CALLBACK_SECRET` de producción (de `PROD_WORKER_CALLBACK_SECRET`, sin versionar), más `MODEL_API_KEY` cuando haya. El modelo `yolov8n.onnx` se monta o se copia a la imagen (licencia AGPL: no se versiona). La API de producción ya tiene todo lo que el worker necesita (`clip_analyses`, las rutas `/internal/*`, `pitch_mode` y `calibration`). Las líneas de la cancha y SAM se encienden con `PP_PITCH_LINES` y `PP_PITCH_SAM`; por omisión están apagados.
