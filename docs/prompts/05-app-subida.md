# 05 · App: login, sesión cifrada y subida reanudable (v0.3.0–v0.3.1)

**Lee antes:** [arquitectura/04-flujos.md](../arquitectura/04-flujos.md) (flujos 1, 3 y 7), [arquitectura/02-app-android.md](../arquitectura/02-app-android.md), el contrato de [04-api-node.md](04-api-node.md).

```text
Conecta pp-coach-tracker con la API (contrato en api/README.md).

data/ApiClient.kt (OkHttp, sin tipos de Android, probado con MockWebServer):
health(), login(email, password) → Session(token, email, name), register(json)
→ RemoteClip(id, status, chunkCount, received: Map<Int, sha256>,
partial: Map<Int, Long>), putPart(clipId, n, file, offset, length, onBytes)
(PUT …/chunks/n/video?offset=, cuerpo crudo con un RequestBody que lee solo
ese tramo del archivo y cuenta bytes en pasos de 64 KB; partes de 8 MB),
finishChunk(clipId, n, yaml, sha256), complete(clipId, index), clip(clipId).
Un 409 con expected_offset no es error: sigue desde ese offset.
ApiException(code, message) con `retryable` = 5xx, 408, 429 o 422. Lee el
mensaje del campo "error" del JSON. normalizeUrl: agrega https:// si falta y
quita la "/" final; deja http:// para servidores locales. Timeouts: conectar
15 s, escribir 120 s (una parte de 8 MB en red de cancha), leer 60 s.

Sesión: Ajustes → "Sistema de análisis": dirección del servidor, correo y
contraseña → "Sesión iniciada como …", "Probar conexión" (GET /health),
"Cerrar sesión". Errores distintos para contraseña equivocada (401),
demasiados intentos (429) y sesión vencida.
- El JWT se guarda CIFRADO: data/SecretBox.kt con una llave AES-256-GCM del
  Android Keystore; SharedPreferences guarda solo el texto cifrado.

analysis/UploadWorker.kt (CoroutineWorker), encadenado después de ChunkWorker
en la cadena única del clip:
1. Lee la sesión cifrada AL CORRER (no en los datos de entrada: WorkManager
   los guarda en claro en su base de datos).
2. POST /clips con los datos del clip y del partido, incluidos
   chunk_seconds y overlap_ms del perfil → chunks que ya están y bytes de
   los que van a medias.
3. Para cada chunk cuyo sha256 el servidor no tiene: sus partes desde los
   bytes que ya tiene el servidor y después finishChunk, con progreso en
   bytes de todo el clip (setProgress con porcentaje, chunk y total).
4. POST /complete con chunks.yaml. Guarda remoteId y estado en el clip.
- Solo con red (Constraints CONNECTED), backoff exponencial desde 10 s, hasta
  8 reintentos en IOException o ApiException.retryable; se detiene con 401
  ("La sesión venció: inicia sesión de nuevo en Ajustes"), 400, 404, 409.

UI: la barra de dos pasos de "Enviar a análisis" muestra el envío real
("Enviando… 42 %", "Esperando conexión…") y termina en "Enviado ✓"; el clip
queda "Subido". Al abrir un clip enviado, GET /clips/:remoteId y muestra "En
cola para el análisis", "Analizando…", "Análisis listo" o "El análisis falló"
(sin error visible si la consulta falla: conserva el último estado).

network_security_config solo en src/debug: permite HTTP en claro (para
http://10.0.2.2:3000 desde el emulador); el release exige HTTPS.

Pruebas JVM: ApiClient contra MockWebServer (health, login, registro con
chunks recibidos y parciales, putPart con offset y progreso creciente, 409
con expected_offset que reanuda, finishChunk, complete, errores y
retryable, normalizeUrl). La lógica de qué partes faltan, en data/ (Kotlin
puro), con pruebas para chunks de 1 y 5 min.
```

## Trampas conocidas

- **Token en los datos de entrada de WorkManager:** queda en claro en `workdb`. La primera construcción lo hizo así en 0.3.0 y tuvo que migrar en 0.3.1 (cifrar el token viejo y llamar `pruneWork()`). Hacerlo bien desde el principio.
- **Reintentos de un `422`:** sí reintentar (el chunk llegó dañado), pero no los `400`/`409`/`428`.
- **El emulador y la API en Docker/WSL:** `10.0.2.2` llega al `127.0.0.1` de Windows, y de ahí al puerto publicado por Docker en WSL.

## Criterios de aceptación

- [ ] Pruebas JVM pasan.
- [ ] Emulador contra la API local: login (incorrecto y correcto), subir un clip de 1:15 (perfil `dev`), borrar el chunk 2 en el servidor y reenviar: solo sube ese.
- [ ] Perfil `demo`, clip de 6 min: cortar la red a media subida de un chunk de 5 min y volver: retoma desde la última parte, no desde el inicio del chunk.
- [ ] Simular el worker con curl (`/internal/jobs/next` y `result`): la app muestra "En cola" → "Analizando…" → "Análisis listo".
- [ ] `sqlite3` sobre la base de WorkManager: no aparece el token.
