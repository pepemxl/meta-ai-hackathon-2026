<?php
declare(strict_types=1);

namespace PpCoachTracker;

/** Consentimiento: versión vigente, fechas del teléfono y estado actual. */
final class Consent
{
    /** El texto vigente (docs/legal/). Una versión nueva vuelve a preguntar a todos. */
    public const VERSION = '2026-10-27';
    public const VERSION_PATTERN = '/^[\w.-]{1,20}$/';
    private const MAX_FUTURE = 86400;

    /** Fecha del teléfono → 'Y-m-d H:i:s' en UTC (al segundo), o null si es inválida o >1 día adelante. */
    public static function parseAt(mixed $value, ?int $now = null): ?string
    {
        if (!is_string($value) || $value === '' || strlen($value) > 40) {
            return null;
        }
        // Formatos ISO 8601 como los manda la app (con Z o con desfase).
        if (!preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/', $value)) {
            return null;
        }
        try {
            $d = new \DateTimeImmutable($value);
        } catch (\Exception $e) {
            return null;
        }
        if ($d->getTimestamp() - ($now ?? time()) > self::MAX_FUTURE) {
            return null;
        }
        return gmdate('Y-m-d H:i:s', $d->getTimestamp());
    }

    /** El bloque consent de POST /clips → [version, at, app_version] o null (→ 428). */
    public static function parseClipConsent(mixed $consent, ?int $now = null): ?array
    {
        if (!is_array($consent) || ($consent['version'] ?? null) !== self::VERSION) {
            return null;
        }
        $at = self::parseAt($consent['accepted_at'] ?? null, $now);
        if ($at === null) {
            return null;
        }
        return ['version' => self::VERSION, 'at' => $at, 'app_version' => self::appVersion($consent['app_version'] ?? null)];
    }

    /** El cuerpo de POST /consent → evento, o ['error' => ...]. */
    public static function parseEvent(array $b, ?int $now = null): array
    {
        $action = $b['action'] ?? null;
        if ($action !== 'accept' && $action !== 'withdraw') {
            return ['error' => 'invalid action'];
        }
        $version = $b['version'] ?? null;
        if (!is_string($version) || !preg_match(self::VERSION_PATTERN, $version)) {
            return ['error' => 'invalid version'];
        }
        if ($action === 'accept' && $version !== self::VERSION) {
            return ['error' => 'not the current version'];
        }
        $at = self::parseAt($b['at'] ?? null, $now);
        if ($at === null) {
            return ['error' => 'invalid date'];
        }
        return ['action' => $action, 'version' => $version, 'at' => $at, 'app_version' => self::appVersion($b['app_version'] ?? null)];
    }

    /** none | accepted | withdrawn | outdated, con los eventos ordenados por hora. */
    public static function current(array $events): string
    {
        if (!$events) {
            return 'none';
        }
        $last = $events[count($events) - 1];
        if ($last['action'] === 'withdraw') {
            return 'withdrawn';
        }
        return $last['version'] === self::VERSION ? 'accepted' : 'outdated';
    }

    /** 'Y-m-d H:i:s' (UTC) → ISO como la API Node: …T…:..:...000Z */
    public static function iso(string $sql): string
    {
        return str_replace(' ', 'T', substr($sql, 0, 19)) . '.000Z';
    }

    private static function appVersion(mixed $v): ?string
    {
        return is_string($v) && strlen($v) <= 40 ? $v : null;
    }
}
