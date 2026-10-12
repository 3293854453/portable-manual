package com.portablemanual.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 黑白灰调色板。
 *
 * 全部为中性灰阶，没有任何彩色 —— 这是刻意的：说明书本身是花花绿绿的印刷品，
 * 界面越素，内容越突出。
 */
object Ink {
    val Ink900 = Color(0xFF0B0B0C)
    val Ink800 = Color(0xFF17171A)
    val Ink700 = Color(0xFF26262B)

    val Gray700 = Color(0xFF3F3F46)
    val Gray600 = Color(0xFF5A5A61)
    val Gray500 = Color(0xFF7A7A82)
    val Gray400 = Color(0xFF9A9AA0)
    val Gray300 = Color(0xFFC9C9CE)
    val Gray200 = Color(0xFFE4E4E7)
    val Gray150 = Color(0xFFEDEDF0)
    val Gray100 = Color(0xFFF4F4F5)
    val Gray50 = Color(0xFFFAFAFA)

    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)

    val Danger = Color(0xFFB3261E)
    val DangerDark = Color(0xFFF2B8B5)
}

/**
 * 标签配色：同样是灰阶。用深浅区分标签，而不是靠颜色，
 * 这样界面永远只有黑白灰三种信息层。
 */
val TagPalette: List<Color> = listOf(
    Ink.Ink900,
    Ink.Gray700,
    Ink.Gray500,
    Ink.Gray400,
    Ink.Gray300,
    Ink.Gray200
)

fun tagColor(index: Int): Color = TagPalette[((index % TagPalette.size) + TagPalette.size) % TagPalette.size]
