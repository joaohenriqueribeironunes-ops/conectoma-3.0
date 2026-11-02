package com.conectoma.app.core

import java.util.Locale

/** Number formatting for the pt-BR UI. Kept framework-free so it can be unit-tested. */
object Numbers {
    private val BR: Locale = Locale("pt", "BR")

    fun grouped(value: Long): String =
        String.format(BR, "%,d", value).replace(',', '.')

    fun grouped(value: Int): String = grouped(value.toLong())

    fun percent(value: Float): String = "${(value * 100).toInt()}%"
}
