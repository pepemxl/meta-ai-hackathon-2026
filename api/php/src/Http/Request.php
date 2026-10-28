<?php
declare(strict_types=1);

namespace PpCoachTracker\Http;

/** La petición: método, ruta sin el prefijo de la carpeta, cabeceras y cuerpo. */
final class Request
{
    public const JSON_LIMIT = 65536;
    /** Tope de un multipart que llega por PUT (yaml del chunk): se lee en memoria. */
    public const PUT_MULTIPART_LIMIT = 8 * 1024 * 1024;

    /** @var array<string, string> */
    public array $params = [];
    /** @var array<string, string> campos de un multipart */
    public array $fields = [];
    /** @var array<string, string> archivos de un multipart: nombre del campo → ruta temporal */
    public array $files = [];
    private bool $formParsed = false;
    /** @var list<string> temporales propios que se borran al final */
    private array $temps = [];

    /**
     * @param array<string, string> $headers en minúsculas
     * @param array<string, mixed> $query
     */
    public function __construct(
        public readonly string $method,
        public readonly string $path,
        public readonly array $headers,
        public readonly array $query,
        public readonly string $ip,
        private readonly string $input = 'php://input',
    ) {
    }

    public static function fromGlobals(): self
    {
        $uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
        // Sin el prefijo de la carpeta: sirve igual en /pp-coach-tracker y en la raíz.
        $base = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
        if ($base !== '' && str_starts_with($uri, $base)) {
            $uri = substr($uri, strlen($base));
        }
        $path = '/' . ltrim($uri, '/');
        $headers = [];
        foreach ($_SERVER as $k => $v) {
            if (str_starts_with($k, 'HTTP_')) {
                $headers[strtolower(str_replace('_', '-', substr($k, 5)))] = (string) $v;
            }
        }
        if (isset($_SERVER['CONTENT_TYPE'])) {
            $headers['content-type'] = (string) $_SERVER['CONTENT_TYPE'];
        }
        // LiteSpeed/Apache: el .htaccess pasa Authorization como variable.
        $auth = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? null;
        if ($auth !== null) {
            $headers['authorization'] = (string) $auth;
        }
        return new self($_SERVER['REQUEST_METHOD'] ?? 'GET', $path, $headers, $_GET, (string) ($_SERVER['REMOTE_ADDR'] ?? ''));
    }

    public function header(string $name): string
    {
        return $this->headers[strtolower($name)] ?? '';
    }

    public function bearer(): ?string
    {
        return preg_match('/^Bearer (.+)$/', $this->header('authorization'), $m) ? $m[1] : null;
    }

    public function isMultipart(): bool
    {
        return str_starts_with(strtolower($this->header('content-type')), 'multipart/form-data');
    }

    /** El cuerpo JSON (máx. 64 KB, o $limit) como arreglo; [] si viene vacío. */
    public function json(int $limit = self::JSON_LIMIT): array
    {
        $in = fopen($this->input, 'rb');
        $body = $in ? (string) stream_get_contents($in, $limit + 1) : '';
        if ($in) {
            fclose($in);
        }
        if (strlen($body) > $limit) {
            throw new HttpError(413, 'body too large');
        }
        if (trim($body) === '') {
            return [];
        }
        $data = json_decode($body, true);
        if (!is_array($data)) {
            throw new HttpError(400, 'invalid JSON');
        }
        return $data;
    }

    /** Llena $fields y $files. PHP solo llena $_FILES en POST: en PUT se parsea a mano. */
    public function parseForm(): void
    {
        if ($this->formParsed) {
            return;
        }
        $this->formParsed = true;
        if (!$this->isMultipart()) {
            return;
        }
        if ($this->method === 'POST') {
            foreach ($_POST as $k => $v) {
                if (is_string($v)) {
                    $this->fields[$k] = $v;
                }
            }
            foreach ($_FILES as $k => $f) {
                if (is_array($f) && ($f['error'] ?? 1) === UPLOAD_ERR_OK) {
                    $this->files[$k] = (string) $f['tmp_name'];
                } elseif (is_array($f) && in_array($f['error'] ?? 0, [UPLOAD_ERR_INI_SIZE, UPLOAD_ERR_FORM_SIZE], true)) {
                    throw new HttpError(413, 'part too large');
                }
            }
            return;
        }
        if (!preg_match('/boundary="?([^";]+)"?/i', $this->header('content-type'), $m)) {
            throw new HttpError(400, 'invalid multipart');
        }
        [$this->fields, $this->files] = Multipart::parse($this->input, $m[1], self::PUT_MULTIPART_LIMIT);
        $this->temps = array_values($this->files);
    }

    /** Un valor del multipart enviado como archivo o como campo de texto. */
    public function formText(string $name): ?string
    {
        $this->parseForm();
        if (isset($this->files[$name])) {
            return (string) file_get_contents($this->files[$name]);
        }
        return $this->fields[$name] ?? null;
    }

    /** El cuerpo crudo como stream (partes del video). */
    public function inputStream()
    {
        return fopen($this->input, 'rb');
    }

    public function cleanup(): void
    {
        foreach ($this->temps as $f) {
            @unlink($f);
        }
    }
}
