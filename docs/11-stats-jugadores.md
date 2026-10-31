# Estadísticas por jugador

> **Antecedente.** Este documento viene del diseño y del prototipo previos al hackathon, que tenían un respaldo local (detector YOLOv8n, tracker propio e insights por reglas). La versión que se construye **no tiene respaldo**: solo SAM 3.1 y Muse Spark, y solo dependencias con licencia permisiva. Donde este texto diga otra cosa, mandan [prompts/00-contexto.md](prompts/00-contexto.md) y el prompt de cada etapa.

pp-coach-tracker guarda las estadísticas de cada jugador del plantel en cada partido y las suma entre partidos. Todo vive en el teléfono (`library.json`); nada se manda a la API ni a la Meta API. Implementación en `android_native/app/src/main/java/com/ppcoach/tracker/data/Stats.kt`.

## Plantel

- Cada jugador tiene **dorsal** (1–99, único en el plantel) y **posición** (portero, defensa, medio, delantero), los dos obligatorios.
- El **apodo o iniciales** es opcional. Nunca sale del teléfono ([10-privacidad-jugadores.md](10-privacidad-jugadores.md)).
- El dorsal es la llave con la que el reporte de análisis identifica a cada jugador.

## Qué se mide y quién lo llena

| Stat | Clave | Quién la llena | Notas |
|---|---|---|---|
| Minutos jugados | `minutes` | Análisis | Tiempo con el track visible en cancha. Máx. 130 |
| Goles | `goals` | Coach | Lo que cuenta el árbitro |
| Asistencias | `assists` | Coach | |
| Tiros | `shots` | Análisis | |
| Tiros a gol | `shots_on_target` | Análisis | No puede ser mayor que los tiros (la app avisa) |
| Pases acertados | `passes_completed` | Análisis | |
| Pases fallidos | `passes_failed` | Análisis | |
| Pases con chance de gol | `key_passes` | Análisis | El pase previo a un tiro |
| Regates completados | `dribbles_completed` | Análisis | |
| Entradas (tackles) | `tackles` | Análisis | |
| Intercepciones | `interceptions` | Análisis | |
| Despejes | `clearances` | Análisis | |
| Bloqueos | `blocks` | Análisis | |
| Duelos | `duels` | Análisis | Disputados |
| Faltas | `fouls` | Coach | Decisión del árbitro |
| Tarjetas amarillas | `yellow_cards` | Coach | Máx. 2 |
| Tarjetas rojas | `red_cards` | Coach | Máx. 1 |

**Calculadas, no se guardan:**

- **Precisión de pase** = acertados / (acertados + fallidos).
- **Por 90 minutos** = valor × 90 / minutos. Solo cuenta los partidos que tienen minutos, para que un partido sin reporte (con goles pero sin minutos) no infle el promedio.
- **Partidos jugados** = partidos en los que el jugador tiene al menos un valor distinto de cero.

## Flujo

1. El coach agrega a los jugadores a la hoja del partido (pantalla **Estadísticas** del partido).
2. **Antes del reporte:** las stats del análisis aparecen bloqueadas (🔒). El coach puede capturar goles, asistencias, faltas y tarjetas.
3. **Llega el reporte de análisis.** Hoy se importa como archivo JSON; en v0.2 lo descargará la app de la API cuando el job termine, con una notificación.
   - Se marca el partido como analizado (fecha, fuente) y se desbloquean las stats del análisis.
   - Cada dorsal del reporte se liga al jugador del plantel con ese dorsal. Los dorsales que no existen se agregan al plantel como "medio", sin apodo, y la app avisa para que el coach revise su posición.
   - Las stats del coach que vengan en el reporte (goles, tarjetas…) se ignoran y la app lo avisa.
4. **Después del reporte:** el coach puede agregar o corregir cualquier stat.
   - Un valor corregido se marca como **editado** y muestra el valor del análisis al lado.
   - "Volver al análisis" borra la corrección.
   - Reimportar el reporte actualiza los valores del análisis y **no toca** las correcciones del coach.

## Formato del reporte de análisis

Lo produce el worker al terminar. Ejemplo completo en `android_native/samples/analysis-report.example.json`.

```json
{
  "version": 1,
  "source": "meta_sam",
  "generated_at": "2026-10-30T18:20:00Z",
  "players": [
    {
      "number": 8,
      "stats": {
        "minutes": 55,
        "passes_completed": 31,
        "passes_failed": 6,
        "key_passes": 3,
        "shots": 2,
        "shots_on_target": 1,
        "duels": 11
      }
    }
  ]
}
```

- `players[].number`: dorsal del equipo del coach. No hay nombres en el reporte.
- `stats`: solo claves de la tabla con "Análisis". Las que falten valen 0; las desconocidas se ignoran.
- Los valores fuera de rango se recortan (por ejemplo, minutos a 130).
- `source`: el backend que lo generó (`meta_sam`, `local_cv`, `cache`), como en `segmenter_used` del job. La app lo muestra.

**Dorsales de los tracks (post-tagging):** en el análisis de cada clip, el coach liga cada track de jugador a un dorsal del plantel; un jugador puede tener varios tracks (sale de cuadro, un corte). "Pasar los minutos al partido" suma los segundos de sus tracks en todos los clips del partido y los pone como `minutes` del análisis (vía `video`), sin tocar lo demás del reporte ni las correcciones del coach. Con la cancha calculada, cada track trae además sus metros y su velocidad máxima: la página del jugador y la hoja del partido muestran **desde el video** la distancia recorrida, los metros por minuto y la velocidad máxima de cada jugador (se guardan por clip y no son parte de las 17 stats del reporte). Las otras stats del análisis (pases, tiros, duelos) todavía llegan solo con el reporte importado.

## Qué tan confiables son las stats del análisis

Con una cámara fija y SAM 3.1 (o YOLO en modo local), minutos, duelos y posiciones son razonables. Pases, tiros, regates y entradas dependen de detectar bien el balón, que es la parte débil. Por eso el coach puede corregir todo después del reporte, y por eso la app marca qué vino del análisis y qué editó él.

## Dónde se guarda

En `library.json`, versión 2:

- `players[]`: `{id, number, position, alias, createdAt}`.
- `matchStats[]`: `{matchId, playerId, analysis: {stat: n}, coach: {stat: n}}`.
- `matches[].analysis`: `{receivedAt, via, source, generatedAt}`, ausente si no hay reporte.

Las bibliotecas de la versión 1 abren con el plantel vacío y sin stats.
