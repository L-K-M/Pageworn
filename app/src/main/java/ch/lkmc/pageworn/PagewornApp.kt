package ch.lkmc.pageworn

import android.app.Application

class PagewornApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
