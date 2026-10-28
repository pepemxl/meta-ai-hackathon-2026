<?php
declare(strict_types=1);

// Arranque común de los comandos de bin/: solo por terminal.

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

require dirname(__DIR__) . '/src/bootstrap.php';

function cli_fail(string $msg, int $code = 1): never
{
    fwrite(STDERR, $msg . PHP_EOL);
    exit($code);
}

/** Pide un valor en la terminal sin mostrarlo (si se puede). */
function cli_secret(string $prompt): string
{
    fwrite(STDOUT, $prompt);
    $tty = stream_isatty(STDIN);
    if ($tty && DIRECTORY_SEPARATOR === '/') {
        shell_exec('stty -echo');
    }
    $value = rtrim((string) fgets(STDIN), "\r\n");
    if ($tty && DIRECTORY_SEPARATOR === '/') {
        shell_exec('stty echo');
        fwrite(STDOUT, PHP_EOL);
    }
    return $value;
}
