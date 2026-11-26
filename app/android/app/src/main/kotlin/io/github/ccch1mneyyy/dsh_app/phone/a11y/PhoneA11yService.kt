// SPDX-FileCopyrightText: 2026 CikeSeven
// SPDX-License-Identifier: MIT
// See THIRD_PARTY_NOTICES.md.
// Local changes: registers into PhoneRegistry; stops the task on screen off, interrupt and
// unbind through ControlListener; debug-only probe.

package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.ccch1mneyyy.dsh_app.pet.PetOverlay
import io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry
import io.github.ccch1mneyyy.dsh_app.phone.PhoneTasks
import io.github.ccch1mneyyy.dsh_app.pet.ImmersiveWatch
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.util.DisplayMetrics
import android.os.Handler
import android.os.Looper

/**
 * DSH's accessibility service (same process as the app). While connected it is the MAIN
 * [io.github.ccch1mneyyy.dsh_app.phone.PhoneDriver] and the overlay
 * [io.github.ccch1mneyyy.dsh_app.phone.UserGate]. It reads nothing on its own: the tree is
 * read only when the bridge calls the driver.
 */
class PhoneA11yService : AccessibilityService() {
    internal val clock = EventClock()
    internal val eventTexts = EventTexts()
    internal val focusInputObservation = FocusInputObservation()
    private var focusInputMethod: FocusInputMethod33? = null
    private var focusInputRetired = false

    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): android.accessibilityservice.InputMethod =
        FocusInputMethod33(this, focusInputObservation).also {
            focusInputMethod = it
            if (destroyed || focusInputRetired) focusInputObservation.retire()
        }

    @android.annotation.TargetApi(33)
    internal fun captureFocusInput(): FocusCapturedInput33? {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (destroyed || instance !== this || PhoneRegistry.mainDriver !== driver || driver == null) return null
        return focusInputMethod?.capture()
    }
    private var overlay: ControlOverlay? = null
    private var driver: MainScreenDriver? = null
    private var companionScreens: CompanionScreenSampler? = null
    @Volatile internal var companionActivityAt = Long.MIN_VALUE
        private set

    internal fun companionScreenSample(control: io.github.ccch1mneyyy.dsh_app.phone.PhoneControl,
        excluded: Set<String>, current: () -> Boolean, image: Boolean = false): Map<String, Any?> =
        companionScreens?.sample(control, excluded, current, image) ?: mapOf("status" to "disconnected")

    internal fun companionScreenCurrent(control: io.github.ccch1mneyyy.dsh_app.phone.PhoneControl,
        excluded: Set<String>, packageName: String, requireIdle: Boolean): Boolean =
        companionScreens?.permitsCurrent(control, excluded, packageName, requireIdle) == true
    private var probe: A11yProbe? = null
    private var receiverRegistered = false
    private var overlayContext: Context? = null
    private var destroyed = false

    /** A dedicated manager whose default token comes from the live accessibility connection. */
    internal fun overlayWindowContext(): Context = overlayContext ?: run {
        val context = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            // AccessibilityService's display/window wrappers obtain getOverlayWindowToken(displayId).
            // An application-derived window context cannot supply that accessibility token.
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else this
        context.also { overlayContext = it }
    }

    internal fun invalidateOverlayWindowContext() { overlayContext = null }

    /** User-initiated display captures must omit the service's own controls too. */
    internal fun hideControlOverlayForPetScreenshot(hide: Boolean) { overlay?.hideForCapture(hide) }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // l10n:keep agent stop reason
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopAll("屏幕关闭了", PhoneTasks.StopSource.SCREEN_OFF)
        }
    }

    override fun onServiceConnected() {
        if (destroyed) return
        super.onServiceConnected()
        // Re-init can reuse this service object. Retire the old driver/handler before publishing
        // the new one; coordinate starts are on this same main queue and check exact publication.
        instance?.takeIf { it !== this }?.teardown("无障碍连接已更换") // l10n:keep agent stop reason
        if (driver != null) teardown("无障碍连接已更换") // l10n:keep agent stop reason
        if (focusInputRetired) {
            if (Build.VERSION.SDK_INT >= 33) focusInputMethod?.reconnect()
            focusInputRetired = false
        }
        invalidateOverlayWindowContext()
        val o = ControlOverlay(this)
        val d = MainScreenDriver(this, o)
        overlay = o
        driver = d
        companionScreens = CompanionScreenSampler(this, o)
        instance = this
        PhoneRegistry.mainDriver = d
        PhoneRegistry.overlayGate = o
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOff, filter)
            receiverRegistered = true
        } catch (e: Exception) { Log.w(TAG, "screen-off receiver", e) }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) probe = A11yProbe(this, d, o).also { it.register() }
        Log.i(TAG, "accessibility service connected")
        PhoneRegistry.controlListener?.onAccessibilityChanged(true)
        refreshImmersive()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) eventTexts.record(event)
        clock.tick(event?.packageName, event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        if (event != null && event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)) companionActivityAt = android.os.SystemClock.uptimeMillis()
        // A keyboard coming or going moves the control and the pet clear of it; the keyguard coming
        // or going is a window too, and the pet leaves while it is up.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            overlay?.onWindowsChanged()
            PetOverlay.onObstaclesChanged()
            refreshImmersive()
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Switching apps changes the window in front even when no window list update follows.
            refreshImmersive()
        }
        // Window changes retire both coordinates and screenshot authorization. Only an identified
        // window of this service's controls is exempt; switching to DSH itself is still a transition.
        if (event != null && invalidatesVisual(event.eventType) {
            overlay?.ownsWindow(event.windowId) == true
        }) driver?.windowChanged()
    }

    /**
     * A window of another app covers the whole display (its bounds start at 0,0 and reach the
     * screen's edges): that is a video, a game or a full-bleed gallery, and the pet keeps out of
     * it. Screens with a status bar, a split screen or a floating window are not full screen.
     */
    private val immersiveHandler = Handler(Looper.getMainLooper())
    private var immersivePending: Runnable? = null
    private var immersiveCandidate = false

    /**
     * A window list can be missing the system bars for a moment while the screen is changing, so
     * entering full screen has to survive a second look 350 ms later; leaving it does not, because
     * the pet coming back late is worse than it coming back at once.
     */
    private fun refreshImmersive() {
        val next = computeImmersive()
        if (!next) {
            immersivePending?.let { immersiveHandler.removeCallbacks(it) }
            immersivePending = null
            immersiveCandidate = false
            if (ImmersiveWatch.immersive) ImmersiveWatch.report(false)
            return
        }
        if (next == immersiveCandidate && immersivePending != null) return
        immersiveCandidate = next
        immersivePending?.let { immersiveHandler.removeCallbacks(it) }
        val confirm = Runnable {
            immersivePending = null
            val again = computeImmersive()
            if (again) {
                Log.i(TAG, "immersive confirmed")
                ImmersiveWatch.report(true)
            } else {
                Log.i(TAG, "immersive dropped: the window list changed again")
            }
        }
        immersivePending = confirm
        immersiveHandler.postDelayed(confirm, 350)
    }

    /**
     * The whole screen, no system bar on top, no keyboard: a video, a game, a full-bleed reader.
     * Split screen, a floating window or picture-in-picture make the active window smaller than a
     * screen, so they keep the pet; a launcher or a chat app draws edge to edge but keeps its
     * status bar, so it keeps the pet too.
     */
    private fun computeImmersive(): Boolean {
        val list = try {
            windows
        } catch (_: Exception) {
            return false
        }
        val app = list.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: return false
        val appRect = Rect()
        app.getBoundsInScreen(appRect)

        @Suppress("DEPRECATION")
        val screens = getSystemService(DisplayManager::class.java)?.displays.orEmpty().map { d ->
            DisplayMetrics().also { d.getRealMetrics(it) }
        }
        val wholeScreen = screens.any { m ->
            appRect.left <= 1 && appRect.top <= 1 &&
                appRect.width() >= m.widthPixels - 1 && appRect.height() >= m.heightPixels - 1
        }
        if (!wholeScreen) return false

        var statusBar = false
        var inputMethod = false
        for (w in list) {
            val r = Rect()
            w.getBoundsInScreen(r)
            when (w.type) {
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    val screen = screens.minByOrNull { Math.abs(it.widthPixels - Math.max(r.width(), 1)) }
                    val width = screen?.widthPixels ?: resources.displayMetrics.widthPixels
                    val barHeight = (screen?.heightPixels ?: resources.displayMetrics.heightPixels) / 8
                    if (r.top <= 1 && r.width() >= width - 1 && r.height() in 1..barHeight) statusBar = true
                }
                // A keyboard means the person is typing: a chat, not an immersive screen.
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> inputMethod = true
            }
        }
        return !statusBar && !inputMethod
    }

    /**
     * The system requests feedback interruption, not cancellation of the phone task.
     * We own no ongoing speech or vibration feedback; keep task ownership and actions intact.
     * Screen off, explicit stop and service teardown retain their separate stop paths.
     */
    override fun onInterrupt() {
        Log.i(TAG, "accessibility feedback interrupted; phone task unchanged")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding swaps the default display: a window context made for the old one adds windows to
        // a screen that is now off, so the pet (and anything else we float) goes missing.
        invalidateOverlayWindowContext()
        overlay?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // l10n:keep agent stop reason
        teardown("无障碍服务已关闭")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        destroyed = true
        immersivePending?.let { immersiveHandler.removeCallbacks(it) }
        immersivePending = null
        // l10n:keep agent stop reason
        teardown("无障碍服务已停止")
        super.onDestroy()
    }

    /** Commit the ambient fence before cancelling prompts, whose STOP reply is not a user choice. */
    internal fun stopAll(reason: String, source: PhoneTasks.StopSource =
        PhoneTasks.StopSource.ENVIRONMENT) {
        try {
            PhoneRegistry.controlListener?.onStopAllRequested(reason, source)
        } finally {
            driver?.abort()
            overlay?.stopAll()
        }
    }

    private fun teardown(reason: String) {
        focusInputObservation.retire()
        focusInputRetired = true
        if (instance !== this && driver == null) return
        val d = driver
        val o = overlay
        if (PhoneRegistry.mainDriver === d) PhoneRegistry.mainDriver = null
        if (PhoneRegistry.overlayGate === o) PhoneRegistry.overlayGate = null
        if (instance === this) instance = null
        invalidateOverlayWindowContext()
        try {
            PhoneRegistry.controlListener?.onStopAllRequested(reason)
        } finally {
            d?.abort()
            o?.destroy()
            d?.close()
            companionScreens?.close()
            companionScreens = null
            probe?.unregister()
            probe = null
            if (receiverRegistered) { runCatching { unregisterReceiver(screenOff) }; receiverRegistered = false }
            driver = null
            overlay = null
            Log.i(TAG, "accessibility service gone: $reason")
            PhoneRegistry.controlListener?.onAccessibilityChanged(false)
        }
    }

    companion object {
        private const val TAG = "DshA11y"

        internal fun invalidatesVisual(eventType: Int, ownOverlay: () -> Boolean): Boolean =
            (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) && !ownOverlay()

        /** The connected service, if any. */
        @Volatile var instance: PhoneA11yService? = null
            private set

        fun isConnected(): Boolean = instance != null

        fun component(context: Context) = ComponentName(context.packageName, PhoneA11yService::class.java.name)

        /** Whether the person switched the service on in system settings (it may not be bound yet). */
        fun isEnabled(context: Context): Boolean {
            val list = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Exception) { null } ?: return false
            val me = component(context)
            return list.split(':').any { entry -> ComponentName.unflattenFromString(entry.trim()) == me }
        }
    }
}
