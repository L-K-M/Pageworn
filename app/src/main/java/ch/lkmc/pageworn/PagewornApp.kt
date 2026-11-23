package ch.lkmc.pageworn

import android.app.Application
import android.content.res.Configuration

class PagewornApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.homeWidgets.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The launcher picks a widget's arrangement by room computed for the font scale.
        container.homeWidgets.refresh()
    }
}
