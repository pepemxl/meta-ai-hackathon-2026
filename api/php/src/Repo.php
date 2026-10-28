<?php
declare(strict_types=1);

namespace PpCoachTracker;

use PDO;

/** Todas las consultas de la API (db/schema.sql). */
final class Repo
{
    /** Un trabajo que lleva más que esto en running regresa a la cola (el worker murió). */
    public const JOB_STALE_SECONDS = 7200;

    public function __construct(private readonly PDO $db)
    {
    }

    private function one(string $sql, array $params = []): ?array
    {
        $st = $this->db->prepare($sql);
        $st->execute($params);
        $row = $st->fetch();
        return $row === false ? null : $row;
    }

    private function all(string $sql, array $params = []): array
    {
        $st = $this->db->prepare($sql);
        $st->execute($params);
        return $st->fetchAll();
    }

    private function run(string $sql, array $params = []): int
    {
        $st = $this->db->prepare($sql);
        $st->execute($params);
        return $st->rowCount();
    }

    // ── usuarios y login ─────────────────────────────────────────────────────

    public function createUser(string $email, string $name, string $hash): int
    {
        $this->run('INSERT INTO users (email, name, password_hash) VALUES (?, ?, ?)', [strtolower($email), $name, $hash]);
        return (int) $this->db->lastInsertId();
    }

    public function userByEmail(string $email): ?array
    {
        return $this->one('SELECT id, email, name, password_hash FROM users WHERE email = ?', [strtolower($email)]);
    }

    public function setPassword(string $email, string $hash): int
    {
        return $this->run('UPDATE users SET password_hash = ? WHERE email = ?', [$hash, strtolower($email)]);
    }

    public function listUsers(): array
    {
        return $this->all('SELECT id, email, name, created_at FROM users ORDER BY id');
    }

    /** Fallos recientes: [por correo, por IP, el más viejo del correo (Y-m-d H:i:s) o null]. */
    public function loginFailures(string $email, string $ip, int $windowSeconds): array
    {
        $since = gmdate('Y-m-d H:i:s', time() - $windowSeconds);
        $byEmail = $this->one('SELECT COUNT(*) AS n, MIN(at) AS first FROM login_failures WHERE email = ? AND at > ?', [$email, $since]);
        $byIp = $this->one('SELECT COUNT(*) AS n, MIN(at) AS first FROM login_failures WHERE ip = ? AND at > ?', [$ip, $since]);
        return [(int) $byEmail['n'], (int) $byIp['n'], $byEmail['first'], $byIp['first']];
    }

    public function addLoginFailure(string $email, string $ip): void
    {
        $this->run('INSERT INTO login_failures (email, ip, at) VALUES (?, ?, UTC_TIMESTAMP())', [$email, $ip]);
        // Limpieza oportunista: lo de más de 1 día ya no cuenta.
        if (random_int(1, 20) === 1) {
            $this->run('DELETE FROM login_failures WHERE at < ?', [gmdate('Y-m-d H:i:s', time() - 86400)]);
        }
    }

    // ── partidos y clips ─────────────────────────────────────────────────────

    public function upsertMatch(string $coachId, array $m): int
    {
        $this->run(
            'INSERT INTO matches (coach_id, client_match_id, home, away, home_color, away_color, match_date, venue)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), home = VALUES(home), away = VALUES(away),
               home_color = VALUES(home_color), away_color = VALUES(away_color), match_date = VALUES(match_date), venue = VALUES(venue)',
            [$coachId, $m['client_match_id'], $m['home'], $m['away'], $m['home_color'], $m['away_color'], $m['date'], $m['venue']],
        );
        return (int) $this->db->lastInsertId();
    }

    public function match(int $id): ?array
    {
        return $this->one('SELECT * FROM matches WHERE id = ?', [$id]);
    }

    public function clipByClientId(string $coachId, string $clientClipId): ?array
    {
        return $this->clipRow($this->one('SELECT * FROM clips WHERE coach_id = ? AND client_clip_id = ?', [$coachId, $clientClipId]));
    }

    public function clip(int $id): ?array
    {
        return $this->clipRow($this->one('SELECT * FROM clips WHERE id = ?', [$id]));
    }

    private function clipRow(?array $row): ?array
    {
        if ($row === null) {
            return null;
        }
        foreach (['id', 'match_id', 'duration_ms', 'chunk_count', 'chunk_seconds', 'overlap_ms'] as $k) {
            $row[$k] = (int) $row[$k];
        }
        return $row;
    }

    public function createClip(array $c): int
    {
        $this->run(
            "INSERT INTO clips (coach_id, client_clip_id, match_id, title, duration_ms, chunk_count, chunk_seconds, overlap_ms, status)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'registered')",
            [$c['coach_id'], $c['client_clip_id'], $c['match_id'], $c['title'], $c['duration_ms'], $c['chunk_count'], $c['chunk_seconds'], $c['overlap_ms']],
        );
        return (int) $this->db->lastInsertId();
    }

    /** @param array<string, scalar> $fields columnas fijas del código, nunca del cliente */
    public function updateClip(int $id, array $fields): void
    {
        if (!$fields) {
            return;
        }
        $sets = implode(', ', array_map(fn($k) => "`$k` = ?", array_keys($fields)));
        $this->run("UPDATE clips SET $sets WHERE id = ?", [...array_values($fields), $id]);
    }

    public function deleteChunks(int $clipId): void
    {
        $this->run('DELETE FROM clip_chunks WHERE clip_id = ?', [$clipId]);
    }

    public function putChunk(int $clipId, int $n, string $sha, int $size): void
    {
        $this->run(
            'INSERT INTO clip_chunks (clip_id, number, sha256, size_bytes) VALUES (?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE sha256 = VALUES(sha256), size_bytes = VALUES(size_bytes), received_at = CURRENT_TIMESTAMP',
            [$clipId, $n, $sha, $size],
        );
    }

    /** @return list<array{number: int, sha256: string, size_bytes: int}> */
    public function chunks(int $clipId): array
    {
        return array_map(
            fn($r) => ['number' => (int) $r['number'], 'sha256' => $r['sha256'], 'size_bytes' => (int) $r['size_bytes']],
            $this->all('SELECT number, sha256, size_bytes FROM clip_chunks WHERE clip_id = ? ORDER BY number', [$clipId]),
        );
    }

    // ── consentimiento ───────────────────────────────────────────────────────

    /** INSERT IGNORE: el mismo evento (acción, versión, hora al segundo) queda una vez. */
    public function addConsentEvent(string $coachId, array $ev, string $ip): int
    {
        $this->run(
            'INSERT IGNORE INTO consent_events (coach_id, action, version, at, received_at, ip, app_version)
             VALUES (?, ?, ?, ?, UTC_TIMESTAMP(), ?, ?)',
            [$coachId, $ev['action'], $ev['version'], $ev['at'], $ip, $ev['app_version']],
        );
        $row = $this->one(
            'SELECT id FROM consent_events WHERE coach_id = ? AND action = ? AND version = ? AND at = ?',
            [$coachId, $ev['action'], $ev['version'], $ev['at']],
        );
        return (int) $row['id'];
    }

    public function consentEvents(string $coachId): array
    {
        return $this->all(
            'SELECT id, action, version, at, received_at, app_version FROM consent_events WHERE coach_id = ? ORDER BY at, id',
            [$coachId],
        );
    }

    public function linkClipConsent(int $clipId, int $eventId): void
    {
        $this->run('REPLACE INTO clip_consent_events (clip_id, event_id) VALUES (?, ?)', [$clipId, $eventId]);
    }

    // ── trabajos ─────────────────────────────────────────────────────────────

    public function openJob(int $clipId): ?array
    {
        return $this->one("SELECT * FROM jobs WHERE clip_id = ? AND status IN ('queued', 'running') ORDER BY id LIMIT 1", [$clipId]);
    }

    public function lastJob(int $clipId): ?array
    {
        return $this->one('SELECT * FROM jobs WHERE clip_id = ? ORDER BY id DESC LIMIT 1', [$clipId]);
    }

    public function createJob(int $clipId): int
    {
        $this->run("INSERT INTO jobs (clip_id, type, status) VALUES (?, 'segment', 'queued')", [$clipId]);
        return (int) $this->db->lastInsertId();
    }

    public function job(int $id): ?array
    {
        return $this->one('SELECT * FROM jobs WHERE id = ?', [$id]);
    }

    /**
     * Toma el trabajo en cola más viejo: running, y su clip a segmenting.
     * UPDATE … LAST_INSERT_ID(id) es atómico y funciona en MariaDB sin SKIP LOCKED.
     */
    public function claimJob(): ?array
    {
        $stale = gmdate('Y-m-d H:i:s', time() - self::JOB_STALE_SECONDS);
        $this->run("UPDATE jobs SET status = 'queued', started_at = NULL WHERE status = 'running' AND started_at < ?", [$stale]);
        $this->db->query('SELECT LAST_INSERT_ID(0)');
        $n = $this->run("UPDATE jobs SET status = 'running', started_at = UTC_TIMESTAMP(), id = LAST_INSERT_ID(id)
                         WHERE status = 'queued' ORDER BY id LIMIT 1");
        $id = (int) $this->db->query('SELECT LAST_INSERT_ID()')->fetchColumn();
        if ($n === 0 || $id === 0) {
            return null;
        }
        $job = $this->job($id);
        $this->run("UPDATE clips SET status = 'segmenting' WHERE id = ?", [(int) $job['clip_id']]);
        return $job;
    }

    public function finishJob(int $id, string $status, ?string $error): void
    {
        $this->run(
            'UPDATE jobs SET status = ?, error = ?, finished_at = UTC_TIMESTAMP() WHERE id = ?',
            [$status, $error === null ? null : mb_substr($error, 0, 500), $id],
        );
    }

    public function saveAnalysis(int $clipId, int $jobId, ?string $segmenter, ?string $analyst, string $result): void
    {
        $this->run(
            'REPLACE INTO clip_analyses (clip_id, job_id, segmenter, analyst, result, created_at) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP())',
            [$clipId, $jobId, $segmenter, $analyst, $result],
        );
    }

    public function analysis(int $clipId): ?array
    {
        return $this->one('SELECT * FROM clip_analyses WHERE clip_id = ?', [$clipId]);
    }
}
