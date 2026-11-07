package io.embodit.fivestones.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.fivestones.domain.BoardGeometry
import io.embodit.fivestones.ui.theme.FiveColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The few shared meshes / materials / shapes (design budget: <= 10 meshes, <= 6 lit materials). Creating a
 * resource waits on the renderer (~1 s round trip on the emulator), so everything is created concurrently on
 * Dispatchers.IO (Xiangqi BoardScene.createResources) and kept global until [close].
 *
 * Geometry note (B12 / ui_gates "geometry"): the design package's asset list makes the board, the cloud stones
 * and the bowls procedural on purpose (no figurative pieces, no Tripo); the gate:allow lines say so per call.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var boardSlab: MeshResource; private set
    lateinit var boardFace: MeshResource; private set
    lateinit var stone: MeshResource; private set
    lateinit var ring: MeshResource; private set
    lateinit var bigRing: MeshResource; private set
    lateinit var bar: MeshResource; private set
    lateinit var pillar: MeshResource; private set
    lateinit var bowl: MeshResource; private set
    lateinit var bowlMouth: MeshResource; private set
    lateinit var bowlRim: MeshResource; private set
    lateinit var marker: MeshResource; private set

    lateinit var wood: Material; private set
    lateinit var woodEdge: Material; private set
    lateinit var face: Material; private set
    lateinit var blackStone: Material; private set
    lateinit var whiteStone: Material; private set
    lateinit var glaze: Material; private set
    lateinit var glazeLip: Material; private set
    lateinit var blackMouth: Material; private set
    lateinit var whiteMouth: Material; private set

    lateinit var ghostBlack: Material; private set
    lateinit var ghostWhite: Material; private set
    lateinit var red: Material; private set
    lateinit var redSoft: Material; private set
    lateinit var teal: Material; private set
    lateinit var tealPillar: Material; private set
    lateinit var amberPillar: Material; private set
    lateinit var tealFlat: Material; private set
    lateinit var amberFlat: Material; private set
    lateinit var lastMark: Material; private set
    lateinit var beam: Material; private set
    lateinit var bowlGlow: Material; private set

    lateinit var padShape: ShapeResource; private set
    lateinit var stoneShape: ShapeResource; private set
    lateinit var markerMat: Material; private set
    lateinit var markerStar: Material; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        const val STONE_R = 0.017f
        const val STONE_H = 0.009f
        /** Point marker: Ø 9 mm, 2 mm proud of the board face (D50 hover carrier). */
        const val MARKER_R = 0.0045f
        const val MARKER_H = 0.002f

        private fun pbr(color: Color, roughness: Float, metallic: Float = 0f, glow: Float = 0f, blend: BlendingMode = BlendingMode.OPAQUE): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(blend).apply {
                setBaseColor(color.c4(1f))
                setRoughness(roughness)
                setMetallic(metallic)
                if (glow > 0f) setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
            }

        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        /** Builds everything concurrently off the main thread. */
        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            val faceTex = async(io) { TextureResource.create(BoardTextures.boardFace(2048)).also { it.toGlobal() } }
            val blackMouthTex = async(io) { TextureResource.create(BoardTextures.bowlStones(true)).also { it.toGlobal() } }
            val whiteMouthTex = async(io) { TextureResource.create(BoardTextures.bowlStones(false)).also { it.toGlobal() } }
            listOf(
                // gate:allow geometry design-package assets: the kaya board is procedural (slab + Canvas face), no Tripo
                async(io) { r.boardSlab = MeshResource.createBox(Vector3(BoardGeometry.BOARD, BoardGeometry.THICK, BoardGeometry.BOARD), cornerRadius = 0.006f).keep() },
                async(io) { r.boardFace = MeshResource.createPlane(BoardGeometry.BOARD - 0.004f, BoardGeometry.BOARD - 0.004f, 0f).keep() },
                // gate:allow geometry cloud stones are lens bodies (a sphere flattened to 9 mm), design assets "基本几何"
                async(io) { r.stone = MeshResource.createSphere(radius = STONE_R).keep() },
                async(io) { r.ring = MeshResource.createTorus(outerRingRadius = 0.011f, innerRingRadius = 0.0085f).keep() },
                async(io) { r.bigRing = MeshResource.createTorus(outerRingRadius = 0.018f, innerRingRadius = 0.015f).keep() },
                // gate:allow geometry thin bars of the red forbidden cross and the win light band (abstract markers)
                async(io) { r.bar = MeshResource.createBox(Vector3(1f, 1f, 1f), cornerRadius = 0f).keep() },
                // gate:allow geometry shape pillars are abstract light columns (design entities3d.pillars)
                async(io) { r.pillar = MeshResource.createCylinder(radius = 0.01f, height = 1f).keep() },
                // gate:allow geometry the two stone bowls are procedural glazed cylinders (design assets "棋罐 程序化")
                async(io) { r.bowl = MeshResource.createCylinder(radius = BoardGeometry.BOWL_R, height = BoardGeometry.BOWL_H).keep() },
                async(io) { r.bowlMouth = MeshResource.createPlane(BoardGeometry.BOWL_R * 1.72f, BoardGeometry.BOWL_R * 1.72f, BoardGeometry.BOWL_R * 0.86f).keep() },
                async(io) { r.bowlRim = MeshResource.createTorus(outerRingRadius = BoardGeometry.BOWL_R * 1.02f, innerRingRadius = BoardGeometry.BOWL_R * 0.9f).keep() },
                async(io) { r.padShape = ShapeResource.createBox(Vector3(0.040f, 0.003f, 0.040f)).keep() },
                // Collider on the stone mesh: the lens is a sphere scaled flat, so a sphere-sized cube scales to the lens.
                async(io) { r.stoneShape = ShapeResource.createBox(Vector3(2 * STONE_R, 2 * STONE_R, 2 * STONE_R)).keep() },
                // gate:allow geometry point marker: the small visible dot on every intersection that carries the system hover highlight (D50, go-nine v0.1.8)
                async(io) { r.marker = MeshResource.createCylinder(radius = MARKER_R, height = MARKER_H).keep() },
                async(io) { r.markerMat = pbr(Color(0xFF9A6A30), roughness = 0.5f).keep() },
                async(io) { r.markerStar = pbr(FiveColors.BoardInk, roughness = 0.5f).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                async(io) { r.wood = pbr(FiveColors.Kaya, roughness = 0.55f).keep() },
                async(io) { r.woodEdge = pbr(FiveColors.KayaEdge, roughness = 0.6f).keep() },
                async(io) { r.blackStone = pbr(FiveColors.StoneBlack, roughness = 0.35f, glow = 0.02f).keep() },
                async(io) { r.whiteStone = pbr(FiveColors.StoneWhite, roughness = 0.3f).keep() },
                async(io) { r.glaze = pbr(FiveColors.Glaze, roughness = 0.4f, metallic = 0.05f).keep() },
                async(io) { r.glazeLip = pbr(androidx.compose.ui.graphics.Color(0xFF8A6A4A), roughness = 0.3f, metallic = 0.05f).keep() },
                async(io) { r.ghostBlack = unlit(Color(0xFF15181B), 0.45f).keep() },
                async(io) { r.ghostWhite = unlit(Color(0xFFF7F4EC), 0.55f).keep() },
                async(io) { r.red = unlit(FiveColors.Error).keep() },
                async(io) { r.redSoft = unlit(FiveColors.Error, 0.6f).keep() },
                async(io) { r.teal = unlit(FiveColors.Primary).keep() },
                async(io) { r.tealPillar = unlit(FiveColors.Primary, 0.6f).keep() },
                async(io) { r.amberPillar = unlit(FiveColors.Tertiary, 0.62f).keep() },
                async(io) { r.tealFlat = unlit(FiveColors.Primary, 0.75f).keep() },
                async(io) { r.amberFlat = unlit(FiveColors.Tertiary, 0.78f).keep() },
                async(io) { r.lastMark = unlit(Color(0xFFD94F3A)).keep() },
                async(io) { r.beam = unlit(Color(0xFF7FE3D6), 0.7f).keep() },
                async(io) { r.bowlGlow = unlit(FiveColors.Tertiary, 0.85f).keep() },
            ).awaitAll()
            val tex = faceTex.await()
            r.face = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.55f); setMetallic(0f); setBaseColorTexture(tex)
            }.keep()
            synchronized(r.all) { r.all += tex }
            val bm = blackMouthTex.await(); val wm = whiteMouthTex.await()
            r.blackMouth = PhysicallyBasedMaterial.create(BlendingMode.MASKED).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.35f); setOpacityThreshold(0.5f); setBaseColorTexture(bm) }.keep()
            r.whiteMouth = PhysicallyBasedMaterial.create(BlendingMode.MASKED).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.3f); setOpacityThreshold(0.5f); setBaseColorTexture(wm) }.keep()
            synchronized(r.all) { r.all += bm; r.all += wm }
            r
        }
    }
}
