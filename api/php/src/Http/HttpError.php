<?php
declare(strict_types=1);

namespace PpCoachTracker\Http;

/** Un error con su código HTTP; App lo responde como {error, ...extra}. */
final class HttpError extends \Exception
{
    /** @param array<string, mixed> $extra */
    public function __construct(public readonly int $status, string $error, public readonly array $extra = [])
    {
        parent::__construct($error);
    }

    /** @return array<string, mixed> */
    public function body(): array
    {
        return ['error' => $this->getMessage()] + $this->extra;
    }
}
