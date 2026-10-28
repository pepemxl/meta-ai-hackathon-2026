<?php
declare(strict_types=1);

namespace PpCoachTracker;

use PDO;

/**
 * Aplica db/schema.sql (solo CREATE TABLE IF NOT EXISTS, se puede repetir) y
 * luego migrate() para lo que esas sentencias no cubren en tablas que ya existen.
 */
final class Schema
{
    /** @return list<string> lo que hizo, para mostrarlo en bin/migrate.php */
    public static function apply(PDO $db, string $schemaFile): array
    {
        $done = [];
        foreach (self::statements((string) file_get_contents($schemaFile)) as $sql) {
            $db->exec($sql);
            if (preg_match('/CREATE TABLE IF NOT EXISTS\s+`?(\w+)/i', $sql, $m)) {
                $done[] = "tabla {$m[1]}";
            }
        }
        return array_merge($done, self::migrate($db));
    }

    /** Sentencias de un .sql sin comentarios "--". */
    public static function statements(string $sql): array
    {
        $lines = array_filter(
            preg_split('/\R/', $sql) ?: [],
            fn($l) => !preg_match('/^\s*--/', $l),
        );
        $clean = implode("\n", $lines);
        return array_values(array_filter(array_map('trim', explode(';', $clean)), fn($s) => $s !== ''));
    }

    /**
     * Cambios a tablas existentes. Cada uno revisa antes si hace falta (idempotente).
     * Por ahora no hay: el esquema nació con esta versión.
     *
     * @return list<string>
     */
    public static function migrate(PDO $db): array
    {
        return [];
    }

    public static function hasColumn(PDO $db, string $table, string $column): bool
    {
        $st = $db->prepare('SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?');
        $st->execute([$table, $column]);
        return (int) $st->fetchColumn() > 0;
    }
}
