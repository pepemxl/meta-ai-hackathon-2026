<?php
declare(strict_types=1);

// Cuentas de entrenador (no hay registro público):
//   php bin/user.php add <correo> "<nombre>"
//   php bin/user.php password <correo>
//   php bin/user.php list
// La contraseña sale de PP_COACH_TRACKER_PASSWORD o se pide en la terminal.

require __DIR__ . '/_cli.php';

use PpCoachTracker\Auth;
use PpCoachTracker\Config;
use PpCoachTracker\Db;
use PpCoachTracker\Repo;

$repo = new Repo(Db::pdo());
$cmd = $argv[1] ?? '';

function ask_password(): string
{
    $p = Config::get('PP_COACH_TRACKER_PASSWORD');
    if ($p === '') {
        $p = cli_secret('Contraseña: ');
    }
    if (strlen($p) < 10) {
        cli_fail('La contraseña debe tener al menos 10 caracteres', 2);
    }
    return $p;
}

switch ($cmd) {
    case 'add':
        $email = strtolower(trim($argv[2] ?? ''));
        $name = trim($argv[3] ?? '');
        if (!filter_var($email, FILTER_VALIDATE_EMAIL) || $name === '') {
            cli_fail('Uso: php bin/user.php add <correo> "<nombre>"', 2);
        }
        if ($repo->userByEmail($email) !== null) {
            cli_fail("Ya existe un usuario con $email");
        }
        $id = $repo->createUser($email, $name, Auth::hashPassword(ask_password()));
        echo "Usuario $email creado (coach_id " . Auth::coachIdOf($id) . ')' . PHP_EOL;
        break;
    case 'password':
        $email = strtolower(trim($argv[2] ?? ''));
        if ($repo->userByEmail($email) === null) {
            cli_fail("No existe el usuario $email");
        }
        $repo->setPassword($email, Auth::hashPassword(ask_password()));
        echo "Contraseña de $email cambiada" . PHP_EOL;
        break;
    case 'list':
        foreach ($repo->listUsers() as $u) {
            echo "u{$u['id']}\t{$u['email']}\t{$u['name']}\t{$u['created_at']}" . PHP_EOL;
        }
        break;
    default:
        cli_fail("Uso: php bin/user.php add <correo> \"<nombre>\" | password <correo> | list", 2);
}
