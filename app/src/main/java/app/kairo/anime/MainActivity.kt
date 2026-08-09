package app.kairo.anime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kairo.anime.ui.KairoAppRoot
import app.kairo.anime.ui.KairoTheme

class MainActivity : ComponentActivity() {
    private var incomingAddonUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureAddonUrl(intent)
        enableEdgeToEdge()
        setContent {
            KairoTheme {
                KairoAppRoot(
                    viewModel = viewModel<MainViewModel>(),
                    incomingAddonUrl = incomingAddonUrl,
                    onAddonUrlConsumed = { incomingAddonUrl = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureAddonUrl(intent)
    }

    private fun captureAddonUrl(intent: Intent) {
        incomingAddonUrl = intent.dataString?.takeIf {
            it.startsWith("stremio://", true) && it.contains("manifest.json", true)
        }
    }
}
