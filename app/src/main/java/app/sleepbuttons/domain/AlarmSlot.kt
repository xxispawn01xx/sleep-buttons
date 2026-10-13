package app.sleepbuttons.domain

/**
 * Every alarm the app can have pending. Each slot owns a stable, distinct
 * PendingIntent identity (request code + data URI), so scheduling one can never
 * overwrite another. Pure Kotlin: no Android imports, so it is unit-testable.
 */
enum class AlarmSlot(
    val id: String,
    val requestCode: Int,
    val label: String,
    val kind: Kind,
) {
    NAP_GET_UP("nap_1", 1001, "Nap: get up", Kind.RINGING_ALARM),
    NAP_BACKUP("nap_2", 1002, "Nap: backup", Kind.RINGING_ALARM),
    MELATONIN("melatonin", 2001, "Melatonin reminder", Kind.REMINDER);

    enum class Kind { RINGING_ALARM, REMINDER }

    /** Request code for the "show" PendingIntent attached to AlarmClockInfo. */
    val showRequestCode: Int get() = requestCode + 10_000

    /** Notification id used when this slot rings or posts. */
    val notificationId: Int get() = requestCode

    /** Data URI that makes the fire Intent unique under Intent.filterEquals(). */
    val uri: String get() = "sleepbuttons://alarm/$id"

    val isNap: Boolean get() = this == NAP_GET_UP || this == NAP_BACKUP

    companion object {
        val napSlots = listOf(NAP_GET_UP, NAP_BACKUP)
        fun fromId(id: String?): AlarmSlot? = entries.firstOrNull { it.id == id }
    }
}

/** Persisted lifecycle of a slot. Only SCHEDULED means "AlarmManager holds it". */
enum class AlarmStatus {
    SCHEDULED,          // registered with AlarmManager successfully
    RINGING,            // nap alarm currently sounding
    POSTED,             // melatonin reminder notification shown, awaiting Taken/Skip
    DISMISSED,
    CANCELLED,
    MISSED,             // fired too late (or after reboot) and was deliberately not sounded
    TIMED_OUT,          // rang for the full ring duration with no response
    TAKEN,
    SKIPPED,
    DUPLICATE_SUPPRESSED, // a dose was already logged for that window when the reminder fired
    NOT_SCHEDULED;      // tried to schedule but could not (permission/failure)

    val isActive: Boolean get() = this == SCHEDULED || this == RINGING
}
