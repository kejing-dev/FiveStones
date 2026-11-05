package io.embodit.fivestones.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.fivestones.domain.LevelGate
import kotlin.math.asin

/**
 * Opens / reopens the control window (native-window-move.md §3.5 and §7 item 9). Every open first asks the
 * [LevelGate]; the board's frame system feeds it the head pitch each frame. In Shared Space the head pose is
 * usually not available to the app (HMDTrackingProvider reports NOT_IN_FULL_SPACE), so the gate opens after
 * its no-head fallback (1.5 s). "Bring it back" = close, wait for ON_DESTROY, then open again (§3.5).
 */
class WindowCtl {
    val gate = LevelGate()
    var levelHint by mutableStateOf(false)
        private set
    var panelState by mutableStateOf("never")
        private set

    private var open: (() -> Unit)? = null
    private var close: (() -> Unit)? = null
    private var recallPending = false
    private var lastStepMs = 0L
    private val main = Handler(Looper.getMainLooper())
    private var hmd: HMDTrackingProvider? = null
    private var hmdTried = false

    /** The board screen hands over the navigator calls (the window's own composition can't reopen itself). */
    fun attach(open: () -> Unit, close: () -> Unit) {
        this.open = open
        this.close = close
    }

    fun detach() {
        open = null; close = null
        gate.cancel()
        main.removeCallbacks(watchdog)
        runCatching { hmd?.stop() }
        hmd = null
    }

    /** Ask for the window; it opens once the head is level (or the fallback fires). */
    fun request(why: String) {
        gate.ask(why, SystemClock.elapsedRealtime())
        Log.i(TAG, "WINDOW ask why=$why state=$panelState")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /** Settings [叫回面前]: close and reopen in front of the user. */
    fun recall() {
        if (panelState == "never" || panelState == "ON_DESTROY") request("recall")
        else { recallPending = true; Log.i(TAG, "WINDOW recall close"); close?.invoke() }
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (recallPending && event == "ON_DESTROY") { recallPending = false; request("recall") }
    }

    /** Called every frame by the board's ECS system. */
    fun stepFrame() {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (!gate.waiting) return
        val pitch = headPitch()
        levelHint = gate.hintWanted(pitch)
        gate.step(now, pitch)?.let { why -> levelHint = false; openNow(why, pitch) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        Log.i(TAG, "WINDOW open why=$why pitch=$pitch")
        open?.invoke()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }

    /** Head pitch in degrees (+ up), or null without a valid head pose (all zero / identity counts as invalid). */
    private fun headPitch(): Float? {
        if (!hmdTried) {
            hmdTried = true
            hmd = runCatching {
                HMDTrackingProvider().also { p ->
                    val r = p.start()
                    Log.i(TAG, "HMD start=$r support=${p.supportState} state=${p.state}")
                }
            }.onFailure { Log.w(TAG, "HMD unavailable: $it") }.getOrNull()
        }
        val provider = hmd ?: return null
        if (provider.state != DataProvider.State.STARTED) return null
        val pose = runCatching { provider.latestData?.hmdPose }.getOrNull() ?: return null
        val q = pose.rotation
        val p = pose.position
        if (p.x == 0f && p.y == 0f && p.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) return null
        val f = q.rotateVector(Vector3(0f, 0f, -1f))
        return Math.toDegrees(asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}
