package dev.wledambilight

import android.app.Application
import dev.wledambilight.core.data.AppContainer

class AmbilightApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
