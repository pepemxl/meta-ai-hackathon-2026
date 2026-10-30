<?php
declare(strict_types=1);

namespace PpCoachTracker;

use PpCoachTracker\Http\HttpError;
use PpCoachTracker\Http\Request;
use PpCoachTracker\Http\Response;
use PpCoachTracker\Http\Router;

/**
 * La API de pp-coach-tracker en PHP, con el mismo contrato que la de Node
 * (api/README.md). Las diferencias obligadas están en api/php/README.md.
 */
final class App
{
    public const VERSION = '0.1.0';
    private const LOGIN_WINDOW = 900;
    private const LOGIN_MAX_EMAIL = 10;
    private const LOGIN_MAX_IP = 30;
    /** Mientras se analiza no se aceptan subidas. */
    private const UPLOAD_BLOCKED = ['queued', 'segmenting'];
    /** Los puntos de la cancha se pueden cambiar hasta que el worker toma el clip. */
    private const CALIBRATION_OPEN = ['registered', 'uploading', 'queued'];
    // Una imagen de la cancha que tomó el entrenador (o un cuadro), comprimida por la app.
    private const MAX_REFERENCE_BYTES = 2 * 1024 * 1024;

    private Router $router;
    private ?Repo $repo = null;
    private Storage $storage;
    private string $jwtSecret;
    private string $workerSecret;
    /** @var list<array{0: string, 1: string}> */
    private array $tokens;
    private int $maxPartBytes;
    private int $maxChunkBytes;
    private bool $requireMeta;

    public function __construct()
    {
        $this->jwtSecret = Config::get('JWT_SECRET');
        $this->workerSecret = Config::get('WORKER_CALLBACK_SECRET');
        $this->tokens = Auth::parseTokens(Config::get('API_TOKENS'));
        $this->maxPartBytes = Config::int('MAX_PART_BYTES', 8 * 1024 * 1024);
        $this->maxChunkBytes = Config::int('MAX_CHUNK_BYTES', 160 * 1024 * 1024);
        $this->requireMeta = Config::get('PP_COACH_TRACKER_REQUIRE_META') === '1';
        $this->storage = new Storage(Config::storageDir());
        $this->router = new Router();
        $this->routes();
    }

    public function run(): void
    {
        $req = Request::fromGlobals();
        try {
            if (strlen($this->jwtSecret) < 32) {
                // Igual que Node, que no arranca: sin secreto no hay sesiones seguras.
                throw new HttpError(500, 'server misconfigured');
            }
            $res = $this->router->dispatch($req);
        } catch (HttpError $e) {
            $res = Response::json($e->body(), $e->status, $e->status === 429 && isset($e->extra['retry_after']) ? ['Retry-After' => (string) $e->extra['retry_after']] : []);
        } catch (\Throwable $e) {
            error_log('pp-coach-tracker: ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());
            $res = Response::json(['error' => 'internal error'], 500);
        } finally {
            $req->cleanup();
        }
        $res->send();
    }

    private function repo(): Repo
    {
        return $this->repo ??= new Repo(Db::pdo());
    }

    // ── autenticación ────────────────────────────────────────────────────────

    private function coachOf(Request $req): ?string
    {
        $t = $req->bearer();
        if ($t === null || strlen($this->jwtSecret) < 32) {
            return null;
        }
        return Auth::verifyToken($this->jwtSecret, $t) ?? Auth::matchStaticToken($this->tokens, $t);
    }

    private function coach(Request $req): string
    {
        $c = $this->coachOf($req);
        if ($c === null) {
            throw new HttpError(401, 'invalid or missing token');
        }
        return $c;
    }

    private function worker(Request $req): void
    {
        $t = $req->bearer();
        if ($this->workerSecret === '' || $t === null || !hash_equals($this->workerSecret, $t)) {
            throw new HttpError(401, 'invalid or missing token');
        }
    }

    /** El clip de :id si es del entrenador; si no, 404. */
    private function ownClip(Request $req, string $coach): array
    {
        $id = ctype_digit($req->params['id'] ?? '') ? (int) $req->params['id'] : 0;
        $clip = $id > 0 ? $this->repo()->clip($id) : null;
        if ($clip === null || $clip['coach_id'] !== $coach) {
            throw new HttpError(404, 'clip not found');
        }
        return $clip;
    }

    private function chunkNumber(Request $req, array $clip): int
    {
        $raw = $req->params['n'] ?? '';
        $n = preg_match('/^\d{1,4}$/', $raw) ? (int) $raw : 0;
        if ($n < 1 || $n > $clip['chunk_count']) {
            throw new HttpError(400, 'invalid chunk number', ['chunk_count' => $clip['chunk_count']]);
        }
        return $n;
    }

    private function clipState(array $clip): array
    {
        return [
            'clip_id' => $clip['id'],
            'status' => $clip['status'],
            'chunk_count' => $clip['chunk_count'],
            'chunk_seconds' => $clip['chunk_seconds'],
            'overlap_ms' => $clip['overlap_ms'],
            'pitch_mode' => $clip['pitch_mode'] ?? null,
            'camera' => $clip['camera'] ?? null,
            'received' => array_map(fn($c) => ['number' => $c['number'], 'sha256' => $c['sha256']], $this->repo()->chunks($clip['id'])),
            'partial' => $this->storage->partials($clip['id']),
        ];
    }

    // ── rutas ────────────────────────────────────────────────────────────────

    private function routes(): void
    {
        $r = $this->router;
        $r->add('GET', '/health', fn(Request $q) => Response::json([
            'ok' => true,
            'version' => self::VERSION,
            'db' => Db::ping(),
            'authenticated' => $this->coachOf($q) !== null,
            'consent_version' => Consent::VERSION,
        ]));
        $r->add('POST', '/auth/login', fn(Request $q) => $this->login($q));
        $r->add('POST', '/clips', fn(Request $q) => $this->registerClip($q));
        $r->add('POST', '/clips/:id/calibration', fn(Request $q) => $this->postCalibration($q));
        $r->add('PUT', '/clips/:id/references/:n', fn(Request $q) => $this->putReference($q));
        $r->add('PUT', '/clips/:id/chunks/:n/video', fn(Request $q) => $this->putPart($q));
        $r->add('PUT', '/clips/:id/chunks/:n', fn(Request $q) => $this->finishChunk($q));
        $r->add('POST', '/clips/:id/complete', fn(Request $q) => $this->complete($q));
        $r->add('GET', '/clips/:id', fn(Request $q) => $this->getClip($q));
        $r->add('GET', '/clips/:id/analysis', fn(Request $q) => $this->getAnalysis($q));
        $r->add('POST', '/consent', fn(Request $q) => $this->postConsent($q));
        $r->add('GET', '/consent', fn(Request $q) => $this->getConsent($q));
        $r->add('GET', '/internal/jobs/next', fn(Request $q) => $this->nextJob($q));
        $r->add('GET', '/internal/clips/:id/files/:name', fn(Request $q) => $this->clipFile($q));
        $r->add('POST', '/internal/jobs/:id/result', fn(Request $q) => $this->jobResult($q));
    }

    private function login(Request $req): Response
    {
        $b = $req->json();
        $email = is_string($b['email'] ?? null) ? strtolower(trim($b['email'])) : '';
        $password = is_string($b['password'] ?? null) ? $b['password'] : '';
        if ($email === '' || $password === '' || strlen($email) > 190) {
            throw new HttpError(400, 'email and password required');
        }
        [$byEmail, $byIp, $firstEmail, $firstIp] = $this->repo()->loginFailures($email, $req->ip, self::LOGIN_WINDOW);
        if ($byEmail >= self::LOGIN_MAX_EMAIL || $byIp >= self::LOGIN_MAX_IP) {
            $first = $byEmail >= self::LOGIN_MAX_EMAIL ? $firstEmail : $firstIp;
            $retry = max(1, strtotime($first . ' UTC') + self::LOGIN_WINDOW - time());
            throw new HttpError(429, 'too many attempts', ['retry_after' => $retry]);
        }
        $user = $this->repo()->userByEmail($email);
        if ($user === null || !Auth::verifyPassword($password, (string) $user['password_hash'])) {
            $this->repo()->addLoginFailure($email, $req->ip);
            throw new HttpError(401, 'invalid credentials');
        }
        return Response::json([
            'token' => Auth::signToken($this->jwtSecret, (int) $user['id'], $user['email']),
            'email' => $user['email'],
            'name' => $user['name'],
        ]);
    }

    private function registerClip(Request $req): Response
    {
        $coach = $this->coach($req);
        $b = $req->json();
        $m = is_array($b['match'] ?? null) ? $b['match'] : [];
        $bad = [];
        $text = function (mixed $v, string $key, int $min = 1) use (&$bad): void {
            if (!is_string($v) || mb_strlen(trim($v)) < $min || mb_strlen($v) > 160) {
                $bad[] = $key;
            }
        };
        $int = function (mixed $v, string $key, int $lo, int $hi) use (&$bad): void {
            if (!is_int($v) || $v < $lo || $v > $hi) {
                $bad[] = $key;
            }
        };
        $text($b['client_clip_id'] ?? null, 'client_clip_id');
        $text($b['title'] ?? null, 'title');
        $int($b['duration_ms'] ?? null, 'duration_ms', 1, 6 * 3600 * 1000);
        $int($b['chunk_count'] ?? null, 'chunk_count', 1, 1000);
        $int($b['chunk_seconds'] ?? null, 'chunk_seconds', 60, 300);
        $int($b['overlap_ms'] ?? null, 'overlap_ms', 2000, 5000);
        $text($m['client_match_id'] ?? null, 'match.client_match_id');
        $text($m['home'] ?? null, 'match.home');
        $text($m['away'] ?? null, 'match.away');
        $text($m['home_color'] ?? null, 'match.home_color');
        $text($m['away_color'] ?? null, 'match.away_color');
        $text($m['date'] ?? null, 'match.date');
        $text($m['venue'] ?? '', 'match.venue', 0);
        [$pitchOk, $pitchMode] = Calibration::pitchMode($b['analysis'] ?? null);
        if (!$pitchOk) {
            $bad[] = 'analysis.pitch_mode';
        }
        [$cameraOk, $camera] = Calibration::camera($b['analysis'] ?? null);
        if (!$cameraOk) {
            $bad[] = 'analysis.camera';
        }
        if ($bad) {
            throw new HttpError(400, 'invalid fields', ['fields' => $bad]);
        }

        $consent = Consent::parseClipConsent($b['consent'] ?? null);
        if ($consent === null) {
            throw new HttpError(428, 'consent required', ['consent_version' => Consent::VERSION]);
        }
        $lastWithdraw = null;
        foreach ($this->repo()->consentEvents($coach) as $e) {
            if ($e['action'] === 'withdraw') {
                $lastWithdraw = $e['at'];
            }
        }
        if ($lastWithdraw !== null && $consent['at'] <= $lastWithdraw) {
            throw new HttpError(428, 'consent withdrawn', ['consent_version' => Consent::VERSION]);
        }
        $eventId = $this->repo()->addConsentEvent($coach, ['action' => 'accept'] + $consent, $req->ip);

        $m['venue'] ??= '';
        $matchId = $this->repo()->upsertMatch($coach, $m);
        $clip = $this->repo()->clipByClientId($coach, $b['client_clip_id']);
        if ($clip === null) {
            $id = $this->repo()->createClip([
                'coach_id' => $coach, 'client_clip_id' => $b['client_clip_id'], 'match_id' => $matchId, 'title' => $b['title'],
                'duration_ms' => $b['duration_ms'], 'chunk_count' => $b['chunk_count'], 'chunk_seconds' => $b['chunk_seconds'], 'overlap_ms' => $b['overlap_ms'],
                'pitch_mode' => $pitchMode, 'camera' => $camera,
            ]);
        } else {
            $id = $clip['id'];
            $fields = ['title' => $b['title'], 'duration_ms' => $b['duration_ms'], 'match_id' => $matchId];
            // La respuesta queda fija cuando el worker toma el clip.
            if (!in_array($clip['status'], self::UPLOAD_BLOCKED, true)) {
                $fields['pitch_mode'] = $pitchMode;
                $fields['camera'] = $camera;
            }
            $changed = $clip['chunk_count'] !== $b['chunk_count'] || $clip['chunk_seconds'] !== $b['chunk_seconds'] || $clip['overlap_ms'] !== $b['overlap_ms'];
            if ($changed && !in_array($clip['status'], self::UPLOAD_BLOCKED, true)) {
                // La app regeneró los chunks con otro perfil: los anteriores ya no sirven.
                $this->repo()->deleteChunks($id);
                $this->storage->wipeClip($id);
                $fields += ['chunk_count' => $b['chunk_count'], 'chunk_seconds' => $b['chunk_seconds'], 'overlap_ms' => $b['overlap_ms'], 'status' => 'registered'];
            }
            $this->repo()->updateClip($id, $fields);
        }
        $this->repo()->linkClipConsent($id, $eventId);
        return Response::json($this->clipState($this->repo()->clip($id)));
    }

    private function postCalibration(Request $req): Response
    {
        $coach = $this->coach($req);
        $clip = $this->ownClip($req, $coach);
        if (!in_array($clip['status'], self::CALIBRATION_OPEN, true)) {
            throw new HttpError(409, 'clip already taken by the worker', ['status' => $clip['status']]);
        }
        $cal = Calibration::parse($req->json(256 * 1024));
        if (isset($cal['error'])) {
            throw new HttpError(400, 'invalid calibration', ['detail' => $cal['error']]);
        }
        $this->repo()->updateClip($clip['id'], ['calibration' => json_encode($cal['value'], JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)]);
        return Response::json([
            'clip_id' => $clip['id'],
            'frames' => count($cal['value']['frames']),
            'points' => array_sum(array_map(fn($f) => count($f['points']), $cal['value']['frames'])),
            'lines' => array_sum(array_map(fn($f) => count($f['lines'] ?? []), $cal['value']['frames'])),
            'references' => count($cal['value']['references'] ?? []),
        ]);
    }

    /** Una imagen de referencia de la cancha (JPEG que comprime la app), n = 1..6. */
    private function putReference(Request $req): Response
    {
        $coach = $this->coach($req);
        $clip = $this->ownClip($req, $coach);
        $nRaw = $req->params['n'] ?? '';
        if (!ctype_digit($nRaw) || (int) $nRaw < 1 || (int) $nRaw > Calibration::MAX_REFERENCES) {
            throw new HttpError(404, 'reference not found');
        }
        $n = (int) $nRaw;
        if (!in_array($clip['status'], self::CALIBRATION_OPEN, true)) {
            throw new HttpError(409, 'clip already taken by the worker', ['status' => $clip['status']]);
        }
        $declared = $req->header('content-length');
        if ($declared !== '' && (int) $declared > self::MAX_REFERENCE_BYTES) {
            throw new HttpError(413, 'reference too large', ['max_reference_bytes' => self::MAX_REFERENCE_BYTES]);
        }
        $in = $req->inputStream();
        $body = $in ? (string) stream_get_contents($in, self::MAX_REFERENCE_BYTES + 1) : '';
        if ($in) {
            fclose($in);
        }
        if (strlen($body) > self::MAX_REFERENCE_BYTES) {
            throw new HttpError(413, 'reference too large', ['max_reference_bytes' => self::MAX_REFERENCE_BYTES]);
        }
        if (strlen($body) < 4 || substr($body, 0, 3) !== "\xFF\xD8\xFF") {
            throw new HttpError(400, 'reference must be a JPEG');
        }
        $dir = $this->storage->ensureClipDir($clip['id']);
        $tmp = (string) tempnam($dir, 'ref');
        file_put_contents($tmp, $body);
        chmod($tmp, 0644);
        rename($tmp, $dir . '/' . Storage::referenceName($n));
        return Response::json(['clip_id' => $clip['id'], 'n' => $n, 'bytes' => strlen($body)]);
    }

    private function putPart(Request $req): Response
    {
        $coach = $this->coach($req);
        $clip = $this->ownClip($req, $coach);
        $n = $this->chunkNumber($req, $clip);
        if (in_array($clip['status'], self::UPLOAD_BLOCKED, true)) {
            throw new HttpError(409, 'clip is being analyzed', ['status' => $clip['status']]);
        }
        $offsetRaw = $req->query['offset'] ?? '';
        if (!is_string($offsetRaw) || !preg_match('/^\d{1,12}$/', $offsetRaw)) {
            throw new HttpError(400, 'invalid offset');
        }
        $offset = (int) $offsetRaw;
        $declared = $req->header('content-length');
        if ($declared !== '' && (int) $declared > $this->maxPartBytes) {
            throw new HttpError(413, 'part too large', ['max_part_bytes' => $this->maxPartBytes]);
        }
        $have = $this->storage->partialSize($clip['id'], $n);
        if ($offset !== $have) {
            throw new HttpError(409, 'offset mismatch', ['expected_offset' => $have]);
        }
        if ($declared !== '' && $have + (int) $declared > $this->maxChunkBytes) {
            throw new HttpError(413, 'chunk too large', ['max_chunk_bytes' => $this->maxChunkBytes]);
        }

        // Del cuerpo crudo a un temporal por bloques, con tope; luego se agrega al .partial.
        $dir = $this->storage->ensureClipDir($clip['id']);
        $tmp = tempnam($dir, 'part');
        $in = $req->inputStream();
        $out = fopen((string) $tmp, 'wb');
        $copied = $in && $out ? stream_copy_to_stream($in, $out, $this->maxPartBytes + 1) : false;
        if ($in) {
            fclose($in);
        }
        if ($out) {
            fclose($out);
        }
        try {
            if ($copied === false) {
                throw new \RuntimeException('cannot read body');
            }
            if ($copied === 0) {
                throw new HttpError(400, 'empty part');
            }
            if ($copied > $this->maxPartBytes) {
                throw new HttpError(413, 'part too large', ['max_part_bytes' => $this->maxPartBytes]);
            }
            if ($have + $copied > $this->maxChunkBytes) {
                throw new HttpError(413, 'chunk too large', ['max_chunk_bytes' => $this->maxChunkBytes]);
            }
            $partial = $dir . '/' . Storage::partialName($n);
            $src = fopen((string) $tmp, 'rb');
            $dst = fopen($partial, 'ab');
            if (!$src || !$dst || !flock($dst, LOCK_EX)) {
                throw new \RuntimeException('cannot open partial');
            }
            // Revisa otra vez con el candado: dos reintentos simultáneos no se intercalan.
            clearstatcache(true, $partial);
            if ((int) filesize($partial) !== $have) {
                flock($dst, LOCK_UN);
                fclose($dst);
                fclose($src);
                throw new HttpError(409, 'offset mismatch', ['expected_offset' => (int) filesize($partial)]);
            }
            stream_copy_to_stream($src, $dst);
            fflush($dst);
            flock($dst, LOCK_UN);
            fclose($dst);
            fclose($src);
        } finally {
            @unlink((string) $tmp);
        }
        if (in_array($clip['status'], ['registered', 'failed', 'ready'], true)) {
            $this->repo()->updateClip($clip['id'], ['status' => 'uploading']);
        }
        return Response::json(['number' => $n, 'received_bytes' => $have + $copied]);
    }

    private function finishChunk(Request $req): Response
    {
        $coach = $this->coach($req);
        $clip = $this->ownClip($req, $coach);
        $n = $this->chunkNumber($req, $clip);
        if (in_array($clip['status'], self::UPLOAD_BLOCKED, true)) {
            throw new HttpError(409, 'clip is being analyzed', ['status' => $clip['status']]);
        }
        $yaml = $req->formText('yaml');
        $expected = strtolower($req->fields['sha256'] ?? '');
        if ($yaml === null || $yaml === '') {
            throw new HttpError(400, 'yaml required');
        }
        if (!preg_match('/^[0-9a-f]{64}$/', $expected)) {
            throw new HttpError(400, 'invalid sha256');
        }
        $dir = $this->storage->clipDir($clip['id']);
        $partial = $dir . '/' . Storage::partialName($n);
        if (!is_file($partial)) {
            throw new HttpError(409, 'no partial upload');
        }
        // hash_file lee por bloques: un chunk de 5 min no cabe en memory_limit.
        $got = (string) hash_file('sha256', $partial);
        if ($got !== $expected || Storage::declaredSha($yaml) !== $got) {
            @unlink($partial);
            throw new HttpError(422, 'sha256 mismatch', ['expected' => $expected, 'got' => $got]);
        }
        clearstatcache(true, $partial);
        $size = (int) filesize($partial);
        rename($partial, $dir . '/' . Storage::mp4Name($n));
        file_put_contents($dir . '/' . Storage::yamlName($n), $yaml);
        $this->repo()->putChunk($clip['id'], $n, $got, $size);
        return Response::json(['number' => $n, 'sha256' => $got, 'size_bytes' => $size]);
    }

    private function complete(Request $req): Response
    {
        $coach = $this->coach($req);
        $clip = $this->ownClip($req, $coach);
        $index = $req->formText('index');
        if ($index === null || $index === '') {
            throw new HttpError(400, 'index required');
        }
        $received = array_column($this->repo()->chunks($clip['id']), 'number');
        $missing = [];
        for ($i = 1; $i <= $clip['chunk_count']; $i++) {
            if (!in_array($i, $received, true)) {
                $missing[] = $i;
            }
        }
        if ($missing) {
            throw new HttpError(409, 'missing chunks', ['missing' => $missing]);
        }
        file_put_contents($this->storage->ensureClipDir($clip['id']) . '/' . Storage::INDEX, $index);
        $open = $this->repo()->openJob($clip['id']);
        $jobId = $open !== null ? (int) $open['id'] : $this->repo()->createJob($clip['id']);
        if ($open === null) {
            $this->repo()->updateClip($clip['id'], ['status' => 'queued']);
        }
        return Response::json($this->clipState($this->repo()->clip($clip['id'])) + ['job_id' => $jobId]);
    }

    private function getClip(Request $req): Response
    {
        $coach = $this->coach($req);
        $clip = $this->ownClip($req, $coach);
        $job = $this->repo()->lastJob($clip['id']);
        return Response::json($this->clipState($clip) + [
            'title' => $clip['title'],
            'duration_ms' => $clip['duration_ms'],
            'job' => $job === null ? null : ['id' => (int) $job['id'], 'status' => $job['status'], 'error' => $job['error']],
        ]);
    }

    private function getAnalysis(Request $req): Response
    {
        $coach = $this->coach($req);
        $clip = $this->ownClip($req, $coach);
        $a = $this->repo()->analysis($clip['id']);
        if ($a === null) {
            throw new HttpError(404, 'analysis not found', ['status' => $clip['status']]);
        }
        return Response::json([
            'clip_id' => $clip['id'],
            'job_id' => (int) $a['job_id'],
            'segmenter' => $a['segmenter'],
            'analyst' => $a['analyst'],
            'created_at' => Consent::iso($a['created_at']),
            'result' => json_decode($a['result']),
        ]);
    }

    private function postConsent(Request $req): Response
    {
        $coach = $this->coach($req);
        $ev = Consent::parseEvent($req->json());
        if (isset($ev['error'])) {
            throw new HttpError(400, $ev['error'], ['consent_version' => Consent::VERSION]);
        }
        $id = $this->repo()->addConsentEvent($coach, $ev, $req->ip);
        return Response::json(['id' => $id, 'action' => $ev['action'], 'version' => $ev['version'], 'at' => Consent::iso($ev['at'])]);
    }

    private function getConsent(Request $req): Response
    {
        $coach = $this->coach($req);
        $events = $this->repo()->consentEvents($coach);
        return Response::json([
            'consent_version' => Consent::VERSION,
            'current' => Consent::current($events),
            'events' => array_map(fn($e) => [
                'action' => $e['action'],
                'version' => $e['version'],
                'at' => Consent::iso($e['at']),
                'received_at' => Consent::iso($e['received_at']),
                'app_version' => $e['app_version'],
            ], $events),
        ]);
    }

    private function nextJob(Request $req): Response
    {
        $this->worker($req);
        $job = $this->repo()->claimJob();
        if ($job === null) {
            return Response::noContent();
        }
        $clip = $this->repo()->clip((int) $job['clip_id']);
        $match = $this->repo()->match($clip['match_id']);
        $files = [Storage::INDEX];
        for ($i = 1; $i <= $clip['chunk_count']; $i++) {
            $files[] = Storage::mp4Name($i);
            $files[] = Storage::yamlName($i);
        }
        return Response::json([
            'job_id' => (int) $job['id'],
            'type' => $job['type'],
            'clip' => [
                'id' => $clip['id'], 'title' => $clip['title'], 'duration_ms' => $clip['duration_ms'], 'chunk_count' => $clip['chunk_count'],
                'chunk_seconds' => $clip['chunk_seconds'], 'overlap_ms' => $clip['overlap_ms'],
                'dir' => Storage::clipDirName($clip['id']), 'index' => Storage::INDEX, 'files' => $files,
                'references' => $this->storage->references($clip['id']),
            ],
            'options' => ['pitch_mode' => $clip['pitch_mode'] ?? null, 'camera' => $clip['camera'] ?? null],
            'calibration' => empty($clip['calibration']) ? null : json_decode($clip['calibration']),
            'match' => $match === null ? null : [
                'home' => $match['home'], 'away' => $match['away'], 'home_color' => $match['home_color'],
                'away_color' => $match['away_color'], 'date' => $match['match_date'], 'venue' => $match['venue'],
            ],
        ]);
    }

    private function clipFile(Request $req): Response
    {
        $this->worker($req);
        $name = $req->params['name'] ?? '';
        $id = $req->params['id'] ?? '';
        if (!preg_match(Storage::FILE_PATTERN, $name) || !ctype_digit($id)) {
            throw new HttpError(404, 'file not found');
        }
        $file = $this->storage->clipDir((int) $id) . '/' . $name;
        if (!is_file($file)) {
            throw new HttpError(404, 'file not found');
        }
        $type = str_ends_with($name, '.mp4') ? 'video/mp4' : (str_ends_with($name, '.jpg') ? 'image/jpeg' : 'text/yaml; charset=utf-8');
        return Response::file($file, $type);
    }

    private function jobResult(Request $req): Response
    {
        $this->worker($req);
        if ($req->isMultipart()) {
            $req->parseForm();
            $body = $req->fields;
        } else {
            $body = $req->json();
        }
        $job = ctype_digit($req->params['id'] ?? '') ? $this->repo()->job((int) $req->params['id']) : null;
        if ($job === null) {
            throw new HttpError(404, 'job not found');
        }
        if ($job['status'] !== 'running') {
            throw new HttpError(409, 'job not running', ['status' => $job['status']]);
        }
        $status = $body['status'] ?? null;
        if ($status !== 'done' && $status !== 'failed') {
            throw new HttpError(400, 'invalid status');
        }
        $error = is_string($body['error'] ?? null) ? mb_substr($body['error'], 0, 500) : null;
        $jobId = (int) $job['id'];
        $clipId = (int) $job['clip_id'];

        if ($status === 'failed') {
            $this->repo()->finishJob($jobId, 'failed', $error ?? 'unknown error');
            $this->repo()->updateClip($clipId, ['status' => 'failed']);
            return Response::json(['job_id' => $jobId, 'status' => 'failed']);
        }

        $text = null;
        if (isset($req->files['analysis'])) {
            $text = (string) file_get_contents($req->files['analysis']);
        } elseif (array_key_exists('analysis', $body)) {
            $text = is_string($body['analysis']) ? $body['analysis'] : json_encode($body['analysis'], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION);
        }
        if ($text !== null) {
            $result = json_decode((string) $text);
            if (!($result instanceof \stdClass)) {
                throw new HttpError(400, 'analysis must be a JSON object');
            }
            $backends = isset($result->backends) && $result->backends instanceof \stdClass ? $result->backends : new \stdClass();
            $segmenter = isset($backends->segmenter) && is_string($backends->segmenter) ? $backends->segmenter : null;
            $analyst = isset($backends->analyst) && is_string($backends->analyst) ? $backends->analyst : null;
            if ($this->requireMeta && $segmenter !== 'meta_sam') {
                $this->repo()->finishJob($jobId, 'failed', "simulator result rejected (segmenter $segmenter)");
                $this->repo()->updateClip($clipId, ['status' => 'failed']);
                throw new HttpError(422, 'simulator result rejected', ['segmenter' => $segmenter]);
            }
            $this->repo()->saveAnalysis($clipId, $jobId, $segmenter, $analyst, (string) $text);
        }
        $this->repo()->finishJob($jobId, 'done', null);
        $this->repo()->updateClip($clipId, ['status' => 'ready']);
        return Response::json(['job_id' => $jobId, 'status' => 'done']);
    }
}
