<?php
declare(strict_types=1);

namespace PpCoachTracker;

/**
 * Contraseñas (bcrypt), JWT HS256 compatibles con jsonwebtoken de la API Node
 * (con el mismo JWT_SECRET un token sirve en las dos) y tokens fijos.
 */
final class Auth
{
    /** Un entrenador inicia sesión una vez por temporada, no por partido. */
    public const TOKEN_TTL = 30 * 24 * 3600;

    public static function hashPassword(string $password): string
    {
        return password_hash($password, PASSWORD_DEFAULT);
    }

    public static function verifyPassword(string $password, string $hash): bool
    {
        return password_verify($password, $hash);
    }

    /** El coach_id con que se guarda todo lo que sube un usuario. */
    public static function coachIdOf(int $userId): string
    {
        return 'u' . $userId;
    }

    public static function signToken(string $secret, int $userId, string $email, ?int $now = null): string
    {
        $now ??= time();
        $header = self::b64((string) json_encode(['alg' => 'HS256', 'typ' => 'JWT']));
        $payload = self::b64((string) json_encode([
            'sub' => self::coachIdOf($userId),
            'email' => $email,
            'iat' => $now,
            'exp' => $now + self::TOKEN_TTL,
        ], JSON_UNESCAPED_SLASHES));
        $sig = self::b64(hash_hmac('sha256', "$header.$payload", $secret, true));
        return "$header.$payload.$sig";
    }

    /** El coach_id de un token válido, o null (firma, algoritmo, vencido, mal formado). */
    public static function verifyToken(string $secret, string $token, ?int $now = null): ?string
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return null;
        }
        [$h, $p, $s] = $parts;
        $header = json_decode((string) self::unb64($h), true);
        if (!is_array($header) || ($header['alg'] ?? '') !== 'HS256') {
            return null;
        }
        $expected = self::b64(hash_hmac('sha256', "$h.$p", $secret, true));
        if (!hash_equals($expected, $s)) {
            return null;
        }
        $payload = json_decode((string) self::unb64($p), true);
        if (!is_array($payload) || !is_string($payload['sub'] ?? null)) {
            return null;
        }
        $now ??= time();
        if (isset($payload['exp']) && (!is_numeric($payload['exp']) || $payload['exp'] <= $now)) {
            return null;
        }
        if (isset($payload['nbf']) && is_numeric($payload['nbf']) && $payload['nbf'] > $now) {
            return null;
        }
        return $payload['sub'];
    }

    /**
     * API_TOKENS: "coach_id:token,coach_id:token" → [[coach_id, token], ...].
     * Se ignoran las entradas mal formadas; un token necesita 16 caracteres o más.
     *
     * @return list<array{0: string, 1: string}>
     */
    public static function parseTokens(string $spec): array
    {
        $out = [];
        foreach (explode(',', $spec) as $entry) {
            $entry = trim($entry);
            $i = strpos($entry, ':');
            if ($i === false || $i === 0) {
                continue;
            }
            $coach = trim(substr($entry, 0, $i));
            $token = trim(substr($entry, $i + 1));
            if ($coach !== '' && strlen($token) >= 16) {
                $out[] = [$coach, $token];
            }
        }
        return $out;
    }

    /** Compara contra todos los tokens en tiempo constante; el coach_id o null. */
    public static function matchStaticToken(array $tokens, string $given): ?string
    {
        $found = null;
        foreach ($tokens as [$coach, $token]) {
            if (hash_equals($token, $given)) {
                $found = $coach;
            }
        }
        return $found;
    }

    private static function b64(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }

    private static function unb64(string $data): string|false
    {
        return base64_decode(strtr($data, '-_', '+/'), true);
    }
}
