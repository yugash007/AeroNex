package com.aeronex.app.vehicle

/** ArduCopter flight modes (HEARTBEAT custom_mode) the app commands or reports. */
object CopterMode {
    const val STABILIZE = 0L
    const val ALT_HOLD = 2L
    const val AUTO = 3L
    const val GUIDED = 4L
    const val LOITER = 5L
    const val RTL = 6L
    const val LAND = 9L
    const val POSHOLD = 16L
    const val BRAKE = 17L
    const val SMART_RTL = 21L

    private val names = mapOf(
        STABILIZE to "STABILIZE", ALT_HOLD to "ALT_HOLD", AUTO to "AUTO", GUIDED to "GUIDED", LOITER to "LOITER",
        RTL to "RTL", LAND to "LAND", POSHOLD to "POSHOLD", BRAKE to "BRAKE", SMART_RTL to "SMART_RTL",
    )

    /** The mode's name for banners such as "Pilot mode LOITER, use RC"; unknown numbers as "MODE n". */
    fun name(mode: Long): String = names[mode] ?: "MODE $mode"
}
