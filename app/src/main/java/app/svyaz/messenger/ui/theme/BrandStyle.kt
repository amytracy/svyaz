package app.svyaz.messenger.ui.theme

enum class BrandStyle(val key: String, val title: String) {
    AUTO("auto", "Авто"),
    ONE_UI("one_ui", "One UI"),
    MATERIAL_YOU("material_you", "Material You");

    /** The style to draw: AUTO follows the phone's maker, the others stand for themselves. */
    fun resolve(manufacturer: String): BrandStyle = when (this) {
        AUTO -> if (manufacturer.equals("samsung", ignoreCase = true)) ONE_UI else MATERIAL_YOU
        else -> this
    }

    companion object {
        fun fromKey(key: String?): BrandStyle = entries.firstOrNull { it.key == key } ?: AUTO
    }
}
