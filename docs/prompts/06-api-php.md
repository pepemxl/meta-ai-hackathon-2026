# 06 · API en PHP para Cloud Provider

**Lee antes:** [arquitectura/03-api.md](../arquitectura/03-api.md) (sobre todo "Ciclo de una petición (PHP)"), [arquitectura/05-despliegue.md](../arquitectura/05-despliegue.md), el contrato de [04-api-node.md](04-api-node.md).

```text
Escribe en api/php/ la misma API que api/ (Node), en PHP 8.3 SIN Composer ni
dependencias, para el hosting compartido de Cloud Provider (LiteSpeed, sin SSH en
el plan, MySQL/MariaDB en 127.0.0.1:3306). Mismo contrato, mismo
db/schema.sql (compartido, no copiado).

Estructura:
- src/bootstrap.php: autoload PSR-4 del prefijo PpCoachTracker\ y Config::load
  (.env de la raíz de la app; getenv gana).
- src/Config.php, Db.php (PDO, time_zone +00:00, errores como excepciones),
  Http/{HttpError, Response, Router, Request, Multipart}.php, Auth.php,
  Storage.php, Repo.php (todas las consultas), Schema.php (aplica
  schema.sql sentencia por sentencia, quitando comentarios, y luego
  migrate() para lo que CREATE TABLE IF NOT EXISTS no cubre), Consent.php,
  App.php (rutas; VERSION).
- public/index.php (display_errors 0; la raíz de la app sale de root.php o
  de la variable PP_COACH_TRACKER_ROOT), public/.htaccess, public/.user.ini.
- bin/migrate.php, bin/user.php (add <correo> "<nombre>", password <correo>,
  list; la contraseña de PP_COACH_TRACKER_PASSWORD o pedida por la terminal,
  mínimo 10 caracteres).
- docker/Dockerfile: php:8.3-apache con pdo_mysql, zip, rewrite y headers,
  la API servida en /pp-coach-tracker (subcarpeta, para probar ese caso) y
  SetEnvIf PP_COACH_TRACKER_ALLOW_HTTP=1 (sin TLS en local).

Diferencias obligadas con Node:
- Contraseñas con password_hash (bcrypt). JWT compatible con jsonwebtoken
  (HS256, sub "u<id>", iat, exp 30 días): con el mismo JWT_SECRET, un token de
  una API sirve en la otra (pruébalo).
- Límite de login en la tabla login_failures: 10 por correo Y 30 por IP en
  15 min → 429 con Retry-After; limpieza oportunista de lo de más de 1 día.
- Trabajos: claimJob con UPDATE … SET id = LAST_INSERT_ID(id) … ORDER BY id
  LIMIT 1 (atómico, funciona en MariaDB sin SKIP LOCKED). Antes, regresa a la
  cola lo que lleva más de JOB_STALE_SECONDS (7200) en running.
- Request: JSON con tope de 64 KB (413 si pasa); las partes del video
  (PUT …/video?offset=) llegan CRUDAS: se copian de php://input al .partial
  en bloques (fopen/stream_copy_to_stream), con tope MAX_PART_BYTES, sin
  pasar por upload_max_filesize. El PUT que cierra el chunk (yaml + sha256)
  es multipart chico: PHP SOLO llena $_FILES en POST, así que se parsea a
  mano (Multipart::parse escribe las partes con tempnam). El sha256 del
  chunk armado se calcula con hash_file, sin cargarlo en memoria. Los
  campos de un POST multipart están en $req->fields (úsalos en
  /internal/jobs/:id/result).
- Las rutas se resuelven sin el prefijo de la carpeta (dirname de
  SCRIPT_NAME), así funciona en /pp-coach-tracker y en la raíz de
  ppcoachtracker.pepemxl.com.

.htaccess: Options -Indexes; redirige a HTTPS salvo con PP_COACH_TRACKER_ALLOW_HTTP
o si X-Forwarded-Proto ya es https (detrás del CDN de Cloud Provider HTTPS llega
como HTTP: sin esa condición la redirección no termina); pasa la cabecera
Authorization (RewriteRule .* - [E=HTTP_AUTHORIZATION:%{HTTP:Authorization}]);
manda a index.php todo lo que no sea archivo (así se sirven estáticos como
legal/*.html); niega root.php y .user.ini.
.user.ini: upload_max_filesize 32M, post_max_size 40M,
max_execution_time 120, memory_limit 256M, display_errors Off, log_errors On.

docker-compose.yml: servicio php (127.0.0.1:8097, volúmenes ./php:/app,
./php/public:/var/www/html/pp-coach-tracker, ./db:/db:ro) con variables de prueba:
JWT_SECRET test-jwt-secret-0123456789abcdefghijkl,
WORKER_CALLBACK_SECRET test-worker-secret-0123456789,
API_TOKENS test-coach1:test-token-0123456789,test-coach2:other-coach-token-9876,
MAX_PART_BYTES 8388608 y MAX_CHUNK_BYTES 167772160 (los de producción),
STORAGE_DIR
/tmp/pp-coach-tracker-storage.
Scripts npm: php:up, php:migrate, test:php.

Pruebas: tests/php/contract.test.js, las MISMAS pruebas de contrato de Node
pero por HTTP contra http://127.0.0.1:8097/pp-coach-tracker (PHP_API_URL), con
.htaccess incluido. Crea cuentas con `docker compose exec php php
bin/user.php` y lee/limpia la base con `docker compose exec mysql mysql -N`.
Prueba extra: un trabajo atorado en running regresa a la cola.

Escribe api/php/README.md (diferencias con Node, local, configuración,
despliegue).
```

## Trampas conocidas

- **`PUT` multipart:** PHP no llena `$_FILES`; sin el parser propio, la app no puede cerrar un chunk.
- **Chunks de 5 min:** un chunk armado pesa hasta ~112 MB (1080p). Nunca leerlo entero en memoria (`memory_limit` 256M): partes con streams y `hash_file`.
- **`Authorization` en LiteSpeed/Apache:** sin la regla del `.htaccess`, PHP no ve el Bearer y todo da 401.
- **`.trim()` sobre la salida tabulada de `mysql -N`** se come la última columna vacía de la última fila: separa por líneas y filtra vacías, no recortes.
- **`DATETIME` en PHP:** compara las horas como cadenas `'Y-m-d H:i:s'` en UTC; formatea a ISO como la API Node (`…T…:..:...000Z`).
- **Migraciones:** `schema.sql` solo con `CREATE TABLE IF NOT EXISTS` (se aplica en cada instalación); cualquier cambio de tabla existente va en `Schema::migrate()` y revisa antes si hace falta (idempotente).

## Criterios de aceptación

- [ ] `npm run php:up && npm run php:migrate && npm run test:php` pasa con el mismo número de pruebas que Node.
- [ ] Un token firmado por la API Node funciona en la PHP.
- [ ] Un chunk de 60 MB (como uno de 5 min a 720p) sube en partes de 8 MB y se cierra con el `sha256` correcto, con los límites de producción.
- [ ] Una parte de 9 MB responde 413; una con `offset` equivocado, 409 con `expected_offset`.
