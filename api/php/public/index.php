<?php
declare(strict_types=1);

// Punto de entrada web de la API de pp-coach-tracker.
// La app vive fuera de la carpeta web: root.php (lo escribe el instalador)
// devuelve su ruta; en local la da la variable PP_COACH_TRACKER_ROOT.

ini_set('display_errors', '0');

$root = is_file(__DIR__ . '/root.php') ? require __DIR__ . '/root.php' : getenv('PP_COACH_TRACKER_ROOT');
if (!is_string($root) || $root === '' || !is_file($root . '/src/bootstrap.php')) {
    http_response_code(500);
    header('Content-Type: application/json');
    echo '{"error":"server misconfigured"}';
    exit;
}

require $root . '/src/bootstrap.php';

(new \PpCoachTracker\App())->run();
