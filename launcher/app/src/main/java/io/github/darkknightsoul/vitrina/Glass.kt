package io.github.darkknightsoul.vitrina

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.widget.FrameLayout

/**
 * «Матовое стекло» без RenderEffect (его нет до Android 12) и без размытия на каждом кадре.
 *
 * Обои один раз уменьшаются в ~10 раз и размываются (это ~0,1 с в фоне при смене обоев).
 * Панель рисует этот маленький кусочек растянутым под себя — растяжение с фильтрацией само
 * даёт мягкость, а видеочипу это одна текстура, как обычная картинка.
 */
object GlassSource {
    /** Маленькая размытая копия текущих обоев. null — обоев ещё нет, панели рисуются полупрозрачными. */
    @Volatile var blurred: Bitmap? = null
        private set

    /** Размер экрана, под который сделан [blurred] (чтобы правильно совместить с обоями). */
    @Volatile var screenW = 1
        private set
    @Volatile var screenH = 1
        private set

    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Вызывать в фоновом потоке; [notify] — в основном (после установки новых обоев). */
    fun prepare(background: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(background, background.width / 10, background.height / 10, true)
        boxBlur(small, radius = 3, passes = 3)
        return small
    }

    fun set(bmp: Bitmap?, w: Int, h: Int) {
        blurred = bmp
        screenW = w
        screenH = h
        listeners.toList().forEach { it() }
    }

    /**
     * Размытие «коробочкой» в несколько проходов (≈ гауссово). На картинке 128×72 — доли миллисекунды,
     * поэтому без RenderScript и сторонних библиотек.
     */
    fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
        val w = bmp.width
        val h = bmp.height
        val src = IntArray(w * h)
        val tmp = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurLine(src, tmp, w, h, radius, horizontal = true)
            blurLine(tmp, src, w, h, radius, horizontal = false)
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h)
    }

    private fun blurLine(input: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = r * 2 + 1
        for (line in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) input[line * w + c] else input[c * w + line]
            }
            for (i in -r..r) { val p = at(i); sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                out[idx] = Color.rgb(sr / div, sg / div, sb / div)
                val add = at(i + r + 1); val sub = at(i - r)
                sr += ((add shr 16) and 0xff) - ((sub shr 16) and 0xff)
                sg += ((add shr 8) and 0xff) - ((sub shr 8) and 0xff)
                sb += (add and 0xff) - (sub and 0xff)
            }
        }
    }
}

/**
 * Панель с матовым стеклом: под ней — размытые обои ровно с того места экрана, где она стоит,
 * сверху лёгкая белая дымка и тонкая светлая кромка, как в tvOS.
 */
open class GlassPanel(context: Context, private val radius: Float) : FrameLayout(context) {

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 28, 30, 40) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = Color.argb(60, 255, 255, 255)
    }
    private val rect = RectF()
    private val matrix = Matrix()
    private val loc = IntArray(2)

    private val onChange: () -> Unit = { invalidate() }

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GlassSource.addListener(onChange)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        GlassSource.removeListener(onChange)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val bmp = GlassSource.blurred
        if (bmp == null) {
            canvas.drawRoundRect(rect, radius, radius, fallback)
        } else {
            // Кусок размытых обоев под панелью: сдвигаем «окно» на положение панели на экране.
            // Шейдер с картинкой + drawRoundRect — скруглённые углы без дорогой обрезки (clipPath).
            layoutPosition(loc)
            val sx = GlassSource.screenW.toFloat() / bmp.width
            val sy = GlassSource.screenH.toFloat() / bmp.height
            matrix.setScale(sx, sy)
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            val s = bmpPaint.shader as? BitmapShader
            val sh = if (s != null && lastBmp === bmp) s
            else BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { lastBmp = bmp; bmpPaint.shader = it }
            sh.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, radius, radius, bmpPaint)
            canvas.drawRoundRect(rect, radius, radius, tint)
        }
        val half = edge.strokeWidth / 2
        rect.inset(half, half)
        canvas.drawRoundRect(rect, radius, radius, edge)
    }

    private var lastBmp: Bitmap? = null

    /**
     * Место панели на экране по раскладке, без учёта сдвигов-анимаций (translation).
     * Сдвиги меняются на каждом кадре без перерисовки панели — фон размыт, поэтому
     * небольшое расхождение во время движения незаметно, а в покое всё совпадает.
     */
    private fun layoutPosition(out: IntArray) {
        var x = 0
        var y = 0
        var v: android.view.View = this
        while (true) {
            x += v.left; y += v.top
            v = v.parent as? android.view.View ?: break
        }
        out[0] = x; out[1] = y
    }
}

/** Стеклянная кнопка-«таблетка»: увеличивается в фокусе и получает светлую рамку. */
class GlassButton(context: Context, private val r: Float) : GlassPanel(context, r) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = Color.WHITE
    }
    private val box = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) 1.1f else 1f
        invalidate()
        animate().scaleX(s).scaleY(s).setDuration(150).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        if (isFocused) {
            val half = ring.strokeWidth / 2
            box.set(half, half, width - half, height - half)
            canvas.drawRoundRect(box, r, r, ring)
        }
    }
}
