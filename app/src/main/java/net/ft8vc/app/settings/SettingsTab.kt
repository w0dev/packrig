package net.ft8vc.app.settings

/**
 * Tabs inside the Settings screen. Declaration order is display order.
 */
enum class SettingsTab(val title: String) {
    GENERAL("General"),
    RIGS("Rigs"),
    DISPLAY("Display"),
    INTEGRATIONS("Integrations");

    /**
     * Relative tab width, proportional to label length so long labels
     * ("Integrations") don't wrap; floored so short tabs stay tappable.
     */
    val weight: Float get() = title.length.coerceAtLeast(5).toFloat()
}
