<?php
declare(strict_types=1);

namespace PpCoachTracker\Http;

/** Respuestas: JSON, vacía o un archivo leído por bloques. */
final class Response
{
    /** @param array<string, string> $headers */
    private function __construct(
        public readonly int $status,
        private readonly ?string $body,
        private readonly array $headers,
        private readonly ?string $file = null,
    ) {
    }

    /** @param array<string, string> $headers */
    public static function json(mixed $data, int $status = 200, array $headers = []): self
    {
        $body = json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION);
        return new self($status, $body === false ? '{}' : $body, ['Content-Type' => 'application/json; charset=utf-8'] + $headers);
    }

    public static function noContent(): self
    {
        return new self(204, null, []);
    }

    public static function file(string $path, string $type): self
    {
        return new self(200, null, ['Content-Type' => $type, 'Content-Length' => (string) filesize($path)], $path);
    }

    public function send(): void
    {
        http_response_code($this->status);
        foreach ($this->headers as $k => $v) {
            header("$k: $v");
        }
        if ($this->file !== null) {
            // Por bloques: un chunk de 5 min no cabe en memory_limit.
            $in = fopen($this->file, 'rb');
            $out = fopen('php://output', 'wb');
            if ($in && $out) {
                stream_copy_to_stream($in, $out);
                fclose($in);
                fclose($out);
            }
            return;
        }
        if ($this->body !== null) {
            echo $this->body;
        }
    }
}
