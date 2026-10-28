package com.ppcoach.tracker.data

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The coach's consent to use their videos for the analysis (docs/legal/).
 * The version is the same constant as in both APIs.
 */
object Consent {
    const val VERSION = "2026-10-27"
    const val DEFAULT_SERVER = "https://ppcoachtracker.pepemxl.com"

    enum class Doc { PRIVACY, TERMS, PLAYERS_FORM }

    /** Public page of a legal text on [server] (or the production one), in the app's language. */
    fun url(server: String?, doc: Doc, english: Boolean): String {
        val base = ApiClient.normalizeUrl(server?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER)
        val page = when (doc) {
            Doc.PRIVACY -> if (english) "en/privacy.html" else "privacidad.html"
            Doc.TERMS -> if (english) "en/consent.html" else "consentimiento.html"
            Doc.PLAYERS_FORM -> if (english) "en/players-form.html" else "formato-jugadores.html"
        }
        return "$base/legal/$page"
    }

    fun isoNow(now: Instant = Instant.now()): String = DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    /** The consent block of POST /clips. */
    fun clipBlock(acceptedAt: String, appVersion: String): JSONObject =
        JSONObject().put("version", VERSION).put("accepted_at", acceptedAt).put("app_version", appVersion)

    /** The body of POST /consent. */
    fun event(action: String, version: String, at: String, appVersion: String): JSONObject {
        require(action == "accept" || action == "withdraw")
        return JSONObject().put("action", action).put("version", version).put("at", at).put("app_version", appVersion)
    }

    /** Whether the accepted version is the one in force (else the dialog shows again). */
    fun isCurrent(acceptedVersion: String?): Boolean = acceptedVersion == VERSION

    /** A 428 from POST /clips: withdrawn (accept again) or outdated terms (update the app). Never retried. */
    enum class Rejection { WITHDRAWN, OUTDATED }

    fun rejection(e: ApiException): Rejection? = when {
        e.code != 428 -> null
        e.message == "consent withdrawn" -> Rejection.WITHDRAWN
        else -> Rejection.OUTDATED
    }
}
