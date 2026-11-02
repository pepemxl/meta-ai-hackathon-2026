package com.ppcoach.tracker.data

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The demo account's sample: a clip and the analysis the worker made of it,
 * bundled in assets/demo/ when the build has them (android_native/README.md#modo-demo).
 * A demo session never uploads: the sample is how it sees an analysis.
 */
object DemoContent {
    const val VIDEO = "demo/demo_clip.mp4"
    const val ANALYSIS = "demo/analysis.json"

    fun available(assets: AssetManager): Boolean =
        runCatching { assets.list("demo")?.toSet()?.containsAll(listOf("demo_clip.mp4", "analysis.json")) == true }.getOrDefault(false)

    /** The sample's match, with the teams and colors the analysis was made with (id 0: the library gives it one). */
    fun match(analysisJson: String, date: String): Match {
        val root = JSONObject(analysisJson)
        val m = (root.optJSONObject("result") ?: root).optJSONObject("match") ?: JSONObject()
        return Match(
            id = 0,
            home = m.optString("home").ifBlank { "A" },
            away = m.optString("away").ifBlank { "B" },
            homeColor = JerseyColor.fromKey(m.optString("home_color"), JerseyColor.RED),
            awayColor = JerseyColor.fromKey(m.optString("away_color"), JerseyColor.BLUE),
            date = date,
            venue = m.optString("venue"),
        )
    }
}
