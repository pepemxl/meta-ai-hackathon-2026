package com.ppcoach.tracker.ui

import com.ppcoach.tracker.ui.i18n.En
import com.ppcoach.tracker.ui.i18n.Es
import com.ppcoach.tracker.ui.i18n.Strings
import com.ppcoach.tracker.ui.theme.PpTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StringsTest {
    /** Every property of Strings, read from both objects: none may be empty. */
    @Test
    fun spanishAndEnglishImplementEverythingWithoutEmptyTexts() {
        val getters = Strings::class.java.methods.filter { it.parameterCount == 0 && it.returnType == String::class.java }
        assertTrue(getters.size > 100)
        for (lang in listOf<Strings>(Es, En)) {
            for (g in getters) {
                val v = g.invoke(lang) as String
                assertTrue("${lang.code}.${g.name} is empty", v.isNotBlank())
            }
            for (k in listOf("play", "half", "full", "training")) assertTrue(lang.kindLabel(k).isNotBlank())
            for (k in listOf("high", "medium", "low", "info")) assertTrue(lang.severityLabel(k).isNotBlank())
            assertTrue(lang.clipsCount(1).isNotBlank() && lang.clipsCount(3).isNotBlank())
        }
        assertEquals("es", Es.code)
        assertEquals("en", En.code)
    }
}

class ThemeContrastTest {
    @Test
    fun everyTextBackgroundPairPassesWcagAa() {
        for ((name, tokens) in listOf("light" to PpTokens.LIGHT, "dark" to PpTokens.DARK)) {
            for ((pair, fg, bg) in PpTokens.pairs(tokens)) {
                val ratio = PpTokens.contrast(fg, bg)
                assertTrue("$name $pair: %.2f < 4.5".format(ratio), ratio >= 4.5)
            }
        }
    }

    @Test
    fun noHandWrittenColorsOutsideTheTheme() {
        // Colors live in ui/theme/ (and JerseyColor in data/Models.kt).
        val src = File("src/main/java/com/ppcoach/tracker")
        val offenders = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.replace('\\', '/').contains("/ui/theme/") || it.name == "Models.kt" }
            .filter { f -> Regex("""Color\(0x[0-9A-Fa-f]{6,8}""").containsMatchIn(f.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
