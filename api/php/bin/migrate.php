<?php
declare(strict_types=1);

// php bin/migrate.php: aplica db/schema.sql y las migraciones (se puede repetir).

require __DIR__ . '/_cli.php';

use PpCoachTracker\Config;
use PpCoachTracker\Db;
use PpCoachTracker\Schema;

try {
    foreach (Schema::apply(Db::pdo(), Config::schemaFile()) as $line) {
        echo "ok  $line" . PHP_EOL;
    }
    echo 'Esquema al día.' . PHP_EOL;
} catch (\Throwable $e) {
    cli_fail('No se pudo migrar: ' . $e->getMessage());
}
