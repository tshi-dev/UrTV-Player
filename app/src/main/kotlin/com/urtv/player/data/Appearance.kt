package com.urtv.player.data

enum class ThemeId { Default, Oled, Midnight, Sand, Light, HighContrast }
enum class AccentId { Blue, Teal, Green, Gold, Amber, Coral, Rose, Violet }
enum class FocusStyle { Solid, Ring, Glow }
enum class CornerStyle { Rounded, Square }
enum class BgStyle { Solid, Gradient, Poster }

/** Everything the user can change about the look. Persisted by [Store]; resolved into a ThemeSpec by the UI. */
data class Appearance(
    val theme: ThemeId = ThemeId.Default,
    val accent: AccentId = AccentId.Blue,
    val focus: FocusStyle = FocusStyle.Solid,
    val corner: CornerStyle = CornerStyle.Rounded,
    val background: BgStyle = BgStyle.Gradient,
)
