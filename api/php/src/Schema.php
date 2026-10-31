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
     *
     * @return list<string>
     */
    public static function migrate(PDO $db): array
    {
        $done = [];
        // Etapa 13: la pregunta de la cancha y los puntos del entrenador.
        if (!self::hasColumn($db, 'clips', 'pitch_mode')) {
            $db->exec('ALTER TABLE clips ADD COLUMN pitch_mode VARCHAR(16) NULL AFTER overlap_ms');
            $done[] = 'clips.pitch_mode';
        }
        if (!self::hasColumn($db, 'clips', 'calibration')) {
            $db->exec('ALTER TABLE clips ADD COLUMN calibration TEXT NULL AFTER pitch_mode');
            $done[] = 'clips.calibration';
        }
        // La cámara quieta (una homografía para todo el video) o con movimiento y zoom.
        if (!self::hasColumn($db, 'clips', 'camera')) {
            $db->exec('ALTER TABLE clips ADD COLUMN camera VARCHAR(16) NULL AFTER pitch_mode');
            $done[] = 'clips.camera';
        }
        // La zona de juego que marca el entrenador con la cámara quieta.
        if (!self::hasColumn($db, 'clips', 'play_area')) {
            $db->exec('ALTER TABLE clips ADD COLUMN play_area TEXT NULL AFTER camera');
            $done[] = 'clips.play_area';
        }
        // El cron de retención marca cuándo borró el video de un clip.
        if (!self::hasColumn($db, 'clips', 'video_deleted_at')) {
            $db->exec('ALTER TABLE clips ADD COLUMN video_deleted_at DATETIME NULL AFTER calibration');
            $done[] = 'clips.video_deleted_at';
        }
        return $done;
    }

    public static function hasColumn(PDO $db, string $table, string $column): bool
    {
        $st = $db->prepare('SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?');
        $st->execute([$table, $column]);
        return (int) $st->fetchColumn() > 0;
    }
}
