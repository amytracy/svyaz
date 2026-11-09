package app.svyaz.messenger

import android.app.Application
import app.svyaz.messenger.core.NodeRepository
import app.svyaz.messenger.core.Notifier

class App : Application() {
    lateinit var notifier: Notifier
        private set
    lateinit var repository: NodeRepository
        private set

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        repository = NodeRepository(this, notifier)
    }
}
