# API de pp-coach-tracker en PHP

La misma API que `api/` (Node), en **PHP 8.3 sin Composer ni dependencias**, para el hosting compartido de Cloud Provider (LiteSpeed, MySQL/MariaDB). El contrato está en [`../README.md`](../README.md) y el esquema es el mismo `../db/schema.sql`.

## En local

```bash
cd api
npm run db:up          # MySQL (ver la nota de Hyper-V en ../README.md)
npm run php:up         # Apache + PHP 8.3 en http://localhost:8097/pp-coach-tracker
npm run php:migrate    # aplica db/schema.sql
npm run test:php       # pruebas de contrato por HTTP (con .htaccess)

wsl -e docker compose exec -T -e PP_COACH_TRACKER_PASSWORD='…' php php bin/user.php add coach@example.com "Nombre"
wsl -e docker compose exec php php bin/user.php list
```

El contenedor sirve la misma carpeta `public/` dos veces: en `/pp-coach-tracker` con los límites de producción y en `/pp-coach-tracker-strict` con `PP_COACH_TRACKER_REQUIRE_META=1` y `MAX_CHUNK_BYTES` de 12 MB, para que las pruebas cubran esos casos sin otro contenedor. Las pruebas usan `PHP_API_URL` si está definida.

## Diferencias con Node

| | Node | PHP |
|---|---|---|
| Contraseñas | scrypt | `password_hash` (bcrypt). Un usuario creado con una API no inicia sesión en la otra: en producción solo existe la PHP |
| JWT | jsonwebtoken | HS256 propio, compatible: con el mismo `JWT_SECRET` un token sirve en las dos (hay prueba) |
| Límite de login | En memoria, 10 por correo | Tabla `login_failures`: 10 por correo **y** 30 por IP en 15 min, con limpieza oportunista de lo de más de 1 día |
| Tomar un trabajo | `SELECT … FOR UPDATE SKIP LOCKED` | `UPDATE … SET id = LAST_INSERT_ID(id) … ORDER BY id LIMIT 1` (atómico, sirve en MariaDB). Antes regresa a la cola lo que lleva más de `JOB_STALE_SECONDS` (7200 s) en `running` |
| Partes del video | `express.raw` | De `php://input` a un temporal por bloques (`stream_copy_to_stream` con tope `MAX_PART_BYTES`) y luego al `.partial` con candado; no pasa por `upload_max_filesize` |
| Multipart por PUT | multer | PHP solo llena `$_FILES` en POST: `Http\Multipart::parse` lo hace a mano (YAML chico, en memoria) |
| sha256 del chunk | stream | `hash_file`, sin cargar el chunk en memoria |
| Ruta | Raíz | Se quita el prefijo de la carpeta (`dirname(SCRIPT_NAME)`): sirve en `/pp-coach-tracker` y en la raíz del dominio |
| Sin `JWT_SECRET` | No arranca | Responde 500 `server misconfigured` en todas las rutas |

## Estructura

```
php/
├── public/            lo único en la carpeta web
│   ├── index.php      display_errors 0; la app sale de root.php o de PP_COACH_TRACKER_ROOT
│   ├── .htaccess      HTTPS (salvo ALLOW_HTTP o X-Forwarded-Proto https), Authorization, index.php
│   └── .user.ini      límites de PHP para LiteSpeed
├── src/               PpCoachTracker\ (PSR-4)
│   ├── bootstrap.php  autoload + Config::load
│   ├── App.php        rutas y VERSION
│   ├── Auth.php, Consent.php, Storage.php, Repo.php, Schema.php, Config.php, Db.php
│   └── Http/          Request, Response, Router, Multipart, HttpError
├── bin/               migrate.php, user.php (solo terminal)
└── docker/Dockerfile  php:8.3-apache, como el hosting
```

## Configuración

Las mismas variables que Node (`.env.example`), en el `.env` de la raíz de la app (fuera de la carpeta web); `getenv` gana. En producción: `PP_COACH_TRACKER_REQUIRE_META=1` y `PP_COACH_TRACKER_ALLOW_HTTP` vacío.

## Despliegue

```bash
APP_ROOT=/home/<usuario>/ppcoachtracker_api \nWEB_DIR=/home/<usuario>/domains/pepemxl.com/public_html/ppcoachtracker \n[ENV_FILE=<.env del servidor>] [USER_ENV=<user.env>] bash api/php/tools/package.sh
```

Deja en `dist/` el zip, `install.php` y `deny.htaccess`. Solo la primera instalación lleva `ENV_FILE` (secretos); `USER_ENV` (EMAIL, NAME, PASSWORD) crea una cuenta si no existe. El paquete y la instalación en https://ppcoachtracker.pepemxl.com están en la etapa 9 ([docs/prompts/09-despliegue.md](../../docs/prompts/09-despliegue.md)): la app fuera de `public_html`, `public/` en la carpeta web con un `root.php` que apunta a la app, y una base nueva con el prefijo de la cuenta.
