package io.github.darkknightsoul.vitrina

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Плитка приложения в духе tvOS: в фокусе она «приподнимается» (увеличение и мягкая тень),
 * слегка наклоняется в сторону, откуда пришёл фокус, по ней пробегает блик, а снизу появляется подпись.
 *
 * Всё, что меняется на каждом кадре, — свойства RenderNode (масштаб, наклон) или пара простых
 * команд рисования без обрезки и без промежуточных слоёв. Тяжёлое (скругление, тень) готовится заранее.
 */
class TvTile(context: Context, private val tileW: Int, private val tileH: Int, private val radius: Float) : View(context) {

    var image: Bitmap? = null
        set(v) { field = v; invalidate() }

    /** Подпись под плиткой: появляется только у плитки в фокусе (как в tvOS). */
    var label: TextView? = null

    /** Действие по кнопке Menu или долгому нажатию OK. */
    var onMenu: (() -> Unit)? = null

    /** Насколько увеличивать плитку в фокусе. */
    private val focusScale = 1.13f

    /** 0 — обычное состояние, 1 — в фокусе. От него зависят масштаб, тень и подпись. */
    private var lift = 0f
    private var liftAnim: ValueAnimator? = null

    // Блик: светлая полоса проходит по плитке один раз при получении фокуса
    private var shinePos = -1f
    private var shineAnim: ValueAnimator? = null
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineMatrix = Matrix()

    // Постоянный «стеклянный» отсвет сверху у плитки в фокусе
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var shadowBmp: Bitmap? = null
    private val shadowPad = TileShadow.pad(context)

    /**
     * Фокус без эффектов (наклона и блика): при пересборке сетки — например, на каждом шаге
     * «Переместить» — плитка просто остаётся приподнятой, а не «прилетает» заново.
     */
    private var quiet = false

    fun focusQuietly() {
        quiet = true
        requestFocus()
        quiet = false
    }

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val shadowDst = Rect()

    init {
        isFocusable = true
        // Приставка стартует в «режиме касания», где обычный фокус программно не ставится:
        // без этого после включения ни одна плитка не выделена до первого нажатия пульта
        isFocusableInTouchMode = true
        isClickable = true
        // Наклон в 3D: камера подальше, чтобы перспектива была мягкой, а не «рыбьим глазом»
        cameraDistance = 12000f * resources.displayMetrics.density
        // Подпись и тень рисуются за пределами плитки — родитель не должен их обрезать
        setOnLongClickListener { onMenu?.invoke(); onMenu != null }

        // Градиенты создаём один раз под размер плитки — на кадре только сдвигаем матрицу
        shinePaint.shader = LinearGradient(
            0f, 0f, tileW * 0.45f, tileH.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, tileH * 0.6f,
            Color.argb(38, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    /** Отрисовка без прозрачных наложений внутри — системе не нужен промежуточный слой для alpha. */
    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Тень под приподнятой плиткой: готовая размытая картинка, растянутая чуть шире и ниже плитки
        if (lift > 0.01f) {
            // Тень и отступ берём из полей — на кадре без поиска в кэше и без новых объектов
            val shadow = shadowBmp ?: TileShadow.get(tileW, tileH, radius, context).also { shadowBmp = it }
            val pad = shadowPad
            val drop = (pad * 0.55f * lift).toInt()
            shadowDst.set(-pad, -pad + drop, width + pad, height + pad + drop)
            shadowPaint.alpha = (lift * 255).toInt()
            canvas.drawBitmap(shadow, null, shadowDst, shadowPaint)
        }
        val img = image
        if (img != null) canvas.drawBitmap(img, null, rectInt(), imgPaint)
        if (lift > 0.01f) {
            glossPaint.alpha = (lift * 255).toInt()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, glossPaint)
        }
        if (shinePos > -1f) {
            // Полоса блика идёт слева направо; скруглённые углы — за счёт drawRoundRect, без clipPath
            shineMatrix.setTranslate(shinePos * w * 1.6f - w * 0.45f, 0f)
            shinePaint.shader.setLocalMatrix(shineMatrix)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, shinePaint)
        }
    }

    private val dstRect = Rect()
    private fun rectInt(): Rect { dstRect.set(0, 0, width, height); return dstRect }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && quiet) {
            // Сразу в приподнятом состоянии, без анимаций
            liftAnim?.cancel()
            setLift(1f)
            return
        }
        animateLift(if (gainFocus) 1f else 0f)
        if (gainFocus) {
            // Фокус пришёл слева — плитка чуть повёрнута левым краем «к нам» и выпрямляется
            tiltFrom(direction)
            startShine()
        } else {
            shineAnim?.cancel(); shinePos = -1f
            animate().rotationX(0f).rotationY(0f).setDuration(150).start()
        }
    }

    /** Масштаб, тень и подпись — одной анимацией 150 мс с замедлением (как в «Доме»). */
    private fun animateLift(target: Float) {
        liftAnim?.cancel()
        liftAnim = ValueAnimator.ofFloat(lift, target).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener { setLift(it.animatedValue as Float) }
            start()
        }
    }

    private fun setLift(v: Float) {
        lift = v
        val s = 1f + (focusScale - 1f) * lift
        scaleX = s; scaleY = s
        label?.let { l ->
            l.alpha = lift
            // Подпись едет вниз вместе с краем увеличенной плитки
            l.translationY = tileH * (focusScale - 1f) / 2f * lift
        }
        // Тень и отсвет — перерисовка только этой плитки (несколько команд)
        invalidate()
    }

    private fun tiltFrom(direction: Int) {
        val deg = 5f
        val (rx, ry) = when (direction) {
            View.FOCUS_RIGHT -> 0f to -deg
            View.FOCUS_LEFT -> 0f to deg
            View.FOCUS_DOWN -> deg to 0f
            View.FOCUS_UP -> -deg to 0f
            else -> 0f to 0f
        }
        rotationX = rx; rotationY = ry
        animate().rotationX(0f).rotationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun startShine() {
        shineAnim?.cancel()
        shineAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            startDelay = 60
            interpolator = DecelerateInterpolator()
            addUpdateListener { shinePos = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { shinePos = -1f; invalidate() }
            })
            start()
        }
    }

    /**
     * «Упёрлись в край»: дальше в эту сторону плиток нет — плитка коротко качается,
     * как в tvOS, вместо того чтобы молча не реагировать.
     */
    fun bump(direction: Int) {
        val deg = 4f
        val (rx, ry) = when (direction) {
            View.FOCUS_RIGHT -> 0f to deg
            View.FOCUS_LEFT -> 0f to -deg
            View.FOCUS_DOWN -> -deg to 0f
            View.FOCUS_UP -> deg to 0f
            else -> return
        }
        animate().cancel()
        animate().rotationX(rx).rotationY(ry).setDuration(90).setInterpolator(DecelerateInterpolator())
            .withEndAction { animate().rotationX(0f).rotationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.5f)).start() }
            .start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && onMenu != null) {
            onMenu?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Общая для всех плиток размытая тень (одна картинка на размер плитки). */
object TileShadow {
    private var key = ""
    private var bmp: Bitmap? = null

    fun pad(context: Context) = context.dp(18)

    fun get(w: Int, h: Int, radius: Float, context: Context): Bitmap {
        val k = "${w}x$h"
        bmp?.let { if (k == key) return it }
        val pad = pad(context)
        // Рисуем в 4 раза меньше и размываем — при растяжении получится мягкая тень
        val q = 4
        val sw = (w + pad * 2) / q
        val sh = (h + pad * 2) / q
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        Canvas(small).drawRoundRect(RectF(pad / q.toFloat(), pad / q.toFloat(), (pad + w) / q.toFloat(), (pad + h) / q.toFloat()),
            radius / q, radius / q, p)
        blurAlpha(small, 2, 3)
        bmp = small
        key = k
        return small
    }

    /** Размытие альфа-канала (тень чёрная, важна только прозрачность). */
    private fun blurAlpha(b: Bitmap, r: Int, passes: Int) {
        val w = b.width; val h = b.height
        val a = IntArray(w * h); b.getPixels(a, 0, w, 0, 0, w, h)
        val t = IntArray(w * h)
        val div = r * 2 + 1
        repeat(passes) {
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0
                for (i in -r..r) s += (a[y * w + (x + i).coerceIn(0, w - 1)] ushr 24)
                t[y * w + x] = (s / div) shl 24
            }
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0
                for (i in -r..r) s += (t[(y + i).coerceIn(0, h - 1) * w + x] ushr 24)
                a[y * w + x] = (s / div) shl 24
            }
        }
        b.setPixels(a, 0, w, 0, 0, w, h)
    }
}

/** Плитка + подпись под ней, одной колонкой. Подпись не занимает фокус. */
class TileCell(context: Context, tileW: Int, tileH: Int, radius: Float) : LinearLayout(context) {
    /** Плитка и подпись не перекрываются — приглушение (alpha) без промежуточного слоя. */
    override fun hasOverlappingRendering() = false

    val tile = TvTile(context, tileW, tileH, radius)
    val label = TextView(context).apply {
        style(14f, Color.WHITE, "sans-serif-medium")
        softShadow()
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
    }

    init {
        orientation = VERTICAL
        noClip()
        addView(tile, LayoutParams(tileW, tileH))
        addView(label, LayoutParams(tileW, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(10) })
        tile.label = label
    }
}
