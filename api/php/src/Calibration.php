<?php
declare(strict_types=1);

namespace PpCoachTracker;

/** Pregunta de la cancha (pitch_mode) y puntos que marca el entrenador (igual que calibration.js). */
final class Calibration
{
    public const PITCH_MODES = ['lines', 'no_lines', 'off'];
    private const MAX_FRAMES = 20;

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

    /** El cuerpo de POST /clips/:id/calibration → ['value' => …] o ['error' => '…']. */
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
        $frames = $b['frames'] ?? null;
        if (!is_array($frames) || !array_is_list($frames) || count($frames) < 1 || count($frames) > self::MAX_FRAMES) {
            return ['error' => 'frames must have 1..' . self::MAX_FRAMES . ' entries'];
        }
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
            $points = $f['points'] ?? null;
            if (!is_array($points) || !array_is_list($points) || count($points) < 4 || count($points) > 40) {
                return ['error' => "frames[$i].points must have 4..40 points"];
            }
            $names = [];
            $o['points'] = [];
            foreach ($points as $j => $pt) {
                $name = is_array($pt) ? ($pt['name'] ?? null) : null;
                if (!is_string($name) || !preg_match('/^[a-z0-9_]{1,40}$/', $name)) {
                    return ['error' => "frames[$i].points[$j].name is invalid"];
                }
                if (isset($names[$name])) {
                    return ['error' => "frames[$i] repeats $name"];
                }
                $names[$name] = true;
                $x = $pt['x'] ?? null;
                $y = $pt['y'] ?? null;
                if (!self::num($x) || !self::num($y) || $x < 0 || $x > 1 || $y < 0 || $y > 1) {
                    return ['error' => "frames[$i].points[$j] must have x and y in 0..1"];
                }
                $o['points'][] = ['name' => $name, 'x' => $x, 'y' => $y];
            }
            $out[] = $o;
        }
        return ['value' => ['pitch' => $pitch, 'frames' => $out]];
    }
}
