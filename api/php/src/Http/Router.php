<?php
declare(strict_types=1);

namespace PpCoachTracker\Http;

/** Rutas con parámetros ":nombre"; 404 si no hay ninguna. */
final class Router
{
    /** @var list<array{string, string, callable}> */
    private array $routes = [];

    public function add(string $method, string $pattern, callable $handler): void
    {
        $regex = '#^' . preg_replace('#:([a-z]+)#', '(?P<$1>[^/]+)', $pattern) . '$#';
        $this->routes[] = [$method, $regex, $handler];
    }

    public function dispatch(Request $req): Response
    {
        foreach ($this->routes as [$method, $regex, $handler]) {
            if ($method === $req->method && preg_match($regex, $req->path, $m)) {
                $req->params = array_filter($m, 'is_string', ARRAY_FILTER_USE_KEY);
                return $handler($req);
            }
        }
        throw new HttpError(404, 'not found');
    }
}
