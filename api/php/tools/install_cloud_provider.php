<?php
declare(strict_types=1);

// Instalador de la API en el hosting compartido (etapa 9). Se sube como
// <WEB_DIR>/_deploy/install.php junto al zip de package.sh y lo corre un cron:
//   /usr/bin/php <WEB_DIR>/_deploy/install.php
// Extrae el zip, reemplaza src/bin/db de la app, copia el .env si venía,
// aplica el esquema, crea la cuenta de user.env si no existía, copia public/ a
// la carpeta web y borra _deploy (zip y secretos). Nunca imprime secretos.

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

$deploy = __DIR__;

function out(string $msg): void
{
    echo $msg . PHP_EOL;
}

function rrmdir(string $path): void
{
    if (is_link($path) || is_file($path)) {
        unlink($path);
        return;
    }
    if (!is_dir($path)) {
        return;
    }
    foreach (scandir($path) ?: [] as $f) {
        if ($f !== '.' && $f !== '..') {
            rrmdir("$path/$f");
        }
    }
    rmdir($path);
}

function rcopy(string $from, string $to): void
{
    if (is_dir($from)) {
        if (!is_dir($to)) {
            mkdir($to, 0755, true);
        }
        foreach (scandir($from) ?: [] as $f) {
            if ($f !== '.' && $f !== '..') {
                rcopy("$from/$f", "$to/$f");
            }
        }
        return;
    }
    copy($from, $to);
}

/** user.env: EMAIL, NAME y PASSWORD, una por línea. */
function read_env(string $file): array
{
    $vars = [];
    foreach (file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [] as $line) {
        if (str_contains($line, '=') && $line[0] !== '#') {
            [$k, $v] = explode('=', $line, 2);
            $vars[trim($k)] = trim($v);
        }
    }
    return $vars;
}

// Un solo instalador a la vez: el cron corre cada minuto.
$lock = fopen(sys_get_temp_dir() . '/ppcoachtracker_install.lock', 'c');
if ($lock === false || !flock($lock, LOCK_EX | LOCK_NB)) {
    out('Otro instalador está corriendo.');
    exit(0);
}

$zips = glob("$deploy/ppcoachtracker_api-*.zip") ?: [];
if (count($zips) !== 1) {
    out('Se esperaba un zip ppcoachtracker_api-*.zip en _deploy; hay ' . count($zips) . '.');
    exit(1);
}
$zipFile = $zips[0];
$x = "$deploy/x";
rrmdir($x);

try {
    $zip = new ZipArchive();
    if ($zip->open($zipFile) !== true || !$zip->extractTo($x)) {
        throw new RuntimeException('no se pudo extraer ' . basename($zipFile));
    }
    $zip->close();

    $webDir = trim((string) @file_get_contents("$x/WEB_DIR"));
    if ($webDir === '' || realpath($webDir) !== realpath(dirname($deploy))) {
        throw new RuntimeException("WEB_DIR ($webDir) no es la carpeta de _deploy");
    }
    $root = require "$x/public/root.php";
    if (!is_string($root) || !str_starts_with($root, '/') || str_starts_with($root, $webDir)) {
        throw new RuntimeException('root.php debe apuntar fuera de la carpeta web');
    }

    // 1. Código de la app.
    if (!is_dir($root)) {
        mkdir($root, 0750, true);
    }
    foreach (['src', 'bin', 'db'] as $d) {
        rrmdir("$root/$d.old");
        if (is_dir("$root/$d")) {
            rename("$root/$d", "$root/$d.old");
        }
        rename("$x/ppcoachtracker_api/$d", "$root/$d");
        rrmdir("$root/$d.old");
    }
    copy("$x/ppcoachtracker_api/VERSION", "$root/VERSION");
    out('Código en ' . $root . ': ' . str_replace("\n", ' ', trim((string) file_get_contents("$root/VERSION"))));

    if (is_file("$x/ppcoachtracker_api/.env")) {
        copy("$x/ppcoachtracker_api/.env", "$root/.env");
        out('.env copiado.');
    }
    if (is_file("$root/.env")) {
        chmod("$root/.env", 0600);
    } else {
        throw new RuntimeException('falta el .env de la app');
    }
    if (!is_dir("$root/storage")) {
        mkdir("$root/storage", 0700, true);
        out('storage/ creado.');
    }

    // 2. Esquema y cuenta.
    require "$root/src/bootstrap.php";
    foreach (\PpCoachTracker\Schema::apply(\PpCoachTracker\Db::pdo(), \PpCoachTracker\Config::schemaFile()) as $line) {
        out("ok  $line");
    }
    if (is_file("$x/user.env")) {
        $u = read_env("$x/user.env");
        $email = strtolower($u['EMAIL'] ?? '');
        $repo = new \PpCoachTracker\Repo(\PpCoachTracker\Db::pdo());
        if (!filter_var($email, FILTER_VALIDATE_EMAIL) || ($u['NAME'] ?? '') === '' || strlen($u['PASSWORD'] ?? '') < 10) {
            out('user.env incompleto: no se creó la cuenta.');
        } elseif ($repo->userByEmail($email) !== null) {
            out('La cuenta ya existía.');
        } else {
            $repo->createUser($email, $u['NAME'], \PpCoachTracker\Auth::hashPassword($u['PASSWORD']));
            out('Cuenta creada.');
        }
    }

    // 3. Carpeta web.
    rcopy("$x/public", $webDir);
    if (is_file("$webDir/default.php")) {
        unlink("$webDir/default.php");
        out('default.php del hosting borrado.');
    }
    out("public/ copiado a $webDir");
    $status = 0;
} catch (Throwable $e) {
    out('Error: ' . $e->getMessage());
    $status = 1;
}

// 4. Lo temporal (zip y secretos) se borra siempre.
rrmdir($deploy);
out('_deploy borrado.');
exit($status);
