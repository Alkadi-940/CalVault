package dev.calvault

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ImportPolicy { ASK, ENCRYPTED, HIDDEN }
enum class ThemeStyle { SYSTEM, LIGHT, DARK, AMOLED }
enum class SlideTransition { FADE, SLIDE, NONE }
enum class FileSort { NEWEST, OLDEST, NAME, SIZE }

data class AppSettings(
    val lockOnBackground: Boolean = true,
    val lockOnScreenOff: Boolean = true,
    val resumeLastLocation: Boolean = true,
    val importPolicy: ImportPolicy = ImportPolicy.ASK,
    val automaticRetrieval: Boolean = true,
    val seekForwardSeconds: Int = 10,
    val seekBackSeconds: Int = 10,
    val videoShuffle: Boolean = false,
    val videoReplay: Boolean = false,
    val slideshowSeconds: Int = 5,
    val slideshowShuffle: Boolean = false,
    val slideTransition: SlideTransition = SlideTransition.FADE,
    val theme: ThemeStyle = ThemeStyle.SYSTEM,
    val accent: Int = 0xFFF5B976.toInt(),
    val grid: Boolean = false,
    val sort: FileSort = FileSort.NEWEST,
)

/** Only nonsensitive display and behavior preferences; no PINs, keys, filenames, or locations. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("behavior", Context.MODE_PRIVATE)
    private fun <T : Enum<T>> enumValue(key: String, values: Array<T>, default: T): T =
        values.firstOrNull { it.name == prefs.getString(key, default.name) } ?: default
    private val state = MutableStateFlow(AppSettings(
        lockOnBackground = prefs.getBoolean("lockBackground", true), lockOnScreenOff = prefs.getBoolean("lockScreen", true),
        resumeLastLocation = prefs.getBoolean("resumeLocation", true), importPolicy = enumValue("importPolicy", ImportPolicy.entries.toTypedArray(), ImportPolicy.ASK),
        automaticRetrieval = prefs.getBoolean("autoRetrieval", true),
        seekForwardSeconds = prefs.getInt("forwardSeconds", 10).coerceIn(1, 30), seekBackSeconds = prefs.getInt("backSeconds", 10).coerceIn(1, 30),
        videoShuffle = prefs.getBoolean("videoShuffle", false), videoReplay = prefs.getBoolean("videoReplay", false),
        slideshowSeconds = prefs.getInt("slideSeconds", 5).coerceIn(1, 60), slideshowShuffle = prefs.getBoolean("slideShuffle", false),
        slideTransition = enumValue("slideTransition", SlideTransition.entries.toTypedArray(), SlideTransition.FADE),
        theme = enumValue("theme", ThemeStyle.entries.toTypedArray(), ThemeStyle.SYSTEM), accent = prefs.getInt("accent", 0xFFF5B976.toInt()),
        grid = prefs.getBoolean("grid", false), sort = enumValue("sort", FileSort.entries.toTypedArray(), FileSort.NEWEST),
    ))
    val flow = state.asStateFlow()
    val current get() = state.value
    fun update(settings: AppSettings) {
        require(settings.seekForwardSeconds in 1..30 && settings.seekBackSeconds in 1..30)
        require(settings.slideshowSeconds in 1..60)
        prefs.edit { putBoolean("lockBackground", settings.lockOnBackground).putBoolean("lockScreen", settings.lockOnScreenOff)
            .putBoolean("resumeLocation", settings.resumeLastLocation).putString("importPolicy", settings.importPolicy.name)
            .putBoolean("autoRetrieval", settings.automaticRetrieval)
            .putInt("forwardSeconds", settings.seekForwardSeconds).putInt("backSeconds", settings.seekBackSeconds)
            .putBoolean("videoShuffle", settings.videoShuffle).putBoolean("videoReplay", settings.videoReplay)
            .putInt("slideSeconds", settings.slideshowSeconds).putBoolean("slideShuffle", settings.slideshowShuffle)
            .putString("slideTransition", settings.slideTransition.name).putString("theme", settings.theme.name).putInt("accent", settings.accent)
            .putBoolean("grid", settings.grid).putString("sort", settings.sort.name)
        }
        state.value = settings
    }
}
