package io.embodit.fivestones.ui.board.input

import com.pico.spatial.core.ecs.Entity

/**
 * What a pinch / trigger / gaze can land on, resolved by the factory board-input template (profile D50):
 * the 225 point holders `pad:<i>` (collider + Interactable + system HoverEffect) with their visible marker child
 * `marker:<i>`, and the placed stones `stone:<i>` (collider + HoverEffect on the stone mesh itself).
 * Anything else (board slab, face, bowls, plate, flying stone, ghost) is not a target.
 */
object FiveTargets : TargetResolver<Int> {
    private val PREFIXES = listOf("pad:", "marker:", "stone:")

    /** Pure name rule, unit-tested: entity name -> board point, or null. */
    fun pointOf(name: String?): Int? {
        if (name == null) return null
        for (p in PREFIXES) if (name.startsWith(p)) return name.removePrefix(p).toIntOrNull()?.takeIf { it in 0 until 225 }
        return null
    }

    /** The same walk as [resolveUp], over a name path (hit entity first, then its parents): for replay tests. */
    fun resolvePath(names: List<String>, maxDepth: Int = 10): Int? = names.take(maxDepth + 1).firstNotNullOfOrNull { pointOf(it) }

    override fun resolve(entity: Entity): Int? = pointOf(runCatching { entity.getName() }.getOrNull())
}
