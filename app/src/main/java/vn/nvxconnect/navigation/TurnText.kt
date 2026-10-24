package vn.nvxconnect.navigation

import java.util.Locale
import kotlin.math.roundToInt

/** Câu chỉ dẫn tiếng Việt (hiển thị và đọc giọng nói). */
object TurnText {
    /** Khoảng cách dạng chữ: <100 m làm tròn 10, <1000 m làm tròn 50, còn lại km với 1 chữ số thập phân. */
    fun distanceWords(m: Double): String {
        if (m < 1000) {
            val r = if (m >= 100) (m / 50).roundToInt() * 50 else maxOf(10, (m / 10).roundToInt() * 10)
            if (r < 1000) return "$r mét"
        }
        val km = m / 1000.0
        val t = (km * 10).roundToInt() / 10.0
        return (if (t == t.toInt().toDouble()) "${t.toInt()}" else String.format(Locale.US, "%.1f", t).replace('.', ',')) + " ki-lô-mét"
    }

    fun phrase(m: Maneuver, exit: Int? = null): String = when (m) {
        Maneuver.TURN_LEFT -> "Rẽ trái"
        Maneuver.TURN_RIGHT -> "Rẽ phải"
        Maneuver.SLIGHT_LEFT -> "Chếch sang trái"
        Maneuver.SLIGHT_RIGHT -> "Chếch sang phải"
        Maneuver.SHARP_LEFT -> "Rẽ gấp sang trái"
        Maneuver.SHARP_RIGHT -> "Rẽ gấp sang phải"
        Maneuver.STRAIGHT -> "Đi thẳng"
        Maneuver.UTURN -> "Quay đầu xe"
        Maneuver.ROUNDABOUT -> if (exit != null && exit > 0) "Vào vòng xuyến, ra ở lối ra thứ $exit" else "Vào vòng xuyến"
        Maneuver.FORK_LEFT -> "Đi theo nhánh bên trái"
        Maneuver.FORK_RIGHT -> "Đi theo nhánh bên phải"
        Maneuver.MERGE -> "Nhập vào đường chính"
        Maneuver.DEPART -> "Xuất phát"
        Maneuver.ARRIVE -> "Đã đến nơi"
        Maneuver.UNKNOWN -> "Tiếp tục"
    }

    /** "Rẽ trái vào Đường Láng". Không có tên đường thì bỏ phần "vào ...". */
    fun instruction(step: RouteStep): String = instruction(step.maneuver, step.street, step.roundaboutExit)
    fun instruction(m: Maneuver, street: String, exit: Int? = null): String {
        val base = phrase(m, exit)
        if (street.isBlank()) return base
        return when (m) {
            Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT, Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT,
            Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT, Maneuver.FORK_LEFT, Maneuver.FORK_RIGHT, Maneuver.MERGE -> "$base vào $street"
            Maneuver.STRAIGHT -> "$base trên $street"
            Maneuver.DEPART -> "$base trên $street"
            Maneuver.ROUNDABOUT -> "$base, sau đó đi vào $street"
            else -> base
        }
    }

    /** Câu nhắc khi còn distanceM mét tới thao tác. thresholdM nhỏ (<= 40) thì nói thẳng, không kèm khoảng cách. */
    fun announce(step: RouteStep, distanceM: Double, thresholdM: Int): String {
        if (step.maneuver == Maneuver.ARRIVE)
            return if (thresholdM <= 40) "Bạn đã gần đến nơi" else "Sau ${distanceWords(distanceM)}, bạn sẽ đến nơi"
        val ins = instruction(step)
        return if (thresholdM <= 40) ins else "Sau ${distanceWords(distanceM)}, " + ins.replaceFirstChar { it.lowercase() }
    }
}

object Fmt {
    fun distance(m: Double): String = if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.US, "%.1f km", m / 1000.0)
    fun duration(s: Double): String {
        val min = (s / 60.0).roundToInt()
        return if (min < 60) "$min phút" else "${min / 60} giờ ${min % 60} phút"
    }
}
