package io.embodit.fivestones.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.fivestones.domain.AlertKind
import io.embodit.fivestones.domain.Board
import io.embodit.fivestones.domain.BoardGeometry
import io.embodit.fivestones.domain.GameFlow
import io.embodit.fivestones.domain.Phase
import io.embodit.fivestones.domain.PlayState
import io.embodit.fivestones.domain.Settings
import io.embodit.fivestones.domain.Shapes
import io.embodit.fivestones.domain.V3
import io.embodit.fivestones.platform.TAG
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; per-frame motion
 * (flights, the ghost's easing, pulses, pillars rising, the five lifting) runs in [onFrame] from [BoardFrameSystem].
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ tiltRoot (near-bottom board edge, tipped about X)
 *   │ └ boardRoot (board top centre, scaled 100 / 110 %)
 *   │   ├ slab, face (Canvas texture), 225 invisible pads "pad:<i>"
 *   │   ├ stones "stone:<i>", ghost, last-move ring, hint ring, forbidden crosses, pillars, win band, alert marks
 *   ├ bowls (black left, white right; never tilt) with their mouths and the AI's thinking glow
 *   └ plate (AttachmentPanel, upright above the far edge)
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var plate: Entity? = null

    private var geo = BoardGeometry()
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    private val stones = HashMap<Int, ModelEntity>()
    private val pool = ArrayList<ModelEntity>()
    private val pads = ArrayList<Entity>()
    private var padsBuilt = 0
    private var ghost: ModelEntity? = null
    private var ghostCross: Entity? = null
    private var lastRing: ModelEntity? = null
    private var hintRing: ModelEntity? = null
    private var alertRing: ModelEntity? = null
    private var alertCross: Entity? = null
    private val crosses = ArrayList<Entity>()
    private val pillars = ArrayList<ModelEntity>()
    private var beam: ModelEntity? = null
    private var flyer: ModelEntity? = null
    private val bowlGlow = HashMap<Int, ModelEntity>()
    private val bowls = HashMap<Int, Entity>()

    // animation state
    private var clock = 0f
    private var ghostPos: V3? = null
    private var ghostTarget: Int? = null
    private var pillarStart = 0L
    private var pillarKey = ""
    private var winStart = 0L
    private val lifted = HashSet<Int>()
    private val winRings = ArrayList<ModelEntity>()

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
    }

    val ready: Boolean get() = built

    /** The volume's half height from the SpatialView; the floor is at -half. */
    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    /** S00 stand-in: a plain slab and two cylinders, shown until the real board is built. */
    fun showFallback(slab: MeshResource, cylinder: MeshResource, material: Material) {
        if (built || fallback.isNotEmpty()) return
        val g = geo
        val board = ModelEntity(slab, material).apply { setName("fallbackBoard") }
        place(board, g.toScene(V3(0f, -BoardGeometry.THICK / 2, 0f)))
        board.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), g.theta))
        val cyl = listOf(1, 2).map { c ->
            ModelEntity(cylinder, material).apply { setName("fallbackBowl$c"); place(this, g.bowlBase(c) + V3(0f, BoardGeometry.BOWL_H / 2, 0f)) }
        }
        fallback = listOf(board) + cyl
        fallback.forEach { sceneRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    fun install(r: SceneResources, plateEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        plate = plateEntity
        val t0 = SystemClock.elapsedRealtime()
        // board
        boardRoot.addChild(ModelEntity(r.boardSlab, r.woodEdge).apply { setName("slab"); transform().setPosition(Vector3(0f, -BoardGeometry.THICK / 2 - 0.0004f, 0f)) })
        boardRoot.addChild(ModelEntity(r.boardFace, r.face).apply {
            setName("face")
            transform().setPosition(Vector3(0f, 0.0003f, 0f))
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
        })
        // bowls
        for (c in listOf(Board.BLACK, Board.WHITE)) {
            val holder = Entity().apply { setName("bowl:$c") }
            holder.addChild(ModelEntity(r.bowl, r.glaze).apply { setName("bowlBody:$c"); transform().setPosition(Vector3(0f, BoardGeometry.BOWL_H / 2, 0f)) })
            holder.addChild(ModelEntity(r.bowlMouth, if (c == Board.BLACK) r.blackMouth else r.whiteMouth).apply {
                setName("bowlMouth:$c")
                transform().setPosition(Vector3(0f, BoardGeometry.BOWL_H + 0.0008f, 0f))
                transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
            })
            // A glazed lip that always shows, so the bowl reads as a bowl and not a plain cylinder.
            holder.addChild(ModelEntity(r.bowlRim, r.glazeLip).apply { setName("bowlLip:$c"); transform().setPosition(Vector3(0f, BoardGeometry.BOWL_H, 0f)) })
            val glow = ModelEntity(r.bowlRim, r.bowlGlow).apply { setName("bowlGlow:$c"); transform().setPosition(Vector3(0f, BoardGeometry.BOWL_H + 0.004f, 0f)); enabled = false }
            holder.addChild(glow)
            bowlGlow[c] = glow
            bowls[c] = holder
            sceneRoot.addChild(holder)
        }
        // markers
        ghost = ModelEntity(r.stone, r.ghostBlack).apply { setName("ghost"); enabled = false; flatten(this) }
        boardRoot.addChild(ghost!!)
        ghostCross = cross(r, r.red, "ghostCross", 0.032f).also { it.enabled = false; boardRoot.addChild(it) }
        lastRing = ModelEntity(r.ring, r.lastMark).apply { setName("lastRing"); enabled = false }
        boardRoot.addChild(lastRing!!)
        hintRing = ModelEntity(r.bigRing, r.teal).apply { setName("hintRing"); enabled = false }
        boardRoot.addChild(hintRing!!)
        alertRing = ModelEntity(r.bigRing, r.red).apply { setName("alertRing"); enabled = false }
        boardRoot.addChild(alertRing!!)
        alertCross = cross(r, r.red, "alertCross", 0.036f).also { it.enabled = false; boardRoot.addChild(it) }
        repeat(16) { i -> crosses += cross(r, r.redSoft, "forbid:$i", 0.026f).also { it.enabled = false; boardRoot.addChild(it) } }
        repeat(Shapes.MAX) { i -> pillars += ModelEntity(r.pillar, r.tealPillar).apply { setName("pillar:$i"); enabled = false }.also { boardRoot.addChild(it) } }
        repeat(9) { i -> winRings += ModelEntity(r.bigRing, r.teal).apply { setName("winRing:$i"); enabled = false }.also { boardRoot.addChild(it) } }
        beam = ModelEntity(r.bar, r.beam).apply { setName("winBeam"); enabled = false }
        boardRoot.addChild(beam!!)
        flyer = ModelEntity(r.stone, r.blackStone).apply { setName("flyer"); enabled = false; flatten(this) }
        boardRoot.addChild(flyer!!)
        plate?.let { sceneRoot.addChild(it) }

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        layout(force = true)
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms (pads follow in 8 frames)")
        apply(state, settings, force = true)
    }

    private fun flatten(e: Entity) = e.transform().setScaleVector(Vector3(1f, SceneResources.STONE_H / (2 * SceneResources.STONE_R), 1f))

    private fun cross(r: SceneResources, m: Material, name: String, size: Float): Entity {
        val holder = Entity().apply { setName(name) }
        for (a in listOf(PI / 4, -PI / 4)) {
            holder.addChild(ModelEntity(r.bar, m).apply {
                setName("$name:bar")
                transform().setScaleVector(Vector3(size, 0.0012f, size * 0.17f))
                transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), a.toFloat()))
            })
        }
        return holder
    }

    /** 225 invisible pads, a few per frame (design startup: "tap pad 分 8 帧建"). */
    private fun buildPads(count: Int) {
        val r = res ?: return
        val end = min(Board.SIZE, padsBuilt + count)
        for (i in padsBuilt until end) {
            val l = geo.local(i, 0.0015f)
            val pad = Entity().apply {
                setName("pad:$i")
                transform().setPosition(Vector3(l.x, l.y, l.z))
                components.set(CollisionComponent(collisionShape = listOf(r.padShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
            }
            boardRoot.addChild(pad)
            pads += pad
        }
        padsBuilt = end
        if (padsBuilt == Board.SIZE) Log.i(TAG, "PADS built ${pads.size}")
    }

    private fun layout(force: Boolean = false) {
        val g = BoardGeometry(settings.tilt, settings.size)
        if (!force && g.tiltDeg == geo.tiltDeg && g.sizePct == geo.sizePct) return
        geo = g
        place(tiltRoot, g.tiltOrigin)
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), g.theta))
        place(boardRoot, g.boardOffset)
        boardRoot.transform().setScaleVector(Vector3(g.scale, g.scale, g.scale))
        bowls.forEach { (c, e) -> place(e, g.bowlBase(c)) }
        plate?.let { place(it, g.plateCentre()) }
        Log.i(TAG, "LAYOUT tilt=${g.tiltDeg} size=${g.sizePct} plate=${g.plateCentre()} farTop=${g.farTop()}")
    }

    // ---- state ----

    fun apply(next: PlayState, s: Settings, force: Boolean = false) {
        val prev = state
        state = next
        val settingsChanged = s != settings
        settings = s
        if (!built) return
        if (settingsChanged || force) layout()
        val r = res ?: return

        // stones on the board (the one in flight is drawn by the flyer)
        val flying = next.flight?.takeIf { !it.back }?.point
        val want = HashMap<Int, Int>()
        next.moves.forEachIndexed { i, p -> if (p != flying) want[p] = if (i % 2 == 0) Board.BLACK else Board.WHITE }
        val win = next.result?.lines?.flatten()?.toSet().orEmpty()
        stones.keys.filter { it !in want }.forEach { p -> stones.remove(p)?.let { it.enabled = false; pool += it } }
        for ((p, color) in want) {
            val e = stones.getOrPut(p) {
                (if (pool.isNotEmpty()) pool.removeAt(pool.lastIndex) else ModelEntity(r.stone, r.blackStone).also { boardRoot.addChild(it) }).also { flatten(it); it.enabled = true }
            }
            e.setName("stone:$p")
            // Stones keep their own colour when they win (critic 要改 3); the glow is a teal ring under each one.
            val mat = if (color == Board.BLACK) r.blackStone else r.whiteStone
            e.components[ModelComponent::class.java]?.materials?.set(0, mat)
            if (p !in win) { val l = geo.local(p, SceneResources.STONE_H / 2); place(e, l) }
        }
        if (next.result != null && prev.result == null) winStart = SystemClock.uptimeMillis()

        // win band along each winning line
        val line = next.result?.lines?.firstOrNull()
        beam?.let { b ->
            if (line != null && line.size >= 2) {
                val a = geo.local(line.first()); val z = geo.local(line.last())
                val len = hypot(z.x - a.x, z.z - a.z) + BoardGeometry.PITCH * 0.9f
                val yaw = atan2(-(z.z - a.z), z.x - a.x)
                b.transform().setScaleVector(Vector3(len, 0.004f, 0.008f))
                b.transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), yaw))
                place(b, V3((a.x + z.x) / 2, 0.003f, (a.z + z.z) / 2))
                b.enabled = true
            } else b.enabled = false
        }

        // last move ring
        lastRing?.let { ring ->
            val p = next.lastMove
            if (p != null && p != flying && next.result == null) { place(ring, geo.local(p, SceneResources.STONE_H + 0.001f)); ring.enabled = true } else ring.enabled = false
        }

        // forbidden crosses
        val forb = next.forbidden.keys.toList()
        crosses.forEachIndexed { i, e ->
            val p = forb.getOrNull(i)
            if (p != null && next.humansTurn && next.result == null) { place(e, geo.local(p, 0.0012f)); e.enabled = true } else e.enabled = false
        }

        // pillars
        val ps = if (next.result == null) next.pillars else emptyList()
        val key = ps.joinToString { "${it.point}:${it.color}:${it.level}" } + settings.reducedMotion
        if (key != pillarKey) { pillarKey = key; pillarStart = SystemClock.uptimeMillis() }
        val own = next.config?.let { if (it.mode == io.embodit.fivestones.domain.Mode.AI) it.humanColor else next.toMove } ?: Board.BLACK
        pillars.forEachIndexed { i, e ->
            val pl = ps.getOrNull(i)
            if (pl == null) { e.enabled = false; return@forEachIndexed }
            val mine = pl.color == own
            e.components[ModelComponent::class.java]?.materials?.set(0, if (settings.reducedMotion) (if (mine) r.tealFlat else r.amberFlat) else (if (mine) r.tealPillar else r.amberPillar))
            e.enabled = true
        }

        // hint ring
        hintRing?.let { h -> val p = next.hint; if (p != null && next.humansTurn) { place(h, geo.local(p, 0.0012f)); h.enabled = true } else h.enabled = false }

        // ghost material
        ghost?.components?.get(ModelComponent::class.java)?.materials?.set(0, if (next.toMove == Board.BLACK) r.ghostBlack else r.ghostWhite)
        if (next.ghost == null) { ghostTarget = null }
        else if (ghostTarget != next.ghost) {
            if (ghostTarget == null) ghostPos = geo.local(next.ghost, SceneResources.STONE_H / 2)
            ghostTarget = next.ghost
        }

        // flyer material
        next.flight?.let { f -> flyer?.components?.get(ModelComponent::class.java)?.materials?.set(0, if (f.color == Board.BLACK) r.blackStone else r.whiteStone) }

        // AI thinking: its bowl's rim glows
        val aiColor = next.config?.takeIf { it.mode == io.embodit.fivestones.domain.Mode.AI }?.aiColor
        bowlGlow.forEach { (c, e) -> e.enabled = next.phase == Phase.THINKING && c == aiColor }

        onFrame(0f)
    }

    // ---- per frame ----

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        if (padsBuilt < Board.SIZE) buildPads((Board.SIZE + 7) / 8)
        val now = SystemClock.uptimeMillis()
        val st = state
        val reduced = settings.reducedMotion

        // flight
        val f = st.flight
        val fl = flyer
        if (f != null && fl != null) {
            var elapsed = now - f.startedAt
            st.freezeMs?.let { elapsed = min(elapsed, it) }
            val dur = if (f.back) GameFlow.BACK_MS else GameFlow.FLIGHT_MS
            var t = (elapsed.toFloat() / dur).coerceIn(0f, 1f)
            if (f.back) t = 1f - t
            val target = geo.local(f.point, SceneResources.STONE_H / 2)
            val pos = if (reduced) target else geo.toLocal(geo.flight(f.color, f.point, t, SceneResources.STONE_H / 2))
            place(fl, pos)
            val k = if (reduced) t.coerceIn(0.2f, 1f) else 1f
            fl.transform().setScaleVector(Vector3(k, k * SceneResources.STONE_H / (2 * SceneResources.STONE_R), k))
            fl.enabled = true
        } else fl?.enabled = false

        // last-move ring pulses once inside the retract window
        lastRing?.takeIf { it.enabled }?.let { ring ->
            val until = st.retractUntil
            val k = if (until != null && now < until && !reduced) 1f + 0.35f * sin(((until - now) / 500f) * PI.toFloat()).coerceAtLeast(0f) else 1f
            ring.transform().setScaleVector(Vector3(k, 1f, k))
        }

        // ghost easing (60 ms)
        val g = ghost
        val target = ghostTarget
        if (g != null) {
            if (target == null || !st.humansTurn || st.phase !in setOf(Phase.PREVIEW, Phase.IDLE, Phase.ALERT)) {
                g.enabled = false; ghostCross?.enabled = false
            } else {
                val to = geo.local(target, SceneResources.STONE_H / 2)
                val from = ghostPos ?: to
                val a = if (dt <= 0f) 0f else (dt / 0.06f).coerceIn(0f, 1f)
                val p = from.lerp(to, a)
                ghostPos = p
                place(g, p)
                val forbidden = st.ghostForbidden != null
                g.enabled = !forbidden
                ghostCross?.let { c -> place(c, V3(p.x, 0.0015f, p.z)); c.enabled = forbidden }
            }
        }

        // hint ring pulse
        hintRing?.takeIf { it.enabled }?.let { val k = 1f + 0.08f * sin(clock * 4f); it.transform().setScaleVector(Vector3(k, 1f, k)) }

        // alert flash (300 ms) on the point
        val al = st.alert
        val flashing = al != null && now - al.at < 300 + 600 && ((now - al.at) / 150) % 2 == 0L
        alertRing?.let { ring -> if (al != null && al.kind != AlertKind.FORBIDDEN && flashing) { place(ring, geo.local(al.point, SceneResources.STONE_H + 0.002f)); ring.enabled = true } else ring.enabled = false }
        alertCross?.let { c -> if (al != null && al.kind == AlertKind.FORBIDDEN && flashing) { place(c, geo.local(al.point, 0.002f)); c.enabled = true } else c.enabled = false }

        // pillars rise (250 ms) or lie flat with reduced motion
        val ps = if (st.result == null) st.pillars else emptyList()
        val rise = if (reduced) 1f else ((now - pillarStart) / 250f).coerceIn(0f, 1f)
        pillars.forEachIndexed { i, e ->
            val pl = ps.getOrNull(i) ?: return@forEachIndexed
            val h = when (pl.level) { Shapes.OPEN_FOUR -> 0.07f; Shapes.FOUR -> 0.05f; else -> 0.03f }
            if (reduced) {
                e.transform().setScaleVector(Vector3(1.5f, 0.0015f, 1.5f))
                place(e, geo.local(pl.point, 0.001f))
            } else {
                val hh = max(0.001f, h * rise)
                e.transform().setScaleVector(Vector3(1f, hh, 1f))
                place(e, geo.local(pl.point, hh / 2))
            }
        }

        // five in a row: those stones lift 3 cm and pulse
        val res = st.result
        if (res != null && res.lines.isNotEmpty()) {
            val t = ((now - winStart) / 600f).coerceIn(0f, 1f)
            val lift = if (reduced) 0f else 0.03f * (1f - (1f - t) * (1f - t))
            val pulse = 1f + 0.06f * sin(clock * 5f)
            res.lines.flatten().toSet().forEachIndexed { i, p ->
                stones[p]?.let { e -> place(e, geo.local(p, SceneResources.STONE_H / 2 + lift)) }
                winRings.getOrNull(i)?.let { ring ->
                    place(ring, geo.local(p, lift + 0.0005f))
                    ring.transform().setScaleVector(Vector3(pulse, 1f, pulse))
                    ring.enabled = true
                }
            }
            beam?.let { b -> val pp = b.transform().position; b.transform().setPosition(Vector3(pp.x, 0.003f + lift, pp.z)) }
            lifted += res.lines.flatten()
        } else if (lifted.isNotEmpty()) {
            // put lifted stones back after an undo from the result
            for (p in lifted) stones[p]?.let { e -> flatten(e); place(e, geo.local(p, SceneResources.STONE_H / 2)) }
            lifted.clear()
            winRings.forEach { it.enabled = false }
        }

        // the AI's bowl rim breathes
        bowlGlow.values.forEach { e -> if (e.enabled) { val k = 1f + 0.04f * sin(clock * 6f); e.transform().setScaleVector(Vector3(k, 1f, k)) } }
    }

    /** Pad entity name -> point index. */
    fun pointOf(name: String?): Int? = name?.takeIf { it.startsWith("pad:") }?.removePrefix("pad:")?.toIntOrNull()
        ?: name?.takeIf { it.startsWith("stone:") }?.removePrefix("stone:")?.toIntOrNull()

    fun evidence(): String {
        val g = geo
        val lift = lifted.firstOrNull()?.let { stones[it]?.transform()?.position?.y }
        return "lift=$lift tilt=${g.tiltDeg} size=${g.sizePct} pads=$padsBuilt stones=${stones.size} pillars=${pillars.count { it.enabled }} " +
            "crosses=${crosses.count { it.enabled }} plate=${g.plateCentre()} bowlB=${g.bowlBase(Board.BLACK)} bowlW=${g.bowlBase(Board.WHITE)} A1=${g.pointScene(0)} O15=${g.pointScene(224)} halfY=$regionHalfY"
    }

    fun dispose() {
        res?.close()
        res = null
    }
}
