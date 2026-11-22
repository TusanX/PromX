package io.github.currencortex.music.data.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class PlayerCoverStyle(val label: String) { CIRCLE("圆形封面"), IMMERSIVE("沉浸式封面") }
enum class ThemeColorStyle(val label: String) { VIVID("鲜明"), TINTED("染色"), SOFT("柔和"), MONOCHROME("单色") }
const val BrandSeedColor = 0xFF5877FFL
const val LegacyBrandSeedColor = 0xFF17635AL
val DefaultThemeColors = listOf(
    BrandSeedColor, // 钴光蓝 · 默认品牌色
    0xFF2B5FA8,  // 墨蓝
    0xFF3F6B4A,  // 松绿
    0xFF8C4A3C,  // 陶土
    0xFF4A4E5A,  // 石墨
    0xFF9C3B5A,  // 胭脂
    0xFF2F7E83,  // 湖水
    0xFF6B6357,  // 岩灰
    0xFF8E8E93,  // 中性
)
enum class RecordShelfPreviewMode { ONCE, LOOP, NEXT, RANDOM }
enum class LaunchStyle(val label: String, val description: String) {
    CLASSIC("玻璃揭示", "液态玻璃扫过音符标志"),
    NONE("无开屏动画", "启动后直接进入应用"),
}
/** Device-reported rates are not limited to a fixed list of 60/90/120 Hz. */
data class RefreshRatePreference(val hz: Int) {
    init { require(hz in 0..1000) }
    val label: String get() = if (hz == 0) "跟随系统" else "$hz Hz"
    // Preserve the existing preference payload, including SYSTEM/HZ60/HZ90/HZ120.
    val name: String get() = if (hz == 0) "SYSTEM" else "HZ$hz"
    companion object {
        val SYSTEM = RefreshRatePreference(0)
        val HZ60 = RefreshRatePreference(60)
        val HZ90 = RefreshRatePreference(90)
        val HZ120 = RefreshRatePreference(120)
        fun valueOf(name: String): RefreshRatePreference {
            if (name == "SYSTEM") return SYSTEM
            require(name.startsWith("HZ"))
            val hz = name.removePrefix("HZ").toInt()
            require(hz in 1..1000)
            return RefreshRatePreference(hz)
        }
    }
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val monet: Boolean = false,
    val uiScale: Float = 1f,
    val blur: Boolean = true,
    val floatingBar: Boolean = true,
    val liquidGlass: Boolean = true,
    val predictiveBack: Boolean = true,
    val navigationGlow: Boolean = true,
    val playlistDepthRibbon: Boolean = false,
    val playlistRecordShelf: Boolean = false,
    val recordShelfPreviewMode: RecordShelfPreviewMode = RecordShelfPreviewMode.ONCE,
    val recordShelfFullPreview: Boolean = false,
    val refreshRate: RefreshRatePreference = RefreshRatePreference.SYSTEM,
    val playerBackground: String = "",
    val playerBackgroundBlur: Float = 35f,
    val launchStyle: LaunchStyle = LaunchStyle.CLASSIC,
    val customFont: String = "",
    val playerDynamicBackground: Boolean = true,
    val pureBlack: Boolean = false,
    val primaryColor: Long = BrandSeedColor,
    val primaryColors: List<Long> = DefaultThemeColors,
    val colorStyle: ThemeColorStyle = ThemeColorStyle.VIVID,
    val customTextScale: Boolean = false,
    val textScale: Float = 1f,
    val playerCoverStyle: PlayerCoverStyle = PlayerCoverStyle.CIRCLE,
    /** 角色皮肤：默认 / 魔理沙（东方 Project 主题） */
) {
    fun normalized() = copy(floatingBar = true,
        uiScale = if (uiScale.isFinite()) uiScale.coerceIn(MIN_SCALE, MAX_SCALE) else 1f,
        playerBackgroundBlur = if (playerBackgroundBlur.isFinite()) playerBackgroundBlur.coerceIn(0f, 100f) else 35f,
        textScale = if (textScale.isFinite()) textScale.coerceIn(.8f, 1.4f) else 1f,
        primaryColor = (primaryColor and 0xFFFFFF) or 0xFF000000,
        primaryColors = primaryColors.map { (it and 0xFFFFFF) or 0xFF000000 }.distinct().take(32).ifEmpty { DefaultThemeColors })
    companion object { const val MIN_SCALE = 0.8f; const val MAX_SCALE = 1.2f }
}
