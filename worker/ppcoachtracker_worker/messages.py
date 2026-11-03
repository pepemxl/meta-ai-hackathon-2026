"""The notes and limitations of a result in the coach's language (analysis.language
of POST /clips: es or en; anything else is es, as it always was).

analyze() sets the language for the job with use(); t(key, **values) returns the
text in it. A ContextVar, so the calibration and the insights need no extra
parameter and two jobs in different threads do not mix.
"""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar

LANGUAGES = ("es", "en")
_LANG: ContextVar[str] = ContextVar("language", default="es")


def language_of(value: str | None) -> str:
    """The language of a job: es or en; anything else (or nothing) is es."""
    return value if value in LANGUAGES else "es"


def current() -> str:
    return _LANG.get()


@contextmanager
def use(language: str | None):
    token = _LANG.set(language_of(language))
    try:
        yield
    finally:
        _LANG.reset(token)


def t(key: str, **values) -> str:
    text = TEXTS[key]
    return text.get(current(), text["es"]).format(**values)


TEXTS: dict[str, dict[str, str]] = {
    # insights.warnings_of
    "few_players": {
        "es": "Pocos jugadores a la vista: {n} por cuadro en promedio.",
        "en": "Few players in view: {n} per frame on average.",
    },
    "ball_unseen": {
        "es": "Balón casi no detectado: visto en {pct} % de los cuadros.",
        "en": "Ball barely detected: seen in {pct}% of the frames.",
    },
    # pipeline: the pitch mask
    "play_area": {
        "es": "Zona de juego marcada por el entrenador ({points} puntos, margen de {margin:g} % del alto): es la cancha de todo el video y se quitaron {removed} cajas de personas fuera de ella.",
        "en": "Play area marked by the coach ({points} points, margin of {margin:g}% of the height): it is the pitch for the whole video and {removed} boxes of people outside it were removed.",
    },
    "mask_source": {
        "es": "Máscara de la cancha: {from_app} cuadros vinieron de la app y {measured} se midieron del video.",
        "en": "Pitch mask: {from_app} frames came from the app and {measured} were measured from the video.",
    },
    "dirt_pitch": {
        "es": "Cancha de tierra (según los detalles de la cancha): la máscara y el balón se miden por el color de la tierra, no del pasto.",
        "en": "Dirt pitch (from the pitch's details): the mask and the ball are measured by the color of the dirt, not of the grass.",
    },
    "sample_dirt": {
        "es": "Muestra de la tierra del entrenador, con un margen: la cancha es lo que tiene tono {h0}–{h1}, saturación {s0}–{s1} y brillo {v0}–{v1}.",
        "en": "Coach's sample of the dirt, with a margin: the pitch is what has hue {h0}–{h1}, saturation {s0}–{s1} and value {v0}–{v1}.",
    },
    "sample_grass": {
        "es": "Muestra del pasto del entrenador, con un margen: la cancha es lo que tiene tono {h0}–{h1}, saturación {s0}–{s1} y brillo {v0}–{v1}.",
        "en": "Coach's sample of the grass, with a margin: the pitch is what has hue {h0}–{h1}, saturation {s0}–{s1} and value {v0}–{v1}.",
    },
    "sample_ignored": {
        "es": "Se ignoró la muestra de la cancha: no son rangos válidos.",
        "en": "The pitch sample was ignored: its ranges are not valid.",
    },
    "play_area_moving": {
        "es": "Se ignoró la zona de juego marcada: solo se usa con la cámara fija todo el video.",
        "en": "The marked play area was ignored: it is only used with the camera still for the whole video.",
    },
    "play_area_invalid": {
        "es": "Se ignoró la zona de juego marcada: no es un polígono válido.",
        "en": "The marked play area was ignored: it is not a valid polygon.",
    },
    "mask_unused": {
        "es": "Solo {with_pitch} de {total} cuadros tienen cancha: no se usó la máscara y se analizó todo el video.",
        "en": "Only {with_pitch} of {total} frames show the pitch: the mask was not used and the whole video was analyzed.",
    },
    "few_seconds": {
        "es": "Solo {seconds:.0f} s del video cuentan (sin acercamientos ni tramos sin cancha): las métricas son de poco tiempo.",
        "en": "Only {seconds:.0f} s of the video count (no close-ups or stretches without the pitch): the metrics cover little time.",
    },
    "short_meters": {
        "es": "Menos de 10 s del video tienen la cancha calculada: no se dan métricas en metros.",
        "en": "Less than 10 s of the video have the pitch computed: no metrics in meters are given.",
    },
    # pipeline: tiles and size
    "tiles_small": {
        "es": "los jugadores miden unos {px:.0f} px y SAM los vería de {seen:.0f}",
        "en": "the players are about {px:.0f} px tall and SAM would see them at {seen:.0f}",
    },
    "tiles_wide": {"es": "video ancho", "en": "wide video"},
    "tiles": {
        "es": "SAM vio el video en {count} parte(s) de hasta {size} px sobre la cancha (escala {scale:g}; {why}), con lo que no es cancha en negro; {joins} tracks se unieron entre partes.",
        "en": "SAM saw the video in {count} part(s) of up to {size} px over the pitch (scale {scale:g}; {why}), with what is not the pitch in black; {joins} tracks were joined between parts.",
    },
    "players_small": {
        "es": "Los jugadores miden unos {px:.0f} px en el video de {w}×{h} y SAM los ve de unos {seen:.0f} px (necesita unos {need}): pueden faltar los lejanos y los que van en grupo. Grabe o importe el video a más resolución, o más cerca de la cancha.",
        "en": "The players are about {px:.0f} px tall in the {w}×{h} video and SAM sees them at about {seen:.0f} px (it needs about {need}): distant and grouped players may be missing. Record or import the video at a higher resolution, or closer to the pitch.",
    },
    "discarded": {
        "es": "Se descartaron {n} cuadros sin cancha (acercamientos, tribuna o gráficos): no entran a las métricas.",
        "en": "{n} frames without the pitch were discarded (close-ups, stands or graphics): they are not in the metrics.",
    },
    # pipeline: limitations
    "lim_meters": {
        "es": "Las métricas en metros salen del {pct} % del video (los cuadros con la cancha calculada); las de la imagen, de todo el video.",
        "en": "The metrics in meters come from {pct}% of the video (the frames with the pitch computed); those of the image, from the whole video.",
    },
    "lim_default_size": {
        "es": "Se usaron las medidas por omisión de la cancha ({length:g}×{width:g} m): las distancias son aproximadas.",
        "en": "The pitch's default size was used ({length:g}×{width:g} m): the distances are approximate.",
    },
    "lim_screen": {
        "es": "Coordenadas de pantalla: sin la cancha calculada, las distancias y posiciones son fracciones de la imagen, no metros.",
        "en": "Screen coordinates: without the pitch computed, distances and positions are fractions of the image, not meters.",
    },
    "lim_tracks": {
        "es": "Los tracks son trayectorias detectadas por SAM 3.1: un jugador que sale de cuadro y vuelve puede contar como otro track.",
        "en": "Tracks are trajectories detected by SAM 3.1: a player who leaves the frame and comes back may count as another track.",
    },
    "lim_simulator": {
        "es": "Análisis de desarrollo: la segmentación viene del simulador local, no de SAM 3.1.",
        "en": "Development analysis: the segmentation comes from the local simulator, not from SAM 3.1.",
    },
    # calibration
    "cal_few_marks": {
        "es": "En {at} hay {what}: con 4 cualquier nombre encaja, así que hacen falta 5 o más (puntos y líneas pintadas) para calcular la cancha.",
        "en": "At {at} there are {what}: with 4 any names fit, so 5 or more (points and painted lines) are needed to compute the pitch.",
    },
    "cal_marks_points": {"es": "{n} puntos", "en": "{n} points"},
    "cal_marks_lines": {"es": " y {n} líneas", "en": " and {n} lines"},
    "cal_marks_misfit": {
        "es": "Los puntos y líneas marcados en {at} no encajan con la cancha y no se usaron.",
        "en": "The points and lines marked at {at} do not fit the pitch and were not used.",
    },
    "cal_sam_failed": {
        "es": "SAM no pudo buscar las líneas de la cancha ({error}): la cancha queda solo con los puntos marcados.",
        "en": "SAM could not look for the pitch lines ({error}): the pitch stays with the marked points only.",
    },
    "cal_sam_none": {
        "es": "SAM no encontró líneas de la cancha en los cuadros clave: la cancha queda solo con los puntos marcados.",
        "en": "SAM found no pitch lines on the key frames: the pitch stays with the marked points only.",
    },
    "cal_sam_unconfirmed": {
        "es": "Las líneas que encontró SAM no confirmaron la cancha en ningún cuadro clave: queda la de los puntos marcados.",
        "en": "The lines SAM found did not confirm the pitch on any key frame: the one from the marked points stays.",
    },
    "cal_no_points_no_lines": {
        "es": "Sin puntos marcados no se calcula la cancha: el análisis queda en posiciones de la imagen.",
        "en": "Without marked points the pitch is not computed: the analysis stays in image positions.",
    },
    "cal_no_points": {
        "es": "No hay puntos de la cancha marcados: marque al menos 5 puntos en un cuadro para medir en metros.",
        "en": "No pitch points are marked: mark at least 5 points on a frame to measure in meters.",
    },
    "cal_off_pitch": {
        "es": "{n} persona(s) pasan casi todo el tiempo fuera de las líneas (jueces de línea, banca o cuerpo técnico): quedan sin equipo.",
        "en": "{n} person(s) spend almost all the time outside the lines (linesmen, bench or staff): they are left without a team.",
    },
    "cal_partial": {
        "es": "La cancha solo se calculó en el {pct} % del video.",
        "en": "The pitch was computed in only {pct}% of the video.",
    },
}
