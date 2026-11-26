package io.github.ccch1mneyyy.dsh_app.pet

/**
 * Whether the app in front covers the whole screen — a video, a game, a full-bleed gallery — so
 * the pet steps aside instead of sitting on top of it.
 *
 * Only the accessibility service can see other apps' windows, so it reports here; the overlay
 * machine reads it. Both live in this process. Nothing reported means false, which is the side
 * that keeps the pet: a stale "full screen" must never hide her for good.
 */
internal object ImmersiveWatch {
    @Volatile
    var immersive: Boolean = false
        private set

    fun report(coversWholeScreen: Boolean) {
        immersive = coversWholeScreen
    }
}
