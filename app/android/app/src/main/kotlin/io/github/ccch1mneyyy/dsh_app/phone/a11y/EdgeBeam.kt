// Portions derived from Libraries.dev Border beam and Distortion waves (https://github.com/Jakubantalik/Libraries.dev), MIT, Copyright (c) 2026 Jakub Antalik. See THIRD_PARTY_NOTICES.md.

package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A band of colour all round the screen's edge while DSH operates the phone (docs/design/phone-overlay.md
 * §12): the colour Distortion waves leaves on the edges, uneven along the edge in seven lumps that
 * drift and breathe slowly, under Border beam's bright stroke right on the edge, with two beams of
 * light travelling round it. Its own full-screen TYPE_ACCESSIBILITY_OVERLAY window,
 * never touchable or focusable, over the bars and the cutout, added under the control's windows.
 * [ControlOverlay] says when it is on, paused or hidden (every screenshot and injected gesture);
 * this class fades it and draws it.
 *
 * Android 13+ draws it with one AGSL shader on the band along the edge; per frame only the lumps',
 * the beams' and the palette's uniforms and the paint's alpha change. Android 9–12 draw an even,
 * still band (a blurred stroke) and the stroke; Android 8.x only the still line.
 */
internal class EdgeBeam(private val context: Context, private val wm: WindowManager) {
    private val view = BeamView(context)
    private var clock = EdgeBeamClock(motion = true)
    private var scheduled = false
    private var drawnAt = 0L
    private var removeWhenGone = false

    val root: View get() = view
    val attached: Boolean get() = view.isAttachedToWindow

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private val frame = Choreographer.FrameCallback { onFrame() }

    /** Adds the window, faded out, for a [dark] or light screen and the app's accent [seed]; false when refused. */
    fun attach(title: CharSequence, dark: Boolean, seed: Int, hidden: Boolean): Boolean {
        view.setLook(dark, seed)
        if (attached) return true
        val motion = OverlayMotion.on()
        clock = EdgeBeamClock(motion)
        view.travels = motion
        removeWhenGone = false
        view.show(0f, 0f, 0.0)
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        params.title = title
        fitScreen()
        return try {
            wm.addView(view, params)
            true
        } catch (e: Exception) {
            Log.w(TAG, "edge rim addView failed", e)
            false
        }
    }

    /** [on]: a task's control shows; [paused]: the rim settles into its still line. */
    fun set(on: Boolean, paused: Boolean) {
        if (!attached) return
        // A new task's control while the last one's rim was still fading out: it stays.
        if (on) removeWhenGone = false
        val now = SystemClock.uptimeMillis()
        clock.show(on, now)
        clock.pause(paused, now)
        schedule()
    }

    /** Not drawn at all (a screenshot or an injected gesture is under way). */
    fun setHidden(hidden: Boolean) {
        if (!attached) return
        val v = if (hidden) View.INVISIBLE else View.VISIBLE
        if (view.visibility == v) return
        view.visibility = v
        schedule()
    }

    /** Goes: faded out first when [animated], the window removed once it is. */
    fun detach(animated: Boolean) {
        if (!attached) return
        if (!animated) { remove(); return }
        clock.show(false, SystemClock.uptimeMillis())
        removeWhenGone = true
        schedule()
    }

    /** A rotation or a new display size. */
    fun relayout() {
        if (!attached) return
        fitScreen()
        try { wm.updateViewLayout(view, params) } catch (e: Exception) { Log.w(TAG, "edge rim update failed", e) }
    }

    /** The display's full size, bars and cutout included. */
    private fun fitScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            params.width = b.width()
            params.height = b.height()
            if (Build.VERSION.SDK_INT >= 31) {
                val insets = wm.currentWindowMetrics.windowInsets
                fun r(pos: Int) = insets.getRoundedCorner(pos)?.radius ?: 0
                // One radius per corner: a folded phone's outer screen is round on the right, nearly
                // square on the left, and the rim has to sit on the glass all the way round.
                view.corners = intArrayOf(
                    r(RoundedCorner.POSITION_TOP_LEFT), r(RoundedCorner.POSITION_TOP_RIGHT),
                    r(RoundedCorner.POSITION_BOTTOM_RIGHT), r(RoundedCorner.POSITION_BOTTOM_LEFT),
                )
            }
        } else {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
        }
    }

    private fun remove() {
        if (scheduled) Choreographer.getInstance().removeFrameCallback(frame)
        scheduled = false
        removeWhenGone = false
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    private fun schedule() {
        if (scheduled || !attached) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun onFrame() {
        scheduled = false
        if (!attached) return
        val now = SystemClock.uptimeMillis()
        if (removeWhenGone && clock.gone(now)) { remove(); return }
        val animating = clock.animating(now, view.drifts)
        // The beams glide round at 60 fps at most, also on a 120 Hz screen.
        if (!animating || now - drawnAt >= EdgeBeamSpec.FRAME_MS) {
            clock.frame(now)
            view.show(clock.fadeNow, clock.stillNow, clock.phase)
            drawnAt = now
        }
        if (animating) schedule() else clock.rest()
    }

    /** The rim itself; paths and shaders are built once per size and look. */
    private class BeamView(context: Context) : View(context) {
        private var dark = true
        private var seed = 0
        private var looked = false
        private var fade = 0f
        private var still = 0f
        private var phase = 0.0

        /** Without animations the beams are left out: a still rim, evenly bright. */
        var travels = true
            set(value) {
                if (value == field) return
                field = value
                if (width > 0) build(width, height)
            }

        /** The display's corner radii in px, top-left to bottom-left; all 0 means not reported. */
        var corners = IntArray(4)
            set(value) {
                if (value.contentEquals(field)) return
                field = value
                if (width > 0) build(width, height)
            }

        /** Android 8.x has no blur in hardware rendering: the still line only. */
        private val blurred = Build.VERSION.SDK_INT >= 28

        private val density = resources.displayMetrics.density
        private val band = Path()
        private val ring = Path()
        private val line = Path()
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val core = Path()
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val lumps = FloatArray(EdgeRim.COUNT * EdgeRim.STRIDE)
        private var perimeter = 0f
        // Made once, here: whether this view's picture drifts is known before its first frame.
        private val rim: Shader? = if (Build.VERSION.SDK_INT >= 33) RimShader.create() else null

        /** Only the shader's rim moves; the bands drawn without it stand still and need no frames. */
        val drifts: Boolean get() = rim != null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setLook(dark: Boolean, seed: Int) {
            if (looked && dark == this.dark && seed == this.seed) return
            looked = true
            this.dark = dark
            this.seed = seed
            if (width > 0) build(width, height)
            invalidate()
        }

        fun show(fade: Float, still: Float, phase: Double) {
            this.fade = fade
            this.still = still
            this.phase = phase
            val shader = rim
            if (shader != null && Build.VERSION.SDK_INT >= 33) RimShader.frame(shader, phase, perimeter, density, still, lumps)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w > 0 && h > 0) build(w, h)
        }

        private fun build(w: Int, h: Int) {
            val palette = EdgeBeamPalette.edge(seed, dark)
            val p = EdgeBeamSpec.preset(dark)
            val r = EdgeBeamSpec.cornersPx(corners.takeIf { c -> c.any { it > 0 } }, density)
            val widthPx = EdgeBeamSpec.WIDTH_DP * density
            val linePx = EdgeBeamSpec.LINE_DP * density
            val corePx = EdgeBeamSpec.CORE_DP * density
            val screen = RectF(0f, 0f, w.toFloat(), h.toFloat())
            perimeter = EdgeRim.perimeter(w.toFloat(), h.toFloat(), r)
            if (Build.VERSION.SDK_INT >= 33) {
                val shader = rim
                if (shader != null) {
                    // Only the band the colour can reach is drawn; the rest of the screen costs nothing.
                    val reach = EdgeBeamSpec.REACH * widthPx
                    band.reset()
                    band.fillType = Path.FillType.EVEN_ODD
                    round(band, screen, r)
                    band.addRect(inset(screen, reach), Path.Direction.CW)
                    val bloomPx = EdgeBeamSpec.BLOOM_DP * density
                    RimShader.look(shader, w, h, r, widthPx, linePx, corePx, bloomPx, reach, p, dark, travels, palette)
                    RimShader.frame(shader, phase, perimeter, density, still, lumps)
                    rimPaint.shader = shader
                    return
                }
            }
            val colours = IntArray(palette.size + 1) { palette[it % palette.size] }
            ring.reset()
            round(ring, screen, r)
            line.reset()
            round(line, inset(screen, linePx / 2), insetRadii(r, linePx / 2))
            linePaint.strokeWidth = linePx
            linePaint.shader = SweepGradient(w / 2f, h / 2f, colours, null)
            core.reset()
            round(core, inset(screen, corePx / 2), insetRadii(r, corePx / 2))
            corePaint.strokeWidth = corePx
            corePaint.shader = SweepGradient(w / 2f, h / 2f, colours, null)
            if (blurred) {
                // A stroke centred on the edge: solid for one width inside, then blurred away over another.
                bandPaint.strokeWidth = 2 * widthPx
                bandPaint.shader = SweepGradient(w / 2f, h / 2f, colours, null)
                bandPaint.maskFilter = BlurMaskFilter(widthPx, BlurMaskFilter.Blur.NORMAL)
            }
        }

        /** [path] gains [rect] with its four corners (top-left, top-right, bottom-right, bottom-left) rounded by [radii]. */
        private fun round(path: Path, rect: RectF, radii: FloatArray) {
            path.addRoundRect(rect, floatArrayOf(
                radii[0], radii[0], radii[1], radii[1], radii[2], radii[2], radii[3], radii[3],
            ), Path.Direction.CW)
        }

        private fun insetRadii(radii: FloatArray, by: Float) = FloatArray(4) { max(radii[it] - by, 0f) }

        override fun onDraw(canvas: Canvas) {
            if (fade <= 0f || width == 0) return
            val p = EdgeBeamSpec.preset(dark)
            if (rim != null) {
                // The shader has the opacities and blends the rim into the still line itself (uniform `still`).
                draw(canvas, band, rimPaint, fade)
                return
            }
            if (blurred) {
                // No beams here: the rim and the stroke as they are between them.
                draw(canvas, ring, bandPaint, p.rim / (1f + EdgeBeamSpec.BEAM_GAIN) * fade * (1f - still))
                draw(canvas, core, corePaint, p.core * EdgeBeamSpec.CORE_REST * fade * (1f - still))
                draw(canvas, line, linePaint, p.line * fade * still)
            } else {
                draw(canvas, line, linePaint, p.line * fade)
            }
        }

        private fun draw(canvas: Canvas, path: Path, paint: Paint, alpha: Float) {
            if (alpha <= ALPHA_FLOOR) return
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
            canvas.drawPath(path, paint)
        }

        private fun inset(r: RectF, by: Float) = RectF(r.left + by, r.top + by, r.right - by, r.bottom - by)
    }

    /** The rim as an AGSL shader (Distortion waves' waveBand, its rim part only) under Border beam's stroke and beams. */
    @RequiresApi(33)
    private object RimShader {
        /**
         * Null when this Android's shader compiler refuses it (AGSL is compiled on the device, by
         * its own Skia): the rim falls back to the Android 9–12 band rather than taking the
         * accessibility service down with it.
         */
        fun create(): Shader? = try {
            RuntimeShader(AGSL)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "edge rim shader refused", e)
            null
        }

        /** What changes with the size and the look. */
        fun look(
            s: Shader, w: Int, h: Int, radii: FloatArray, width: Float, line: Float, core: Float, bloom: Float, reach: Float,
            p: EdgeBeamSpec.Preset, dark: Boolean, travels: Boolean, palette: IntArray,
        ) {
            s as RuntimeShader
            val perimeter = EdgeRim.perimeter(w.toFloat(), h.toFloat(), radii)
            s.setFloatUniform("size", w.toFloat(), h.toFloat())
            s.setFloatUniform("radii", radii)
            s.setFloatUniform("width", width)
            s.setFloatUniform("line", line)
            s.setFloatUniform("core", core)
            s.setFloatUniform("reach", reach)
            s.setFloatUniform("peaks", p.rim, p.line, p.core, EdgeBeamSpec.CORE_REST)
            s.setFloatUniform("bloom", bloom, p.bloom)
            s.setFloatUniform("bloomRest", EdgeBeamSpec.BLOOM_REST)
            s.setFloatUniform("uneven", EdgeBeamSpec.UNEVEN)
            s.setFloatUniform("maxThick", EdgeRim.MAX_THICKNESS)
            s.setFloatUniform("gain", EdgeBeamSpec.BEAM_GAIN)
            s.setFloatUniform("whiten", if (dark) EdgeBeamSpec.CORE_WHITEN else 0f)
            s.setFloatUniform("beamOn", if (travels) 1f else 0f)
            s.setFloatUniform("sigma", perimeter * EdgeBeamSpec.BEAM_SHARE)
            s.setFloatUniform("perimeter", perimeter)
            val rgb = FloatArray(palette.size * 4)
            palette.forEachIndexed { i, c ->
                rgb[i * 4] = (c shr 16 and 0xFF) / 255f
                rgb[i * 4 + 1] = (c shr 8 and 0xFF) / 255f
                rgb[i * 4 + 2] = (c and 0xFF) / 255f
                rgb[i * 4 + 3] = 1f
            }
            s.setFloatUniform("palette", rgb)
        }

        /** What changes every frame: the lumps, the beams, the palette's flow and the stillness; [lumps] is reused. */
        fun frame(s: Shader, phase: Double, perimeter: Float, density: Float, still: Float, lumps: FloatArray) {
            s as RuntimeShader
            EdgeRim.lumps(phase, perimeter, density, lumps)
            s.setFloatUniform("lumps", lumps)
            s.setFloatUniform("head", EdgeFlow.head(phase, perimeter))
            s.setFloatUniform("hue", EdgeFlow.hue(phase))
            s.setFloatUniform("still", still)
        }

        /**
         * Inset from the rounded screen edge, position along it (clockwise from the top centre, by
         * the angle round the screen's centre), the lumps' thickness there, and the colour's falloff inward:
         * exp(−(inset / (width × thickness))²), brighter where a beam passes (EdgeFlow.at). Over it the
         * stroke, exp(−(inset / core)²), brightest (and on a dark screen whitest) under a beam, and a
         * wider bloom, exp(−(inset / bloom.x)²) × bloom.y, also strongest there. The lumps' thickness
         * is pulled towards 1 by `uneven` (EdgeRim.even): one band all round. Paused, all blend into
         * the still line's exp(−(inset / line)²). The palette is laid once round the screen and flows
         * along it. Output is premultiplied; `peaks` = rim, line, stroke, stroke at rest.
         */
        private const val AGSL = """
uniform float2 size;
uniform float4 radii;
uniform float width;
uniform float line;
uniform float core;
uniform float2 bloom;
uniform float bloomRest;
uniform float uneven;
uniform float maxThick;
uniform float reach;
uniform float4 peaks;
uniform float gain;
uniform float whiten;
uniform float beamOn;
uniform float sigma;
uniform float perimeter;
uniform float head;
uniform float hue;
uniform float still;
uniform float4 lumps[7];
uniform float4 palette[9];

float perimeterAt(float2 p, float2 h) {
    // Position along the rim, clockwise from the top centre, by the angle the point is seen at from
    // the screen's centre. One smooth field the whole way round: an edge-by-edge projection leaves
    // a crease pointing in from every corner, where the two edges' positions part company.
    float a = atan(p.x, -p.y);
    if (a < 0.0) a += 6.2831853;
    return perimeter * a / 6.2831853;
}

half4 main(float2 xy) {
    float2 h = size * 0.5;
    float2 p = xy - h;
    float r = p.x < 0.0 ? (p.y < 0.0 ? radii.x : radii.w) : (p.y < 0.0 ? radii.y : radii.z);
    float2 q = abs(p) - (h - r);
    float inset = max(-(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r), 0.0);
    if (inset > reach) return half4(0.0);
    float s = perimeterAt(p, h);
    float t = 0.08;
    for (int i = 0; i < 7; i++) {
        float ds = s - lumps[i].x;
        ds -= perimeter * floor(ds / perimeter + 0.5);
        float k = ds / lumps[i].y;
        t += lumps[i].z * exp(-k * k);
    }
    float beam = 0.0;
    for (int b = 0; b < 2; b++) {
        float ds = s - head - perimeter * 0.5 * float(b);
        ds -= perimeter * floor(ds / perimeter + 0.5);
        float k = ds / sigma;
        beam += exp(-k * k);
    }
    beam = min(beam, 1.0) * beamOn * (1.0 - still);
    float rimK = inset / (width * min(1.0 + uneven * (max(t / 1.0498, 0.04) - 1.0), maxThick));
    float lineK = inset / line;
    float coreK = inset / core;
    float rimA = mix(exp(-rimK * rimK) * peaks.x * (1.0 + gain * beam) / (1.0 + gain), exp(-lineK * lineK) * peaks.y, still);
    float coreA = exp(-coreK * coreK) * peaks.z * mix(peaks.w, 1.0, beam) * (1.0 - still);
    float bloomK = inset / bloom.x;
    rimA = (1.0 - (1.0 - rimA) * (1.0 - exp(-bloomK * bloomK) * bloom.y * mix(bloomRest, 1.0, beam) * (1.0 - still)))
        * (1.0 - smoothstep(reach * 0.8, reach, inset));
    float u = s / perimeter * 9.0 + hue;
    u -= 9.0 * floor(u / 9.0);
    float fu = floor(u);
    int i0 = int(fu);
    float m = u - fu;
    m = m * m * (3.0 - 2.0 * m);
    float4 c0 = palette[0];
    float4 c1 = palette[0];
    for (int j = 0; j < 9; j++) {
        if (j == i0) c0 = palette[j];
        if (j == i0 + 1) c1 = palette[j];
    }
    float3 rgb = mix(c0.rgb, c1.rgb, m);
    float3 coreRgb = mix(rgb, float3(1.0), whiten * beam);
    float a = coreA + rimA * (1.0 - coreA);
    return half4(half3(coreRgb * coreA + rgb * rimA * (1.0 - coreA)), half(a));
}
"""
    }

    companion object {
        private const val TAG = "DshA11y"
        private const val ALPHA_FLOOR = 0.004f
    }
}
