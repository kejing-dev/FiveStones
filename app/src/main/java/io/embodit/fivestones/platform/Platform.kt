package io.embodit.fivestones.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.fivestones.data.FileGameStore
import io.embodit.fivestones.data.SettingsStore
import io.embodit.fivestones.domain.GameFlow
import io.embodit.fivestones.domain.Patterns
import io.embodit.fivestones.domain.Sound
import io.embodit.fivestones.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

const val TAG = "FiveStones"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.fivestones.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGameStore(context)
    val flow = GameFlow(
        scope = scope,
        engineDispatcher = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { sounds.play(it) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    init {
        scope.launch(Dispatchers.Default) { Patterns.warmUp() }
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = s.sound
                sounds.volumeLevel = s.volume
            }
        }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Effects from assets/sounds (profile D51). Stone sounds are real CC0 recordings of a Go stone on a wooden board
 * (build/sound-sources.md), cleaned by tools/import_stones.py (spectral subtraction, 120 Hz-6 kHz, no thickening): place /
 * place_2 / place_3 rotate with a random ±5 % pitch; retract = a softer stone click.
 * Short UI cues (illegal, win, lose) are CC0 interface recordings, played quieter than the stones. Hover / pre-selection
 * / hint are silent. One sound of a kind at a time: a new one stops the previous one of the same kind (no stacking).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastVariant = HashMap<String, Int>()
    private val lastStream = HashMap<String, Int>()

    private fun fileOf(s: Sound): String? = when (s) {
        Sound.PLACE -> "place"
        Sound.RETRACT -> "retract"
        Sound.ILLEGAL, Sound.FORBIDDEN -> "illegal"
        Sound.WIN -> "win"
        Sound.LOSE, Sound.DRAW -> "lose"
        Sound.HINT -> null
    }

    private fun gainOf(base: String): Float = when (base) { "place" -> 1f; "retract" -> 0.6f; else -> 0.55f }

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (base in Sound.entries.mapNotNull { fileOf(it) }.toSet()) {
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[base] = found else Log.w(TAG, "no sound for $base")
        }
    }

    fun play(s: Sound) {
        if (!enabled) return
        val base = fileOf(s) ?: return
        val variants = ids[base] ?: return
        val vol = io.embodit.fivestones.ui.control.VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f * gainOf(base)
        var v = if (variants.size > 1) random.nextInt(variants.size) else 0
        if (variants.size > 1 && v == lastVariant[base]) v = (v + 1) % variants.size
        lastVariant[base] = v
        val rate = if (base == "place" || base == "retract") 0.95f + random.nextFloat() * 0.10f else 1f
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        lastStream[base]?.let { pool.stop(it) }
        lastStream[base] = pool.play(id, vol, vol, 1, 0, rate)
    }

    fun release() = pool.release()
}
