<?php
declare(strict_types=1);

namespace PpCoachTracker;

/** Archivos de un clip en disco (docs/12-videos-largos-chunks.md#archivos). */
final class Storage
{
    public const INDEX = 'chunks.yaml';
    public const FILE_PATTERN = '/^(video_part_\d{3}\.(mp4|yaml)|chunks\.yaml|reference_\d\.jpg)$/';

    public function __construct(private readonly string $dir)
    {
    }

    public static function mp4Name(int $n): string
    {
        return sprintf('video_part_%03d.mp4', $n);
    }

    public static function yamlName(int $n): string
    {
        return sprintf('video_part_%03d.yaml', $n);
    }

    public static function referenceName(int $n): string
    {
        return "reference_$n.jpg";
    }

    /** Las imágenes de referencia de un clip en disco, ordenadas. @return list<string> */
    public function references(int $clipId): array
    {
        $out = array_map('basename', glob($this->clipDir($clipId) . '/reference_*.jpg') ?: []);
        $out = array_values(array_filter($out, fn($n) => preg_match('/^reference_\d\.jpg$/', $n) === 1));
        sort($out);
        return $out;
    }

    public static function partialName(int $n): string
    {
        return self::mp4Name($n) . '.partial';
    }

    public static function clipDirName(int $clipId): string
    {
        return sprintf('clip_%06d', $clipId);
    }

    public function clipDir(int $clipId): string
    {
        return $this->dir . '/' . self::clipDirName($clipId);
    }

    public function ensureClipDir(int $clipId): string
    {
        $d = $this->clipDir($clipId);
        if (!is_dir($d) && !mkdir($d, 0770, true) && !is_dir($d)) {
            throw new \RuntimeException('cannot create clip dir');
        }
        return $d;
    }

    public function partialSize(int $clipId, int $n): int
    {
        $f = $this->clipDir($clipId) . '/' . self::partialName($n);
        clearstatcache(true, $f);
        return is_file($f) ? (int) filesize($f) : 0;
    }

    /** @return list<array{number: int, received_bytes: int}> */
    public function partials(int $clipId): array
    {
        $out = [];
        foreach (glob($this->clipDir($clipId) . '/video_part_*.mp4.partial') ?: [] as $f) {
            if (preg_match('/video_part_(\d{3})\.mp4\.partial$/', $f, $m)) {
                $n = (int) $m[1];
                $out[] = ['number' => $n, 'received_bytes' => $this->partialSize($clipId, $n)];
            }
        }
        usort($out, fn($a, $b) => $a['number'] <=> $b['number']);
        return $out;
    }

    /** Borra chunks, YAML, parciales e índice (cambió el perfil del clip). */
    public function wipeClip(int $clipId): void
    {
        $d = $this->clipDir($clipId);
        foreach (glob($d . '/*') ?: [] as $f) {
            @unlink($f);
        }
        @rmdir($d);
    }

    /** El `sha256:` de primer nivel de un YAML de chunk, o null. */
    public static function declaredSha(string $yaml): ?string
    {
        return preg_match('/^sha256:\s*"?([0-9a-fA-F]{64})"?\s*$/m', $yaml, $m) ? strtolower($m[1]) : null;
    }
}
