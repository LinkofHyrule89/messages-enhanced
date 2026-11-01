package com.ubermicrostudios.messagesenhanced.ui.effects

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen message effects like the Google Messages phone app (our own
 * drawing code, no Google assets): keyword/holiday effects and reaction bursts.
 */
object ScreenEffects {
    enum class Kind { CELEBRATE, FIREWORKS, LOVE, LAUGH, SAD, ANGER, SURPRISE, GOODMORNING, GOODNIGHT, SPRING, SUMMER, FALL, WINTER, THANKSGIVING, OLYMPICS, BURST }

    private class Rule(val kind: Kind, val re: Regex, val emoji: List<String>)

    private fun r(kind: Kind, pattern: String, vararg emoji: String) =
        Rule(kind, Regex(pattern, RegexOption.IGNORE_CASE), emoji.toList())

    private val RULES = listOf(
        r(Kind.FIREWORKS, """\bhappy new year\b|\bhappy (4th|fourth)( of july)?\b|\bfireworks?\b|\bhappy independence day\b""", "\uD83C\uDF86", "\uD83C\uDF87", "\uD83E\uDDE8"),
        r(Kind.CELEBRATE, """\bhappy (birthday|bday|anniversary|graduation)\b|\bhbd\b|\bcongrat(s|ulations)\b|\bwe did it\b""", "\uD83C\uDF89", "\uD83E\uDD73", "\uD83C\uDF8A", "\uD83C\uDF82"),
        r(Kind.LOVE, """\bi love (you|u)\b|\blove (you|u|ya)\b|\bhappy valentine'?s?( day)?\b|\bmiss (you|u)\b""", "\u2764\uFE0F", "\uD83D\uDE0D", "\uD83E\uDD70", "\uD83D\uDC98", "\uD83D\uDC96"),
        r(Kind.WINTER, """\bmerry (christmas|xmas)\b|\bhappy holidays\b|\blet it snow\b|\bit'?s snowing\b|\bhappy hanukkah\b|\bseason'?s greetings\b""", "\u2744\uFE0F", "\u2603\uFE0F", "\u26C4", "\uD83C\uDF84"),
        r(Kind.THANKSGIVING, """\bhappy thanksgiving\b|\bgobble gobble\b""", "\uD83E\uDD83"),
        r(Kind.FALL, """\bhappy (fall|autumn)\b|\bhappy halloween\b|\btrick or treat\b""", "\uD83C\uDF42", "\uD83C\uDF41", "\uD83C\uDF83"),
        r(Kind.SPRING, """\bhappy (spring|easter)\b|\bspring is here\b""", "\uD83C\uDF38", "\uD83C\uDF37", "\uD83C\uDF3C"),
        r(Kind.SUMMER, """\bhappy summer\b|\bsummer vibes\b|\bbeach day\b""", "\u2600\uFE0F", "\uD83C\uDFD6\uFE0F", "\uD83C\uDF1E"),
        r(Kind.OLYMPICS, """\bgo team\b|\bgold medal\b|\blet'?s go+\b""", "\uD83E\uDD47", "\uD83C\uDFC5", "\uD83C\uDFC6"),
        r(Kind.GOODMORNING, """\bgood ?morning\b|\brise and shine\b""", "\uD83C\uDF05", "\uD83C\uDF04"),
        r(Kind.GOODNIGHT, """\bgood ?night\b|\bsweet dreams\b|\bnighty night\b""", "\uD83C\uDF19", "\uD83D\uDE34", "\uD83C\uDF1B"),
        r(Kind.LAUGH, """\b(lol|lmao|lmfao|rofl)\b|\bha(ha)+\b""", "\uD83D\uDE02", "\uD83E\uDD23"),
        r(Kind.SURPRISE, """\bomg\b|\boh my (god|gosh)\b|\bno way\b""", "\uD83D\uDE2E", "\uD83D\uDE32", "\uD83E\uDD2F"),
        r(Kind.SAD, """\bso sad\b|\bi'?m sad\b|\bsorry for your loss\b""", "\uD83D\uDE22", "\uD83D\uDE2D", "\uD83E\uDD7A"),
        r(Kind.ANGER, """\bso (mad|angry)\b|\bi'?m (mad|angry|furious)\b""", "\uD83D\uDE21", "\uD83D\uDE20", "\uD83E\uDD2C"),
    )

    val REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21", "\uD83D\uDE20", "\uD83D\uDC4E", "\uD83D\uDCA9", "\uD83C\uDF89")

    fun detect(text: String?): Kind? {
        val t = text.orEmpty()
        if (t.isBlank() || t.length > 600) return null
        for (rule in RULES) {
            if (rule.re.containsMatchIn(t) || rule.emoji.any { t.contains(it) }) return rule.kind
        }
        return null
    }

    fun emojiFor(kind: Kind): List<String> = RULES.firstOrNull { it.kind == kind }?.emoji ?: listOf("\u2728")

    private fun animationsOff(context: Context): Boolean =
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    fun play(activity: Activity, kind: Kind, emoji: List<String> = emojiFor(kind)) {
        if (animationsOff(activity) || activity.isFinishing) return
        val root = activity.window?.decorView as? ViewGroup ?: return
        (root.findViewWithTag<View>(TAG) as? EffectView)?.stop()
        val view = EffectView(activity, kind, emoji)
        view.tag = TAG
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.start()
    }

    /** Reaction burst; false when [emoji] has no effect. */
    fun reaction(activity: Activity, emoji: String): Boolean {
        val e = emoji.replace("\uFE0F", "")
        if (REACTIONS.none { it.replace("\uFE0F", "") == e }) return false
        if (e == "\uD83C\uDF89") play(activity, Kind.CELEBRATE) else play(activity, Kind.BURST, listOf(emoji))
        return true
    }

    private const val TAG = "me_screen_effect"

    private class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val g: Float = 0f, val drag: Float = 1f, val size: Float, val color: Int = Color.WHITE,
        val emoji: String? = null, val life: Int, var delay: Int = 0, val sway: Float = 0f, val ph: Float = 0f,
        var rot: Float = 0f, val vr: Float = 0f, val type: Int = 0, var age: Int = 0,
    )

    private class EffectView(context: Context, val kind: Kind, val emoji: List<String>) : View(context) {
        private val parts = ArrayList<P>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var anim: ValueAnimator? = null
        private val d = resources.displayMetrics.density
        private var spawned = false

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun start() {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 4500
                addUpdateListener { step(); invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { stop() }
                })
                start()
            }
        }

        fun stop() {
            anim?.removeAllUpdateListeners()
            anim?.cancel()
            anim = null
            (parent as? ViewGroup)?.removeView(this)
        }

        private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

        private fun spawn(w: Float, h: Float) {
            val colors = intArrayOf(0xFFFF5A5F.toInt(), 0xFFFFB400.toInt(), 0xFF00C2A8.toInt(), 0xFF4285F4.toInt(), 0xFFA142F4.toInt(), 0xFF34A853.toInt(), 0xFFFF7AB6.toInt())
            when (kind) {
                Kind.CELEBRATE, Kind.OLYMPICS -> {
                    val cols = if (kind == Kind.OLYMPICS) intArrayOf(0xFF0081C8.toInt(), 0xFFFCB131.toInt(), 0xFF000000.toInt(), 0xFF00A651.toInt(), 0xFFEE334E.toInt()) else colors
                    repeat(90) { i ->
                        val left = i % 2 == 0
                        parts += P(if (left) -10f else w + 10f, h * rnd(.55f, .9f), (if (left) 1 else -1) * rnd(4f, 11f) * d / 2.5f, -rnd(9f, 17f) * d / 2.5f,
                            g = .32f * d / 2.5f, drag = .985f, size = rnd(6f, 11f) * d / 2f, color = cols[i % cols.size], life = rnd(140f, 200f).toInt(), rot = rnd(0f, 6f), vr = rnd(-.3f, .3f), type = 1)
                    }
                }
                Kind.FIREWORKS -> repeat(4) { b ->
                    val cx = w * rnd(.2f, .8f); val cy = h * rnd(.15f, .45f); val col = colors[b % colors.size]
                    repeat(46) { i ->
                        val a = (i / 46f) * 2 * PI.toFloat(); val s = rnd(2.5f, 5.5f) * d / 2.5f
                        parts += P(cx, cy, cos(a) * s, sin(a) * s, g = .05f * d / 2.5f, drag = .975f, size = rnd(2f, 3.4f) * d / 2f, color = col, life = rnd(70f, 100f).toInt(), delay = b * 22, type = 2)
                    }
                }
                Kind.WINTER -> repeat(90) {
                    parts += P(rnd(0f, w), -rnd(10f, h * .6f), rnd(-.6f, .6f), rnd(1.2f, 2.8f) * d / 2.5f, size = rnd(2f, 5f) * d / 2f, life = 260, sway = rnd(.5f, 1.6f), ph = rnd(0f, 6f), type = 2)
                }
                Kind.FALL, Kind.THANKSGIVING, Kind.SPRING -> repeat(60) { i ->
                    parts += P(rnd(0f, w), -rnd(10f, h * .6f), rnd(-.6f, .6f), rnd(1.2f, 2.8f) * d / 2.5f, size = rnd(18f, 30f) * d, emoji = emoji[i % emoji.size],
                        life = 260, sway = rnd(.5f, 1.6f), ph = rnd(0f, 6f), rot = rnd(0f, 6f), vr = rnd(-.04f, .04f))
                }
                Kind.GOODNIGHT -> {
                    repeat(70) { parts += P(rnd(0f, w), rnd(0f, h * .7f), 0f, 0f, size = rnd(1f, 2.8f) * d, color = 0xFFFFF8D0.toInt(), life = rnd(150f, 220f).toInt(), ph = rnd(0f, 6f), type = 3) }
                    parts += P(w * .78f, h * .18f, 0f, 0f, size = 64f * d, emoji = "\uD83C\uDF19", life = 220)
                }
                Kind.GOODMORNING -> parts += P(w / 2, h + 40, 0f, -2.2f * d / 2.5f, size = 0f, life = 200, type = 4)
                Kind.LOVE -> repeat(60) { i ->
                    parts += P(rnd(0f, w), h + rnd(10f, h * .5f), rnd(-.4f, .4f), -rnd(2.2f, 4.2f) * d / 2.5f, size = rnd(22f, 40f) * d, emoji = emoji[i % emoji.size], life = 240, sway = rnd(.4f, 1.2f), ph = rnd(0f, 6f))
                }
                else -> {
                    val n = if (kind == Kind.BURST) 26 else 60
                    repeat(n) { i ->
                        parts += P(rnd(w * .1f, w * .9f), h + rnd(10f, 120f), rnd(-2f, 2f), -rnd(7f, 13f) * d / 2.5f, g = .18f * d / 2.5f, size = rnd(26f, 46f) * d,
                            emoji = emoji[i % emoji.size], life = 150, rot = rnd(-.4f, .4f), vr = rnd(-.05f, .05f))
                    }
                    if (kind == Kind.SAD) repeat(n * 2) { parts += P(rnd(0f, w), -rnd(0f, h), 0f, rnd(7f, 11f) * d / 2.5f, size = 14f * d / 2.5f, color = 0xFF8AB4F8.toInt(), life = 160, type = 5) }
                }
            }
        }

        private fun step() {
            if (!spawned && width > 0) { spawn(width.toFloat(), height.toFloat()); spawned = true }
            val it = parts.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.delay > 0) { p.delay--; continue }
                p.age++
                if (p.age > p.life) { it.remove(); continue }
                p.vy += p.g; p.vx *= p.drag; p.vy *= p.drag
                val sx = if (p.sway != 0f) sin(p.age / 30f + p.ph) * p.sway else 0f
                p.x += p.vx + sx; p.y += p.vy; p.rot += p.vr
                if (p.type == 4 && p.y < height * .7f) p.vy *= .97f
            }
            if (spawned && parts.isEmpty()) post { stop() }
        }

        override fun onDraw(canvas: Canvas) {
            for (p in parts) {
                if (p.delay > 0) continue
                val alpha = min(min(1f, p.age / 10f), min(1f, (p.life - p.age) / 30f)).coerceIn(0f, 1f)
                paint.alpha = (alpha * 255).toInt(); text.alpha = paint.alpha
                canvas.save()
                canvas.translate(p.x, p.y)
                canvas.rotate(Math.toDegrees(p.rot.toDouble()).toFloat())
                when {
                    p.emoji != null -> { text.textSize = p.size; canvas.drawText(p.emoji, 0f, p.size / 3, text) }
                    p.type == 1 -> { paint.color = p.color; paint.alpha = (alpha * 255).toInt(); val hh = p.size * 1.5f * abs(cos(p.age / 8f)); canvas.drawRect(-p.size / 2, -hh / 2, p.size / 2, hh / 2, paint) }
                    p.type == 2 || p.type == 3 -> {
                        paint.color = p.color
                        val tw = if (p.type == 3) .55f + .45f * sin(p.age / 8f + p.ph) else 1f
                        paint.alpha = (alpha * tw * 255).toInt()
                        canvas.drawCircle(0f, 0f, p.size, paint)
                    }
                    p.type == 4 -> {
                        val r = maxOf(width, height) * .6f
                        paint.shader = RadialGradient(0f, 0f, r, intArrayOf(0xBFFFD666.toInt(), 0x59FFAA40, 0x00FFAA40), floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP)
                        paint.alpha = (alpha * 255).toInt()
                        canvas.drawCircle(0f, 0f, r, paint)
                        paint.shader = null
                    }
                    p.type == 5 -> { paint.color = p.color; paint.alpha = (alpha * 255).toInt(); paint.strokeWidth = 2 * d; canvas.drawLine(0f, 0f, 0f, p.size, paint) }
                }
                canvas.restore()
            }
        }
    }
}
