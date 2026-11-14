package inego.zungjunggame.i18n

/**
 * Simple in-process listeners for locale changes (hot-switch).
 *
 * Immediate-mode widgets that pull strings every frame need not subscribe.
 * Snapshot state (confirm titles, open reference browser, cached headlines)
 * should rebind in [Listener.onLocaleChanged].
 */
object LocaleBus {
    fun interface Listener {
        fun onLocaleChanged(locale: AppLocale)
    }

    private val listeners = LinkedHashSet<Listener>()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun clearListeners() {
        listeners.clear()
    }

    fun fire(locale: AppLocale) {
        // Snapshot so a listener may unregister during notification.
        val copy = listeners.toList()
        for (l in copy) {
            l.onLocaleChanged(locale)
        }
    }
}
