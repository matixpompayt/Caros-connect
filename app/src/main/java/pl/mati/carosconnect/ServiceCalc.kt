package pl.mati.carosconnect

import kotlin.math.max
import kotlin.math.roundToInt

/** Ile zostało do serwisu – te same zasady co w CAR OS w radiu. */
object ServiceCalc {

    private const val DAY = 86_400_000L

    fun due(i: CarData.SvcItem, odo: Int?, odoSet: Boolean, now: Long = System.currentTimeMillis()): CarData.Service {
        val kmLeft = if (i.intervalKm > 0 && i.lastKm >= 0 && odoSet && odo != null) i.lastKm + i.intervalKm - odo else null
        val daysLeft = if (i.intervalMonths > 0 && i.lastDate > 0) {
            ((i.lastDate + (i.intervalMonths * 30.44 * DAY).toLong() - now) / DAY).toInt()
        } else null
        val fKm = if (kmLeft != null) 1f - kmLeft.toFloat() / i.intervalKm else 0f
        val fDays = if (daysLeft != null) 1f - daysLeft.toFloat() / (i.intervalMonths * 30.44f) else 0f
        val known = kmLeft != null || daysLeft != null
        val overdue = (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0)
        val soon = !overdue && ((kmLeft != null && kmLeft <= 1000) || (daysLeft != null && daysLeft <= 30))
        return CarData.Service(i.name, overdue, soon, known, kmLeft, daysLeft, describe(known, kmLeft, daysLeft), max(fKm, fDays).coerceIn(0f, 1f))
    }

    fun list(items: List<CarData.SvcItem>, odo: Int?, odoSet: Boolean): List<CarData.Service> =
        items.map { due(it, odo, odoSet) }
            .sortedWith(compareByDescending<CarData.Service> { it.overdue }.thenByDescending { it.soon }.thenByDescending { it.fraction })

    private fun describe(known: Boolean, kmLeft: Int?, daysLeft: Int?): String {
        if (!known) return "ustaw, kiedy ostatnio"
        val parts = mutableListOf<String>()
        kmLeft?.let { parts += if (it <= 0) "${-it} km po terminie" else "za ${"%,d".format(it).replace(',', ' ')} km" }
        daysLeft?.let { parts += if (it <= 0) "${-it} dni po terminie" else if (it < 60) "za $it dni" else "za ${(it / 30.44).roundToInt()} mies." }
        return parts.joinToString(" lub ")
    }
}
