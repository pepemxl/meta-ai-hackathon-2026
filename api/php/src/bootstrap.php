<?php
declare(strict_types=1);

// Arranque común de la web (public/index.php) y de bin/*.php: autoload PSR-4
// del prefijo PpCoachTracker\ y la configuración del .env de la raíz de la app.

spl_autoload_register(static function (string $class): void {
    $prefix = 'PpCoachTracker\\';
    if (strncmp($class, $prefix, strlen($prefix)) !== 0) {
        return;
    }
    $file = __DIR__ . '/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
    if (is_file($file)) {
        require $file;
    }
});

\PpCoachTracker\Config::load(dirname(__DIR__));
