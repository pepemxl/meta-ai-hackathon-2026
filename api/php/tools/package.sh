#!/usr/bin/env bash
# Empaqueta la API PHP para el hosting compartido (etapa 9).
#
#   APP_ROOT=/home/<usuario>/ppcoachtracker_api \
#   WEB_DIR=/home/<usuario>/domains/<dominio>/public_html/ppcoachtracker \
#   [ENV_FILE=ruta/al/.env.del.servidor] [USER_ENV=ruta/a/user.env] \
#   bash api/php/tools/package.sh
#
# Deja en api/php/dist/:
#   ppcoachtracker_api-<fecha>-<commit>.zip  ppcoachtracker_api/ (src, bin, db, VERSION
#                                            y .env si se pasó ENV_FILE), public/
#                                            (con root.php), WEB_DIR y user.env si se pasó
#   install.php                              el instalador (lo corre un cron, solo CLI)
#   deny.htaccess                            "Require all denied" para _deploy/
# Sin ENV_FILE el zip no lleva secretos y el servidor conserva su .env.
set -euo pipefail

: "${APP_ROOT:?falta APP_ROOT}"
: "${WEB_DIR:?falta WEB_DIR}"

HERE="$(cd "$(dirname "$0")" && pwd)"
PHP_DIR="$(dirname "$HERE")"
API_DIR="$(dirname "$PHP_DIR")"
DIST="$PHP_DIR/dist"
COMMIT="$(git -C "$PHP_DIR" rev-parse --short HEAD)"
NAME="ppcoachtracker_api-$(date +%Y%m%d-%H%M)-$COMMIT"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

app="$STAGE/ppcoachtracker_api"
mkdir -p "$app/db" "$STAGE/public"
cp -r "$PHP_DIR/src" "$PHP_DIR/bin" "$app/"
cp "$API_DIR/db/schema.sql" "$app/db/"
sed -n "s/.*const VERSION = '\([^']*\)'.*/\1/p" "$PHP_DIR/src/App.php" | head -1 > "$app/VERSION"
echo "$COMMIT" >> "$app/VERSION"
if [ -n "${ENV_FILE:-}" ]; then
  cp "$ENV_FILE" "$app/.env"
fi

cp -r "$PHP_DIR/public/." "$STAGE/public/"
printf "<?php\nreturn '%s';\n" "$APP_ROOT" > "$STAGE/public/root.php"
printf '%s\n' "$WEB_DIR" > "$STAGE/WEB_DIR"
if [ -n "${USER_ENV:-}" ]; then
  cp "$USER_ENV" "$STAGE/user.env"
fi

mkdir -p "$DIST"
rm -f "$DIST"/ppcoachtracker_api-*.zip
python - "$STAGE" "$DIST/$NAME.zip" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(src):
        for f in files:
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, src).replace(os.sep, "/"))
PY
cp "$HERE/install_cloud_provider.php" "$DIST/install.php"
printf 'Require all denied\n' > "$DIST/deny.htaccess"
echo "$DIST/$NAME.zip"
[ -n "${ENV_FILE:-}" ] && echo "OJO: el zip lleva el .env del servidor (secretos)." || true
