"""Muse Spark turns the aggregated metrics into findings a coach can act on.

Responses API with strict structured output (json_schema). Only aggregated
metrics go to Meta: no heatmaps, no tracks, no names. If the answer does not
validate, ONE repair call with the error; if that fails too, the job fails
(MetaError). There are no rule-based insights.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from .meta_api import MetaClient, cache_key
from .types import MetaError

SEVERITIES = ["info", "low", "medium", "high"]
MAX_INSIGHTS = 8

SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["summary", "insights"],
    "properties": {
        "summary": {"type": "string"},
        "insights": {
            "type": "array",
            "maxItems": MAX_INSIGHTS,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["title", "description", "t_start_ms", "t_end_ms", "severity", "recommendation"],
                "properties": {
                    "title": {"type": "string"},
                    "description": {"type": "string"},
                    "t_start_ms": {"type": "integer"},
                    "t_end_ms": {"type": "integer"},
                    "severity": {"type": "string", "enum": SEVERITIES},
                    "recommendation": {"type": "string"},
                },
            },
        },
    },
}

SYSTEM = """Eres el analista de video de PP-Coach-Tracker. Recibes métricas agregadas de un clip de fútbol amateur, medidas por SAM 3.1 sobre el video de un celular, y escribes hallazgos breves en español para un entrenador voluntario.

Reglas:
- Di solo lo que los números sostienen. Si un dato no alcanza para concluir algo, no lo concluyas.
- Las posiciones de "teams" son coordenadas de pantalla (fracciones de la imagen): di "en la imagen". Solo si viene "pitch_meters" puedes hablar de metros y km/h, solo con esos números y diciendo de qué parte del video salen; sin "pitch_meters" NUNCA uses metros ni distancias reales.
- Los "tracks" son trayectorias detectadas, no personas: nunca digas "se siguieron N jugadores".
- Si hay avisos (pocos jugadores a la vista, balón casi no detectado), dilos primero, en el resumen y como primer hallazgo.
- Cada hallazgo apunta a un momento del video con t_start_ms y t_end_ms dentro del clip (milisegundos del video original).
- El entrenador decide: recomienda qué revisar o practicar, sin órdenes tácticas absolutas.
- Equipo A es el local y equipo B el visitante; usa sus nombres si vienen.
- Máximo 8 hallazgos. Responde solo con el JSON pedido."""


def validate(obj: object, duration_ms: int) -> str | None:
    """None if [obj] follows SCHEMA (and the times fall in the clip), else the error."""
    if not isinstance(obj, dict):
        return "the answer is not a JSON object"
    extra = set(obj) - {"summary", "insights"}
    if extra:
        return f"unexpected fields: {sorted(extra)}"
    if not isinstance(obj.get("summary"), str) or not obj["summary"].strip():
        return "summary must be a non-empty string"
    ins = obj.get("insights")
    if not isinstance(ins, list) or len(ins) > MAX_INSIGHTS:
        return f"insights must be a list of at most {MAX_INSIGHTS}"
    required = SCHEMA["properties"]["insights"]["items"]["required"]
    for i, it in enumerate(ins):
        if not isinstance(it, dict):
            return f"insights[{i}] is not an object"
        missing = [k for k in required if k not in it]
        if missing:
            return f"insights[{i}] misses {missing}"
        if set(it) - set(required):
            return f"insights[{i}] has unexpected fields {sorted(set(it) - set(required))}"
        for k in ("title", "description", "recommendation"):
            if not isinstance(it[k], str) or not it[k].strip():
                return f"insights[{i}].{k} must be a non-empty string"
        for k in ("t_start_ms", "t_end_ms"):
            if isinstance(it[k], bool) or not isinstance(it[k], int):
                return f"insights[{i}].{k} must be an integer"
        if it["severity"] not in SEVERITIES:
            return f"insights[{i}].severity must be one of {SEVERITIES}"
        if it["t_start_ms"] > it["t_end_ms"]:
            return f"insights[{i}]: t_start_ms > t_end_ms"
        if duration_ms and (it["t_start_ms"] < 0 or it["t_end_ms"] > duration_ms + 1000):
            return f"insights[{i}]: times outside the clip (0..{duration_ms} ms)"
    return None


def warnings_of(metrics: dict) -> list[str]:
    out = []
    if metrics.get("players_per_frame", 0) < 6:
        out.append(f"Pocos jugadores a la vista: {metrics.get('players_per_frame', 0)} por cuadro en promedio.")
    if metrics.get("ball", {}).get("seen_ratio", 0) < 0.2:
        out.append(f"Balón casi no detectado: visto en {round(100 * metrics.get('ball', {}).get('seen_ratio', 0))} % de los cuadros.")
    return out


def facts_for(metrics: dict, match: dict, start_ms: int, pitch: dict | None = None) -> dict:
    """Only aggregated numbers (no heatmaps, no tracks, no names of players)."""
    teams = {}
    for t, label in (("A", match.get("home") or "Local"), ("B", match.get("away") or "Visitante")):
        m = metrics["teams"][t]
        teams[t] = {
            "name": label,
            "color": match.get("home_color" if t == "A" else "away_color"),
            "players_per_frame": m["players_per_frame"],
            "max_players": m["max_players"],
            "tracks": m["tracks"],
            "shape_in_image": m["shape"],
            "possession": m["possession"],
            "longest_possession_ms": m["longest_possession_ms"],
            "pressers_per_frame": m["pressers_per_frame"],
        }
    return {
        "clip": {"start_ms": start_ms, "end_ms": start_ms + metrics["duration_ms"], "duration_ms": metrics["duration_ms"], "frames": metrics["frames"]},
        "coordinates": "screen",
        "teams": teams,
        "unassigned_per_frame": metrics["unassigned_per_frame"],
        "players_per_frame": metrics["players_per_frame"],
        "ball_seen_ratio": metrics["ball"]["seen_ratio"],
        "possession_changes": metrics["possession_changes"],
        "possession_changes_per_minute": metrics["possession_changes_per_minute"],
        "possession_change_times_ms": [e["t_ms"] for e in metrics["events"][:40]],
        "warnings": warnings_of(metrics),
        **({"pitch_meters": pitch_facts(pitch)} if pitch else {}),
    }


def pitch_facts(pitch: dict) -> dict:
    """Aggregates in meters per team (no heatmaps, no tracks)."""
    teams = {}
    for t, m in pitch["teams"].items():
        shape = m.get("shape") or {}
        teams[t] = {
            "meters_per_minute_per_player": m.get("meters_per_minute"),
            "sprint_speed_p95_kmh": round(m["speed_p95"] * 3.6, 1) if m.get("speed_p95") is not None else None,
            "team_length_m": shape.get("length"),
            "team_width_m": shape.get("width"),
            "player_seconds": m.get("player_seconds"),
        }
    return {
        "seconds_measured": pitch["seconds"],
        "share_of_video": pitch["frames_with_homography"],
        "pitch_size_m": [pitch["length_m"], pitch["width_m"]],
        "approximate_pitch_size": pitch["approximate"],
        "teams": teams,
    }


class MuseSpark:
    def __init__(self, client: MetaClient, model: str, raw_dir: Path | None = None, is_meta_url: bool = True):
        self.client = client
        self.model = model
        self.raw_dir = raw_dir
        self.simulator = not is_meta_url

    def _ask(self, messages: list[dict], name: str) -> str:
        body = {
            "model": self.model,
            "input": messages,
            "text": {"format": {"type": "json_schema", "name": "coach_insights", "schema": SCHEMA, "strict": True}},
        }
        digest = hashlib.sha256(json.dumps(body, sort_keys=True).encode("utf-8")).hexdigest()
        resp = self.client.respond(body, cache_key(self.client.base_url, digest, self.model), {"model": self.model, "prompt_sha256": digest}, stream=False)
        self.simulator |= resp.simulator
        if self.raw_dir is not None:
            self.raw_dir.mkdir(parents=True, exist_ok=True)
            (self.raw_dir / name).write_text(resp.raw, encoding="utf-8")
        return resp.text

    def insights(self, metrics: dict, match: dict, start_ms: int, pitch: dict | None = None) -> dict:
        facts = facts_for(metrics, match, start_ms, pitch)
        end_ms = facts["clip"]["end_ms"]
        messages = [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": json.dumps(facts, ensure_ascii=False)},
        ]
        text = self._ask(messages, "muse_spark.json")
        parsed, error = _parse(text, end_ms)
        if error:
            messages = messages + [
                {"role": "assistant", "content": text},
                {"role": "user", "content": f"La respuesta no es válida: {error}. Devuelve solo el JSON corregido que cumpla el esquema."},
            ]
            text = self._ask(messages, "muse_spark_repair.json")
            parsed, error = _parse(text, end_ms)
            if error:
                raise MetaError("muse_spark", f"invalid structured output after one repair: {error}")
        return parsed

    @property
    def analyst(self) -> str:
        return "muse_spark_simulator" if self.simulator else "muse_spark"


def _parse(text: str, end_ms: int) -> tuple[dict | None, str | None]:
    try:
        obj = json.loads(text)
    except ValueError as e:
        return None, f"not valid JSON ({e.msg})"
    err = validate(obj, end_ms)
    return (obj, None) if err is None else (None, err)
