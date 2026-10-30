<?php
declare(strict_types=1);

// php bin/retention.php [--ready-hours=24] [--max-days=30] [--dry-run]
//
// Borra el video de los clips como promete el aviso de privacidad ("hasta
// terminar el análisis y como máximo 30 días después de subirlo"): chunks,
// YAML, partes a medias, índice e imágenes de referencia. Se queda el
// análisis. Un clip pierde su video cuando:
//   - su análisis terminó (ready) hace más de --ready-hours, o
//   - se registró hace más de --max-days, en cualquier estado; si seguía en
//     la cola, su trabajo y el clip quedan en failed.
// Un clip que se vuelve a enviar sube su video de nuevo. Lo corre un cron
// diario; --dry-run solo dice qué borraría.

require __DIR__ . '/_cli.php';

use PpCoachTracker\Config;
use PpCoachTracker\Db;
use PpCoachTracker\Repo;
use PpCoachTracker\Storage;

$opts = getopt('', ['ready-hours::', 'max-days::', 'dry-run']);
$readyHours = isset($opts['ready-hours']) ? (int) $opts['ready-hours'] : 24;
$maxDays = isset($opts['max-days']) ? (int) $opts['max-days'] : 30;
$dry = isset($opts['dry-run']);
if ($readyHours < 0 || $maxDays < 1) {
    cli_fail('--ready-hours debe ser 0 o más y --max-days 1 o más.');
}

try {
    $repo = new Repo(Db::pdo());
    $storage = new Storage(Config::storageDir());
    $clips = $repo->retentionCandidates($readyHours, $maxDays);
    $freed = 0;
    foreach ($clips as $c) {
        $id = (int) $c['id'];
        $bytes = $storage->clipBytes($id);
        $why = $c['status'] === 'ready' && !$c['expired'] ? 'analizado' : "más de $maxDays días ({$c['status']})";
        echo ($dry ? 'borraría' : 'borrado') . "  clip $id  " . round($bytes / 1048576, 1) . " MB  $why" . PHP_EOL;
        $freed += $bytes;
        if ($dry) {
            continue;
        }
        $storage->wipeClip($id);
        $repo->deleteChunks($id);
        $repo->markVideoDeleted($id);
        if (in_array($c['status'], ['registered', 'uploading', 'queued', 'running'], true)) {
            // Nunca llegó a analizarse dentro del plazo: el video ya no está.
            $repo->failOpenJobs($id, "retention: video deleted after $maxDays days");
            $repo->updateClip($id, ['status' => 'failed']);
        }
    }
    echo count($clips) . ' clips, ' . round($freed / 1048576, 1) . ' MB' . ($dry ? ' (sin borrar nada)' : ' liberados') . '.' . PHP_EOL;
} catch (\Throwable $e) {
    cli_fail('Retención: ' . $e->getMessage());
}
