<?php
declare(strict_types=1);

namespace PpCoachTracker;

/** Pregunta de la cancha (pitch_mode), puntos y líneas que marca el entrenador e imágenes de referencia (igual que calibration.js). */
final class Calibration
{
    public const PITCH_MODES = ['lines', 'no_lines', 'off'];
    public const MAX_REFERENCES = 6;
    private const MAX_FRAMES = 20;
    private const MAX_LINES = 20;
    private const MAX_STROKE_POINTS = 64;
    private const MAX_TOTAL_STROKE_POINTS = 3000;
    private const NAME = '/^[a-z0-9_]{1,40}$/';

    /** analysis.pitch_mode de POST /clips → [ok, valor] (null si falta). */
    public static function pitchMode(mixed $analysis): array
    {
        if ($analysis === null) {
            return [true, null];
        }
        if (!is_array($analysis)) {
            return [false, null];
        }
        $m = $analysis['pitch_mode'] ?? null;
        if ($m === null) {
            return [true, null];
        }
        return in_array($m, self::PITCH_MODES, true) ? [true, $m] : [false, null];
    }

    private static function num(mixed $v): bool
    {
        return (is_int($v) || is_float($v)) && is_finite((float) $v);
    }

    private static function inUnit(mixed $pt): bool
    {
        if (!is_array($pt)) {
            return false;
        }
        $x = $pt['x'] ?? null;
        $y = $pt['y'] ?? null;
        return self::num($x) && self::num($y) && $x >= 0 && $x <= 1 && $y >= 0 && $y <= 1;
    }

    /** Un trazo pintado: 2..64 puntos con x, y en 0..1 → ['value' => …] o ['error' => '…']. */
    private static function stroke(mixed $points, string $where): array
    {
        if (!is_array($points) || !array_is_list($points) || count($points) < 2 || count($points) > self::MAX_STROKE_POINTS) {
            return ['error' => "$where.points must have 2.." . self::MAX_STROKE_POINTS . ' points'];
        }
        $out = [];
        foreach ($points as $pt) {
            if (!self::inUnit($pt)) {
                return ['error' => "$where.points must have x and y in 0..1"];
            }
            $out[] = ['x' => $pt['x'], 'y' => $pt['y']];
        }
        return ['value' => $out];
    }

    /**
     * El cuerpo de POST /clips/:id/calibration (igual que calibration.js):
     * cuadros con 0..40 puntos y 0..20 líneas pintadas (con nombre o null),
     * al menos uno de los dos, y 0..6 imágenes de referencia con sus trazos.
     * → ['value' => …] o ['error' => '…'].
     */
    public static function parse(array $b): array
    {
        $p = $b['pitch'] ?? null;
        if (!is_array($p) || !in_array($p['size'] ?? null, [11, 7], true)) {
            return ['error' => 'pitch.size must be 11 or 7'];
        }
        $pitch = ['size' => $p['size']];
        foreach ([['length_m', 20, 130], ['width_m', 15, 100]] as [$key, $lo, $hi]) {
            if (!array_key_exists($key, $p) || $p[$key] === null) {
                continue;
            }
            if (!self::num($p[$key]) || $p[$key] < $lo || $p[$key] > $hi) {
                return ['error' => "pitch.$key must be $lo..$hi"];
            }
            $pitch[$key] = $p[$key];
        }
        $frames = array_key_exists('frames', $b) ? $b['frames'] : [];
        $refs = array_key_exists('references', $b) ? $b['references'] : [];
        if (!is_array($frames) || !array_is_list($frames) || count($frames) > self::MAX_FRAMES) {
            return ['error' => 'frames must have 0..' . self::MAX_FRAMES . ' entries'];
        }
        if (!is_array($refs) || !array_is_list($refs) || count($refs) > self::MAX_REFERENCES) {
            return ['error' => 'references must have 0..' . self::MAX_REFERENCES . ' entries'];
        }
        if (count($frames) + count($refs) < 1) {
            return ['error' => 'frames or references must have an entry'];
        }
        $strokePoints = 0;
        $out = [];
        foreach ($frames as $i => $f) {
            if (!is_array($f)) {
                return ['error' => "frames[$i] is not an object"];
            }
            $o = [];
            if (is_int($f['t_ms'] ?? null) && $f['t_ms'] >= 0) {
                $o['t_ms'] = $f['t_ms'];
            } elseif (is_int($f['frame'] ?? null) && $f['frame'] >= 0) {
                $o['frame'] = $f['frame'];
            } else {
                return ['error' => "frames[$i] needs t_ms or frame"];
            }
            $points = array_key_exists('points', $f) ? $f['points'] : [];
            $lines = array_key_exists('lines', $f) ? $f['lines'] : [];
            if (!is_array($points) || !array_is_list($points) || count($points) > 40) {
                return ['error' => "frames[$i].points must have 0..40 points"];
            }
            if (!is_array($lines) || !array_is_list($lines) || count($lines) > self::MAX_LINES) {
                return ['error' => "frames[$i].lines must have 0.." . self::MAX_LINES . ' lines'];
            }
            if (count($points) + count($lines) < 1) {
                return ['error' => "frames[$i] needs points or lines"];
            }
            $names = [];
            $o['points'] = [];
            foreach ($points as $j => $pt) {
                $name = is_array($pt) ? ($pt['name'] ?? null) : null;
                if (!is_string($name) || !preg_match(self::NAME, $name)) {
                    return ['error' => "frames[$i].points[$j].name is invalid"];
                }
                if (isset($names[$name])) {
                    return ['error' => "frames[$i] repeats $name"];
                }
                $names[$name] = true;
                if (!self::inUnit($pt)) {
                    return ['error' => "frames[$i].points[$j] must have x and y in 0..1"];
                }
                $o['points'][] = ['name' => $name, 'x' => $pt['x'], 'y' => $pt['y']];
            }
            if ($lines) {
                $o['lines'] = [];
                foreach ($lines as $j => $ln) {
                    $where = "frames[$i].lines[$j]";
                    if (!is_array($ln)) {
                        return ['error' => "$where is not an object"];
                    }
                    $name = $ln['name'] ?? null;
                    if ($name !== null && (!is_string($name) || !preg_match(self::NAME, $name))) {
                        return ['error' => "$where.name is invalid"];
                    }
                    $st = self::stroke($ln['points'] ?? null, $where);
                    if (isset($st['error'])) {
                        return $st;
                    }
                    $strokePoints += count($st['value']);
                    $o['lines'][] = ['name' => $name, 'points' => $st['value']];
                }
            }
            $out[] = $o;
        }
        $references = [];
        $numbers = [];
        foreach ($refs as $i => $r) {
            $where = "references[$i]";
            if (!is_array($r)) {
                return ['error' => "$where is not an object"];
            }
            $n = $r['n'] ?? null;
            if (!is_int($n) || $n < 1 || $n > self::MAX_REFERENCES || isset($numbers[$n])) {
                return ['error' => "$where.n must be a distinct 1.." . self::MAX_REFERENCES];
            }
            $numbers[$n] = true;
            if (!in_array($r['source'] ?? null, ['photo', 'frame'], true)) {
                return ['error' => "$where.source must be photo or frame"];
            }
            $o = ['n' => $n, 'source' => $r['source']];
            if (($r['t_ms'] ?? null) !== null) {
                if (!is_int($r['t_ms']) || $r['t_ms'] < 0) {
                    return ['error' => "$where.t_ms is invalid"];
                }
                $o['t_ms'] = $r['t_ms'];
            }
            $strokes = array_key_exists('strokes', $r) ? $r['strokes'] : [];
            if (!is_array($strokes) || !array_is_list($strokes) || count($strokes) > self::MAX_LINES) {
                return ['error' => "$where.strokes must have 0.." . self::MAX_LINES . ' strokes'];
            }
            $o['strokes'] = [];
            foreach ($strokes as $j => $st) {
                $res = self::stroke(is_array($st) ? ($st['points'] ?? null) : null, "$where.strokes[$j]");
                if (isset($res['error'])) {
                    return $res;
                }
                $strokePoints += count($res['value']);
                $o['strokes'][] = ['points' => $res['value']];
            }
            $references[] = $o;
        }
        if ($strokePoints > self::MAX_TOTAL_STROKE_POINTS) {
            return ['error' => 'at most ' . self::MAX_TOTAL_STROKE_POINTS . ' stroke points in all'];
        }
        $value = ['pitch' => $pitch, 'frames' => $out];
        if ($references) {
            $value['references'] = $references;
        }
        return ['value' => $value];
    }
}
