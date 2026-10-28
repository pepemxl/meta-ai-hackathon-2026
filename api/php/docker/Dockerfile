# Imita el hosting compartido de Cloud Provider: PHP 8.3 y un servidor que
# respeta .htaccess (allá LiteSpeed; aquí Apache, que entiende las mismas
# reglas), con la API en una subcarpeta (/pp-coach-tracker) para probar ese caso.
# docker-compose monta php/ en /app y php/public en /var/www/html/pp-coach-tracker.
FROM php:8.3-apache

# zip: lo usa el instalador de producción (el hosting lo trae activo).
RUN apt-get update && apt-get install -y --no-install-recommends libzip-dev \
    && docker-php-ext-install pdo_mysql zip && a2enmod rewrite headers \
    && rm -rf /var/lib/apt/lists/*

# Sin TLS en local: el .htaccess no redirige a HTTPS con PP_COACH_TRACKER_ALLOW_HTTP.
# Una segunda copia en /pp-coach-tracker-strict con REQUIRE_META y un tope de
# chunk chico: así las pruebas de contrato cubren esos casos sin otro contenedor.
RUN printf '<Directory /var/www/html>\n    AllowOverride All\n</Directory>\n' > /etc/apache2/conf-available/ppcoachtracker.conf \
    && printf 'SetEnvIf Request_URI "." PP_COACH_TRACKER_ALLOW_HTTP=1\n' >> /etc/apache2/conf-available/ppcoachtracker.conf \
    && printf 'SetEnvIf Request_URI "^/pp-coach-tracker-strict/" PP_COACH_TRACKER_REQUIRE_META=1 MAX_CHUNK_BYTES=12582912\n' >> /etc/apache2/conf-available/ppcoachtracker.conf \
    && a2enconf ppcoachtracker

# Apache con mod_php no lee .user.ini: los mismos límites que public/.user.ini.
RUN printf 'expose_php = Off\nupload_max_filesize = 32M\npost_max_size = 40M\nmax_execution_time = 120\nmemory_limit = 256M\ndisplay_errors = Off\nlog_errors = On\n' \
    > /usr/local/etc/php/conf.d/ppcoachtracker.ini

WORKDIR /app
