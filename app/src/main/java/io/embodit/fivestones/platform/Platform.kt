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
import kotlin.math.abs
import kotlin.random.Random

const val TAG = "FiveStones"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
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
 * Short effects from assets/sounds/<name>.wav (+ _2 / _3 variants) through a SoundPool. The stone sound rotates
 * its three variants with a random rate of 0.93-1.07 that never repeats back to back (design sound.variation).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private var lastRate = 1f
    private var lastVariant = -1

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound for $s")
        }
    }

    fun play(s: Sound) {
        if (!enabled) return
        val variants = ids[s] ?: return
        val vol = io.embodit.fivestones.ui.control.VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f
        var rate = 1f
        var v = 0
        if (s == Sound.PLACE) {
            rate = 0.93f + random.nextFloat() * 0.14f
            if (abs(rate - lastRate) < 0.015f) rate = if (rate < 1f) rate + 0.03f else rate - 0.03f
            lastRate = rate
            v = random.nextInt(variants.size)
            if (v == lastVariant && variants.size > 1) v = (v + 1) % variants.size
            lastVariant = v
        }
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        pool.play(id, vol, vol, 1, 0, rate)
    }

    fun release() = pool.release()
}
