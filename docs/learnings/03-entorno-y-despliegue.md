# Entorno y despliegue

## Windows, WSL y Docker

- **Git Bash convierte rutas.** `/worker/...` se vuelve una ruta de Git para Windows. Con `MSYS_NO_PATHCONV=1` antes de `wsl -e docker compose ...` llegan intactas.
- **Los heredocs largos fallan en Git Bash** (comillas, `$`, acentos). Para ediciones con varias líneas, un script de Python en el scratchpad es más confiable. Si el archivo usa CRLF, el script tiene que normalizar los saltos de línea antes de buscar el texto.
- **La consola de Windows no imprime `≥` ni `×`.** `PYTHONIOENCODING=utf-8` antes de correr las herramientas.
- **Python fuera del contenedor no encuentra el paquete del worker.** `-e PYTHONPATH=/worker` en `docker compose run`.
- **El código del worker se monta en el contenedor:** un cambio se prueba sin reconstruir la imagen. El del visor no: hay que `npm run viewer:up` (reconstruye).

## Memoria

- **Guardar todos los cuadros en memoria no escala.** `make_chunks.py` se quedaba sin memoria con 10 minutos; ahora escribe cada chunk mientras lee.
- **Emulador y análisis largo juntos se quedan sin memoria.** El sistema mató el emulador una vez y la comparación otra. Antes de una corrida de 30 min, apagar el emulador si ya no hace falta.
- **Si el sistema mata un proceso por memoria, no relanzarlo solo:** reportarlo y esperar a que se pida.

## Emulador

- **El modo demo de la barra de estado** (hora 10:00, batería llena, sin notificaciones) hace las capturas comparables entre versiones.
- **`uiautomator dump`** da los textos y sus coordenadas; con eso se navega sin adivinar. El botón flotante no aparece en el dump.
- **Tocar por coordenadas es frágil.** Una vez un toque cayó en "English" y cambió el idioma de la app. Después de cada toque, volver a leer la pantalla.
- **La cámara virtual del emulador sirve para videos de prueba propios** (sin video de terceros), aunque no muestra jugadores.

## Cloud Provider compartido, sin SSH

- **Se despliega con la API de archivos (TUS) y un cron de una sola corrida** que ejecuta `install.php` y luego se borra. Detalle en [prompts/09-despliegue.md](../prompts/09-despliegue.md).
- **Proteger primero la carpeta temporal:** subir un `.htaccess` que niega todo y comprobar el 403 antes de subir el instalador y el paquete.
- **El paquete normal no lleva `.env`:** el servidor conserva el suyo. Los secretos nunca pasan por el repo.
- **La migración tiene que ser idempotente** y correr en el instalador. Una columna nueva (`clips.pitch_mode`) se agrega en `Schema::migrate()`, que se puede correr dos veces sin efecto.
- **Subir la versión de la API** (`App::VERSION`) hace que `/health` confirme el despliegue de un vistazo.
- **Crear un cron es "persistencia"** para el sistema de permisos de Claude Code: lo bloquea hasta que se permite la herramienta de Cloud Provider (`.claude/settings.local.json`, no versionado).
- **La API y la base deben ir antes que la app** cuando cambia el contrato: una app 0.3.8 contra una base sin `pitch_mode` falla al registrar.

## Commits

- **Separar commits que tocan los mismos archivos es posible:** guardar las versiones completas, escribir la versión intermedia, hacer commit, restaurar.
- **Probar el commit intermedio por sí solo** en un `git worktree` aparte: así se sabe que cada commit funciona, no solo el último.
- **El patrón de versión:** un commit con la función, otro con la versión y las capturas (`docs/versions/<ver>/`), y el despliegue de la API si cambió el contrato.
