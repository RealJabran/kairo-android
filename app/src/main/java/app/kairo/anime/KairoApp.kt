package app.kairo.anime

import android.app.Application
import app.kairo.anime.data.KairoRepository

class KairoApp : Application() {
    val repository by lazy { KairoRepository(this) }
}
