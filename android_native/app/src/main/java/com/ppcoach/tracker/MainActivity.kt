package com.ppcoach.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import com.ppcoach.tracker.ui.AppRoot
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.i18n.stringsFor
import com.ppcoach.tracker.ui.theme.PpCoachTrackerTheme
import java.util.Locale

/** The only Activity: the screens are a stack in CoachViewModel. */
class MainActivity : ComponentActivity() {
    private val vm: CoachViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = vm.settings
            // The first time it follows the phone's language; then Settings decides.
            val strings = stringsFor(settings.language.ifEmpty { Locale.getDefault().language })
            CompositionLocalProvider(LocalStrings provides strings) {
                PpCoachTrackerTheme(settings.theme) {
                    AppRoot(vm, onExit = { finish() })
                }
            }
        }
    }
}
