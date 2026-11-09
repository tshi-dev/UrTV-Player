package com.urtv.player.server

import com.urtv.player.R

/** One cycling row of the subtitle style editor: [next] is the style after OK is pressed. Shared by the player and Settings. */
data class StyleRow(val label: Int, val valueRes: Int, val valueText: String, val next: SubtitleStyle)

private val SIZES = listOf(0.045f, 0.052f, 0.058f, 0.066f, 0.076f, 0.09f)
private val BOTTOMS = listOf(0.04f, 0.07f, 0.11f, 0.16f)
private val COLORS = listOf(0xFFFFFFFFL to R.string.srv_c_white, 0xFFFFE066L to R.string.srv_c_yellow, 0xFF7FE9FFL to R.string.srv_c_cyan, 0xFF9CFF9CL to R.string.srv_c_green)
private val BGS = listOf(0x00000000L to R.string.srv_bg_none, 0x99000000L to R.string.srv_bg_dim, 0xFF000000L to R.string.srv_bg_black)
private val FONTS = listOf("sans" to R.string.srv_f_sans, "serif" to R.string.srv_f_serif, "mono" to R.string.srv_f_mono)
private fun edgeRes(e: Edge) = when (e) { Edge.None -> R.string.srv_e_none; Edge.Outline -> R.string.srv_e_outline; Edge.Shadow -> R.string.srv_e_shadow; Edge.Raised -> R.string.srv_e_raised; Edge.Depressed -> R.string.srv_e_depressed }

private fun <V> after(l: List<V>, i: Int) = l[(i.coerceAtLeast(0) + 1) % l.size]

fun styleRows(s: SubtitleStyle): List<StyleRow> {
    val si = SIZES.indices.minByOrNull { kotlin.math.abs(SIZES[it] - s.size) } ?: 2
    val bi = BOTTOMS.indices.minByOrNull { kotlin.math.abs(BOTTOMS[it] - s.bottom) } ?: 1
    val ci = COLORS.indexOfFirst { it.first == s.color }
    val gi = BGS.indexOfFirst { it.first == s.background }
    val fi = FONTS.indexOfFirst { it.first == s.font }
    return listOf(
        StyleRow(R.string.srv_style_size, 0, "${(SIZES[si] * 1000).toInt()}", s.copy(size = SIZES[(si + 1) % SIZES.size])),
        StyleRow(R.string.srv_style_color, COLORS[ci.coerceAtLeast(0)].second, "", s.copy(color = after(COLORS, ci).first)),
        StyleRow(R.string.srv_style_bg, BGS[gi.coerceAtLeast(0)].second, "", s.copy(background = after(BGS, gi).first)),
        StyleRow(R.string.srv_style_edge, edgeRes(s.edge), "", s.copy(edge = Edge.entries[(s.edge.ordinal + 1) % Edge.entries.size])),
        StyleRow(R.string.srv_style_font, FONTS[fi.coerceAtLeast(0)].second, "", s.copy(font = after(FONTS, fi).first)),
        StyleRow(R.string.srv_style_bold, if (s.bold) R.string.on else R.string.off, "", s.copy(bold = !s.bold)),
        StyleRow(R.string.srv_style_pos, 0, "${(BOTTOMS[bi] * 100).toInt()}%", s.copy(bottom = BOTTOMS[(bi + 1) % BOTTOMS.size])),
    )
}
