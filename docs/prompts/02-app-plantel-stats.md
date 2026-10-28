# 02 · Plantel y estadísticas por jugador (v0.2.0)

**Lee antes:** [11-stats-jugadores.md](../11-stats-jugadores.md) (completo: qué se mide, quién lo llena, formato del reporte), [10-privacidad-jugadores.md](../10-privacidad-jugadores.md).

```text
Agrega a pp-coach-tracker el plantel del equipo y las estadísticas por jugador
y partido, siguiendo docs/11-stats-jugadores.md.

Modelo (data/Stats.kt, Kotlin puro):
- Player(id, number 1..99 único, position[GK|DF|MF|FW], alias opcional que
  nunca sale del teléfono, createdAt).
- Stat(key, fromAnalysis). Las keys son el contrato con el reporte y con
  library.json; no cambian:
  minutes(A), goals, assists, shots(A), shots_on_target(A),
  passes_completed(A), passes_failed(A), key_passes(A),
  dribbles_completed(A), tackles(A), interceptions(A), clearances(A),
  blocks(A), duels(A), fouls, yellow_cards, red_cards.
  (A) = fromAnalysis: puede venir del análisis de video.
- PlayerMatchStats(matchId, playerId, analysis: Map<Stat,Int>, coach:
  Map<Stat,Int>). valor = coach ?: analysis ?: 0. Lo del entrenador gana;
  borrarlo regresa a lo del análisis.
- AnalysisReport(receivedAt, via "file" (luego "api"), source, generatedAt)
  guardado en el Match.
- StatsCalc: totales entre partidos, precisión de pase, por 90 minutos (solo
  sumando partidos con minutos registrados), totales del equipo por partido.

Reglas: las stats fromAnalysis están bloqueadas en un partido hasta que tenga
reporte; las demás (goles, tarjetas…) se capturan siempre.

Importar reporte (data/AnalysisReportParser.kt) desde un archivo JSON:
{
  "version": 1, "source": "meta_sam", "generated_at": "2026-10-22T18:20:00Z",
  "players": [ { "number": 8, "stats": { "minutes": 55, "passes_completed": 31, … } } ]
}
- Se liga por dorsal. Un dorsal que no está en el plantel se agrega (posición
  MF). Claves de stat desconocidas se ignoran y se informan. Versión distinta
  de 1, JSON inválido o dorsales fuera de rango → error claro, sin cambios.
- Deja un ejemplo en android_native/samples/analysis-report.example.json.

Pantallas (con el estilo de docs/diseno.md; Plantel es un destino de la
barra inferior): Plantel (lista, alta/edición, borrar), Stats del jugador (totales y
por partido), Stats del partido (hoja por jugador, importar reporte, agregar
jugadores del plantel al partido), Editor de stat (jugador × partido, marca lo
editado contra lo del análisis).

Pruebas JVM: parser (válido, inválido, claves desconocidas, dorsales nuevos),
cálculos (por 90 sin inflar, precisión), reglas de bloqueo, códec de la
biblioteca con las tablas nuevas y compatibilidad con un library.json de v0.1.
```

## Criterios de aceptación

- [ ] Pruebas JVM pasan.
- [ ] Emulador: plantel de 3, goles capturados antes del reporte (las stats del video bloqueadas), importar el ejemplo (agrega dorsales nuevos y conserva los goles), corregir pases y ver totales entre dos partidos.
- [ ] Una biblioteca de v0.1 abre sin perder nada.
