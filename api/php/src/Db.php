<?php
declare(strict_types=1);

namespace PpCoachTracker;

use PDO;

/** Conexión PDO única, en UTC y con errores como excepciones. */
final class Db
{
    private static ?PDO $pdo = null;

    public static function pdo(): PDO
    {
        if (self::$pdo === null) {
            $dsn = sprintf(
                'mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4',
                Config::get('DB_HOST', '127.0.0.1'),
                Config::int('DB_PORT', 3306),
                Config::get('DB_NAME', 'ppcoachtracker'),
            );
            self::$pdo = new PDO($dsn, Config::get('DB_USER', 'ppcoachtracker'), Config::get('DB_PASSWORD'), [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_EMULATE_PREPARES => false,
            ]);
            self::$pdo->exec("SET time_zone = '+00:00'");
        }
        return self::$pdo;
    }

    /** true si la base responde; para /health. */
    public static function ping(): bool
    {
        try {
            self::pdo()->query('SELECT 1');
            return true;
        } catch (\Throwable $e) {
            return false;
        }
    }
}
