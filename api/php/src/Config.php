<?php
declare(strict_types=1);

namespace PpCoachTracker;

/**
 * Configuración: el .env de la raíz de la app (fuera de la carpeta web) y las
 * variables de entorno. getenv gana, así Docker y las pruebas mandan.
 */
final class Config
{
    private static string $root = '';
    /** @var array<string, string> */
    private static array $file = [];

    public static function load(string $root): void
    {
        self::$root = $root;
        self::$file = [];
        $env = $root . '/.env';
        if (!is_file($env)) {
            return;
        }
        foreach (file($env, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [] as $line) {
            $line = trim($line);
            if ($line === '' || $line[0] === '#' || !str_contains($line, '=')) {
                continue;
            }
            [$key, $value] = explode('=', $line, 2);
            $value = trim($value);
            if (strlen($value) >= 2 && ($value[0] === '"' || $value[0] === "'") && $value[-1] === $value[0]) {
                $value = substr($value, 1, -1);
            }
            self::$file[trim($key)] = $value;
        }
    }

    public static function root(): string
    {
        return self::$root;
    }

    public static function get(string $key, string $default = ''): string
    {
        $env = getenv($key);
        if ($env !== false && $env !== '') {
            return $env;
        }
        return self::$file[$key] ?? $default;
    }

    public static function int(string $key, int $default): int
    {
        $v = self::get($key);
        return $v === '' ? $default : (int) $v;
    }

    public static function storageDir(): string
    {
        $dir = self::get('STORAGE_DIR', 'storage');
        return str_starts_with($dir, '/') || preg_match('/^[A-Za-z]:[\\\\\\/]/', $dir) ? $dir : self::$root . '/' . $dir;
    }

    /** schema.sql: dentro de la app (paquete de producción) o en api/db (repo y Docker). */
    public static function schemaFile(): string
    {
        foreach ([self::$root . '/db/schema.sql', dirname(self::$root) . '/db/schema.sql'] as $f) {
            if (is_file($f)) {
                return $f;
            }
        }
        throw new \RuntimeException('schema.sql not found');
    }
}
