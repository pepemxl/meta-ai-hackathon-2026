<?php
declare(strict_types=1);

namespace PpCoachTracker\Http;

/**
 * Parser de multipart/form-data para PUT (PHP solo llena $_FILES en POST).
 * Solo para cuerpos chicos (el YAML de un chunk): se leen en memoria.
 */
final class Multipart
{
    /**
     * @return array{0: array<string, string>, 1: array<string, string>} [campos, archivos → ruta temporal]
     */
    public static function parse(string $input, string $boundary, int $maxBytes): array
    {
        $in = fopen($input, 'rb');
        $body = $in ? (string) stream_get_contents($in, $maxBytes + 1) : '';
        if ($in) {
            fclose($in);
        }
        if (strlen($body) > $maxBytes) {
            throw new HttpError(413, 'part too large');
        }
        $fields = [];
        $files = [];
        $parts = explode('--' . $boundary, $body);
        foreach ($parts as $part) {
            if ($part === '' || str_starts_with($part, '--')) {
                continue;
            }
            // Cada parte: \r\n cabeceras \r\n\r\n contenido \r\n
            $part = preg_replace('/^\r\n/', '', $part);
            $sep = strpos((string) $part, "\r\n\r\n");
            if ($sep === false) {
                continue;
            }
            $head = substr((string) $part, 0, $sep);
            $content = substr((string) $part, $sep + 4);
            if (str_ends_with($content, "\r\n")) {
                $content = substr($content, 0, -2);
            }
            if (!preg_match('/name="([^"]*)"/i', $head, $nm)) {
                continue;
            }
            $name = $nm[1];
            if (preg_match('/filename="([^"]*)"/i', $head)) {
                $tmp = tempnam(sys_get_temp_dir(), 'ppct');
                if ($tmp === false) {
                    throw new \RuntimeException('tempnam failed');
                }
                file_put_contents($tmp, $content);
                $files[$name] = $tmp;
            } else {
                $fields[$name] = $content;
            }
        }
        return [$fields, $files];
    }
}
