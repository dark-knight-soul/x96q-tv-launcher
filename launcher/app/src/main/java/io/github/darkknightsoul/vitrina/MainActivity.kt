package io.github.darkknightsoul.vitrina

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран «Витрина» в духе tvOS.
 *
 * Сверху — витрина: обои, часы, погода, «Сейчас играет». Ниже — сетка плиток по 5 в ряд.
 * Уходим вниз — вся колонка плавно уезжает вверх (один сдвиг одного вида), фон притухает.
 * Вверх с первого ряда — стеклянная кнопка, она открывает пункт управления справа.
 */
class MainActivity : Activity() {

    private val columns = 5
    private val side by lazy { dp(56) }
    private val gap by lazy { dp(30) }
    private val rowGap by lazy { dp(20) }
    private val radius by lazy { dp(10).toFloat() }

    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    private val tileW by lazy { (screenW - side * 2 - gap * (columns - 1)) / columns }
    private val tileH by lazy { tileW * 9 / 16 }
    /** Высота ячейки (плитка + подпись) — меряем настоящую ячейку, а не угадываем по шрифту. */
    private val cellH by lazy {
        TileCell(this, tileW, tileH, radius).run {
            label.text = "Ag"
            measure(View.MeasureSpec.makeMeasureSpec(tileW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            measuredHeight
        }
    }
    /** Шаг ряда: ячейка + отступ. */
    private val rowH by lazy { cellH + rowGap }
    /** Высота витрины: первый ряд стоит у нижнего края, подпись под ним ещё видна. */
    private val shelfH by lazy { screenH - dp(34) - cellH }

    /** Готовые ячейки по пакету: при перестановке и возврате из приложения переиспользуем, а не создаём заново. */
    private val cells = HashMap<String, TileCell>()

    private val bgRotateMs = 20 * 60_000L
    private val weatherRefreshMs = 30 * 60_000L

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private val backgrounds = arrayOfNulls<ImageView>(2)
    private var bgFront = 0
    private lateinit var dim: View

    /** Всё, что прокручивается: витрина + ряды плиток. Двигаем только её translationY. */
    private lateinit var column: LinearLayout
    private lateinit var grid: LinearLayout

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var dayInfo: TextView
    private lateinit var weatherIcon: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var corner: GlassButton
    private lateinit var cornerTime: TextView
    private lateinit var netIcon: ImageView
    private lateinit var btIcon: ImageView

    private lateinit var cc: GlassPanel
    private lateinit var ccScrim: View
    private lateinit var ccTime: TextView
    private lateinit var ccDate: TextView
    private lateinit var ccWeather: TextView
    private var ccOpen = false
    private var focusBeforeCc: View? = null

    private val main = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private var builtForLastApp: String? = null
    private var started = false
    private var appsDirty = false
    private var bgDropped = false
    private var bgGen = 0
    private var weatherNow: WeatherInfo? = null
    private var weatherLoading = false
    private var currentRow = 0

    /** Пакет, который сейчас переставляем пультом (режим «Переместить»), или null. */
    private var movingPkg: String? = null

    /** Готовый фон (фото + затемнение). v2 — затемнение по яркости фото; старый файл без него не берём. */
    private val bgFile by lazy { File(filesDir, "home_bg_v2.jpg").also { File(filesDir, "home_bg.jpg").delete() } }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale("ru"))
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }

    /** Сеть, сила сигнала Wi-Fi и Bluetooth — значки в углу меняются только по системным событиям. */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            updateStatus()
            if (i.action == @Suppress("DEPRECATION") android.net.ConnectivityManager.CONNECTIVITY_ACTION && isOnline()) {
                refreshWeather(force = false)
            }
        }
    }

    /** Установка/удаление приложений: пока мы в фоне — только помечаем, перестроим при возврате. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!started) { appsDirty = true; return }
            main.removeCallbacks(reloadAfterPackages)
            main.postDelayed(reloadAfterPackages, 700)
        }
    }
    private val reloadAfterPackages = Runnable { reloadApps(keepFocus = true) }
    private val rotateBackground = Runnable { refreshBackground(force = true) }
    private val weatherTimer = object : Runnable {
        override fun run() {
            refreshWeather(force = true)
            main.postDelayed(this, weatherRefreshMs)
        }
    }
    private val idleTimer = Runnable { startScreensaver() }

    // ---------- Жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildLayout())
        reloadApps(keepFocus = false)
        loadSavedBackground()
        // Прокрутка витрины следует за фокусом, где бы он ни оказался
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        @Suppress("DEPRECATION")
        registerReceiver(statusReceiver, IntentFilter().apply {
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        updateClock()
        updateStatus()
        nowPlaying.start()
        started = true
        if (appsDirty) {
            appsDirty = false
            reloadApps(keepFocus = false)
        } else if (prefs.lastApp != builtForLastApp && apps.isNotEmpty()) {
            // Вернулись из приложения — ставим его первым без перечитывания плиток
            builtForLastApp = prefs.lastApp
            buildGrid(sortApps(apps, prefs), focusPkg = null)
        }
        if (bgDropped) { bgDropped = false; loadSavedBackground(force = true) }
        showWeather(Weather(this).cached())
        refreshWeather(force = false)
        main.postDelayed(weatherTimer, weatherRefreshMs)
        val sinceChange = System.currentTimeMillis() - prefs.bgChangedAt
        main.postDelayed(rotateBackground, maxOf(5_000L, bgRotateMs - sinceChange))
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeReceiver)
        unregisterReceiver(statusReceiver)
        nowPlaying.stop()
        started = false
        main.removeCallbacks(weatherTimer)
        main.removeCallbacks(rotateBackground)
        if (ccOpen) closeControlCenter(animated = false)
        if (movingPkg != null) finishMove()
    }

    override fun onResume() {
        super.onResume()
        resetIdle()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(idleTimer)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
    }

    /** Лаунчер скрыт и памяти мало — отдаём обои (их можно быстро прочитать с диска). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!started && level >= TRIM_MEMORY_BACKGROUND && !bgDropped) {
            bgDropped = true
            bgGen++
            backgrounds.forEach { it?.animate()?.cancel(); it?.setImageDrawable(null); it?.alpha = 0f }
            GlassSource.set(null, screenW, screenH)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(10, 14, 26)))
        }
    }

    /** Home на главном экране — в начало: первая плитка, витрина на месте. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ccOpen) closeControlCenter(animated = true)
        if (movingPkg != null) finishMove()
        firstTile()?.requestFocus()
    }

    @Deprecated("Back на главном экране закрывает панели, иначе ничего не делает")
    override fun onBackPressed() {
        when {
            ccOpen -> closeControlCenter(animated = true)
            currentRow > 0 -> firstTile()?.requestFocus()
        }
    }

    // ---------- Пульт ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetIdle()
        if (event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            if (movingPkg != null) {
                if (handleMoveKey(code)) return true
                // Пока двигаем плитку — никаких панелей и меню поверх
                if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS ||
                    code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_GUIDE) return true
            }
            when (code) {
                // Пункт управления — отдельные кнопки пульта, если они есть
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    if (ccOpen) closeControlCenter(true) else openControlCenter()
                    return true
                }
            }
            val dir = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> 0
            }
            val f = currentFocus
            if (dir != 0 && f is TvTile && f.focusSearch(dir) == null) {
                // Дальше в эту сторону ничего нет — плитка качнётся, как в tvOS
                f.bump(dir)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Разметка ----------

    /** Вид-заливка без перекрытий внутри — его alpha рисуется сразу, без offscreen-слоя. */
    private fun flatView() = object : View(this) { override fun hasOverlappingRendering() = false }

    private fun buildLayout(): View {
        root = FrameLayout(this)
        for (i in 0..1) {
            backgrounds[i] = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
            root.addView(backgrounds[i], FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Затемнение фона, когда витрина уехала и в фокусе нижние ряды
        // Сплошная заливка: прозрачность без промежуточного слоя размером с экран на каждом кадре
        dim = flatView().apply { setBackgroundColor(Color.argb(150, 0, 0, 0)); alpha = 0f }
        root.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.noClip()
        column.addView(buildShelf(), LinearLayout.LayoutParams(MATCH_PARENT, shelfH))
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, 0)
        }.noClip()
        column.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // Колонка выше экрана: обычный FrameLayout ограничил бы её высотой экрана
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.noClip()

        ccScrim = flatView().apply { setBackgroundColor(Color.argb(110, 0, 0, 0)); alpha = 0f; visibility = View.GONE }
        root.addView(ccScrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cc = buildControlCenter()
        root.addView(cc, FrameLayout.LayoutParams(dp(330), MATCH_PARENT, Gravity.END).apply {
            setMargins(0, dp(20), dp(20), dp(20))
        })
        return root
    }

    /** Витрина: слева часы и дата, справа погода, «Сейчас играет» и стеклянная кнопка. */
    private fun buildShelf(): View {
        clock = TextView(this).apply { style(84f, Color.WHITE, "sans-serif-medium"); softShadow() }
        date = TextView(this).apply { style(24f, Color.WHITE, "sans-serif-medium"); softShadow() }
        dayInfo = TextView(this).apply { style(15f, white(0.92f)); softShadow() }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(dayInfo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            // Часы и дата меняются раз в минуту — держим их готовой текстурой.
            // Текстура размером с блок: запас по краям, чтобы мягкая тень текста не обрезалась
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        cornerTime = TextView(this).apply { style(15f, Color.WHITE, "sans-serif-medium") }
        netIcon = ImageView(this)
        btIcon = ImageView(this).apply { setImageResource(R.drawable.ic_bluetooth); visibility = View.GONE }
        corner = GlassButton(this, dp(20).toFloat()).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(14), 0)
                // Статус: сеть и Bluetooth, затем время и шестерёнка пункта управления
                addView(netIcon, LinearLayout.LayoutParams(dp(17), dp(17)))
                addView(btIcon, LinearLayout.LayoutParams(dp(17), dp(17)).apply { leftMargin = dp(8) })
                addView(View(this@MainActivity).apply { setBackgroundColor(white(0.3f)) },
                    LinearLayout.LayoutParams(dp(1), dp(16)).apply { leftMargin = dp(12); rightMargin = dp(12) })
                addView(cornerTime)
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_settings) },
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(10) })
            }, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            setOnClickListener { openControlCenter() }
        }

        weatherIcon = TextView(this).apply { style(36f, Color.WHITE) }
        weatherTemp = TextView(this).apply { style(48f, Color.WHITE); softShadow() }
        weatherDesc = TextView(this).apply { style(16f, white(0.9f)); softShadow(); gravity = Gravity.END }
        nowPlaying = NowPlayingView(this)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(corner, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(weatherIcon)
                addView(weatherTemp, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(weatherDesc, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(nowPlaying, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }.noClip()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(side, dp(30), side, 0)
            // Отрицательные отступы компенсируют запас под тень — текст стоит там же, где и был
            addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = -dp(8); topMargin = -dp(8) })
            addView(right, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }.noClip()
    }

    // ---------- Сетка плиток ----------

    private fun reloadApps(keepFocus: Boolean) {
        builtForLastApp = prefs.lastApp
        val focusPkg = if (keepFocus) (currentFocus as? TvTile)?.tag as? String else null
        io.safe {
            val list = loadApps(this, prefs, tileW, tileH, radius)
            main.post {
                apps = list
                buildGrid(list, focusPkg)
            }
        }
    }

    /** Запасная плитка для приложения, чью картинку не удалось нарисовать (одна на всех). */
    private val fallbackTile by lazy { renderIconTile(getDrawable(R.drawable.ic_launcher)!!, tileW, tileH, radius) }

    /**
     * Раскладывает плитки в заданном порядке. Готовые ячейки переиспользуются — новые создаются
     * только для новых приложений, поэтому перестановка и возврат из приложения почти ничего не стоят.
     *
     * [focusPkg] — какую плитку выделить (null — ту, что была в фокусе, иначе первую);
     * [quiet] — выделить без наклона и блика (шаги режима «Переместить»).
     */
    private fun buildGrid(list: List<AppEntry>, focusPkg: String?, quiet: Boolean = false) {
        apps = list
        // При первом построении фокус стоит там, куда его поставила система (угловая кнопка), — это не выбор пользователя
        val firstBuild = grid.childCount == 0
        val hadFocus = currentFocus
        val keepPkg = focusPkg ?: (hadFocus as? TvTile)?.tag as? String
        // Пока ячейки вынуты, фокусу некуда деться — без этого он «прыгает» на угловую кнопку
        // и дёргает прокрутку витрины туда-обратно
        column.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        for (r in 0 until grid.childCount) (grid.getChildAt(r) as ViewGroup).removeAllViews()
        grid.removeAllViews()

        val alive = HashSet<String>()
        var row: LinearLayout? = null
        var toFocus: TvTile? = null
        list.forEachIndexed { i, app ->
            if (i % columns == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.noClip()
                grid.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (i > 0) topMargin = rowGap
                })
            }
            val cell = cells.getOrPut(app.pkg) { TileCell(this, tileW, tileH, radius) }
            alive += app.pkg
            cell.label.text = app.label
            cell.tile.apply {
                tag = app.pkg
                image = app.banner ?: fallbackTile
                setOnClickListener { if (movingPkg == null) launch(app) }
                onMenu = { appMenu(app) }
            }
            row!!.addView(cell, LinearLayout.LayoutParams(tileW, WRAP_CONTENT).apply { if (i % columns > 0) leftMargin = gap })
            if (app.pkg == keepPkg) toFocus = cell.tile
        }
        // Ячейки удалённых и скрытых приложений больше не держим
        cells.keys.retainAll(alive)

        // Колонка выше экрана: задаём высоту явно, иначе родитель ужмёт её до экрана и ряды наедут друг на друга
        val rows = (list.size + columns - 1) / columns
        column.layoutParams = column.layoutParams.apply { height = shelfH + rows * rowH + screenH / 2 }

        if (ccOpen) return   // фокус вернётся при закрытии панели
        column.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        when {
            toFocus != null -> if (quiet) toFocus!!.focusQuietly() else toFocus!!.requestFocus()
            // Фокус был не на плитке (угловая кнопка) — возвращаем туда же
            !firstBuild && hadFocus != null && hadFocus !is TvTile -> hadFocus.requestFocus()
            else -> firstTile()?.requestFocus()
        }
    }

    private fun firstTile(): View? = ((grid.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? TileCell)?.tile

    /** Номер ряда для вида в фокусе: -1 — витрина (стеклянная кнопка и т.п.). */
    private fun rowOf(v: View?): Int {
        var p: View? = v
        while (p != null) {
            val parent = p.parent as? View ?: return -1
            if (parent === grid) return grid.indexOfChild(p)
            p = parent
        }
        return -1
    }

    /**
     * Фокус переехал — сдвигаем колонку: первый ряд и витрина на своих местах,
     * ниже — витрина уходит вверх, а ряд в фокусе встаёт под верхний край экрана.
     */
    private fun onFocusMoved(now: View?) {
        if (now == null || ccOpen) return
        val row = rowOf(now).coerceAtLeast(0)
        if (row == currentRow) return
        currentRow = row
        val target = if (row == 0) 0f else -(shelfH - dp(64) + (row - 1) * rowH).toFloat()
        column.animate().translationY(target).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
        dim.animate().alpha(if (row == 0) 0f else 1f).setDuration(320).start()
    }

    private fun launch(app: AppEntry) {
        try {
            startActivity(app.launch)
            prefs.lastApp = app.pkg
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть «${app.label}»", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appMenu(app: AppEntry) {
        val actions = mutableListOf("Переместить", "Поставить первым", "Скрыть с главного экрана")
        if (!app.isSystem) actions += "Удалить приложение"
        dialog(app.label, actions) { which ->
            when (actions[which]) {
                "Переместить" -> startMove(app.pkg)
                "Поставить первым" -> { prefs.moveFirst(app.pkg); buildGrid(sortApps(apps, prefs), app.pkg) }
                "Скрыть с главного экрана" -> {
                    prefs.hide(app.pkg)
                    reloadApps(keepFocus = false)
                    Toast.makeText(this, "Вернуть: пункт управления → Оформление", Toast.LENGTH_LONG).show()
                }
                "Удалить приложение" -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            }
        }
    }

    // ---------- Режим «Переместить» ----------

    private fun startMove(pkg: String) {
        movingPkg = pkg
        Toast.makeText(this, "Стрелками двигайте плитку, OK или «Назад» — готово", Toast.LENGTH_LONG).show()
        markMoving()
    }

    /** Переставляемая плитка заметно приподнята, остальные приглушены. */
    private fun markMoving() {
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as ViewGroup
            for (c in 0 until row.childCount) {
                val cell = row.getChildAt(c) as TileCell
                cell.alpha = if (movingPkg == null || cell.tile.tag == movingPkg) 1f else 0.45f
            }
        }
    }

    private fun handleMoveKey(code: Int): Boolean {
        val pkg = movingPkg ?: return false
        val i = apps.indexOfFirst { it.pkg == pkg }
        if (i < 0) { finishMove(); return false }
        val to = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> i - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> i + 1
            KeyEvent.KEYCODE_DPAD_UP -> i - columns
            KeyEvent.KEYCODE_DPAD_DOWN -> i + columns
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { finishMove(); return true }
            else -> return false
        }
        if (to !in apps.indices) {
            (currentFocus as? TvTile)?.bump(when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                else -> View.FOCUS_DOWN
            })
            return true
        }
        val list = apps.toMutableList()
        list.add(to, list.removeAt(i))
        buildGrid(list, pkg, quiet = true)
        markMoving()
        return true
    }

    private fun finishMove() {
        movingPkg = null
        // Сохраняем порядок целиком; «последнее открытое» больше не перебивает ручную расстановку
        prefs.saveOrder(apps.map { it.pkg })
        prefs.lastApp = null
        builtForLastApp = null
        markMoving()
    }

    // ---------- Пункт управления ----------

    private fun buildControlCenter(): GlassPanel {
        ccTime = TextView(this).apply { style(44f, Color.WHITE, "sans-serif-medium") }
        ccDate = TextView(this).apply { style(16f, white(0.85f)) }
        ccWeather = TextView(this).apply { style(15f, white(0.85f)) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            addView(ccTime)
            addView(ccDate, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(ccWeather, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(14) })
        }.noClip()
        val items = listOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_play, "Заставка") { closeControlCenter(false); startScreensaver() },
            Triple(R.drawable.ic_wifi, "Wi-Fi") { openSystem(Settings.ACTION_WIFI_SETTINGS) },
            Triple(R.drawable.ic_bluetooth, "Bluetooth") { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) },
            Triple(R.drawable.ic_tv, "Экран и звук") { openSystem(Settings.ACTION_DISPLAY_SETTINGS) },
            Triple(R.drawable.ic_palette, "Оформление") { closeControlCenter(false); appearanceMenu() },
            Triple(R.drawable.ic_settings, "Все настройки") { openSystem(Settings.ACTION_SETTINGS) },
        )
        for ((icon, text, action) in items) {
            val btn = Card(this, dp(12).toFloat()).apply {
                focusScale = 1.04f
                setSolid(white(0.10f))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    addView(ImageView(this@MainActivity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    addView(TextView(this@MainActivity).apply { style(16f, Color.WHITE, "sans-serif-medium"); this.text = text },
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(14) })
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                setOnClickListener { action() }
            }
            list.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        }
        return GlassPanel(this, dp(22).toFloat()).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            visibility = View.GONE
            noClip()
        }
    }

    private fun openControlCenter() {
        if (ccOpen) return
        ccOpen = true
        focusBeforeCc = currentFocus
        updateClock()
        // Пока панель открыта, фокус не должен уходить в сетку за ней
        column.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        ccScrim.visibility = View.VISIBLE
        ccScrim.animate().alpha(1f).setDuration(220).start()
        cc.visibility = View.VISIBLE
        cc.translationX = dp(360).toFloat()
        cc.animate().translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.8f)).start()
        // Первая кнопка списка (до неё — время, дата и погода)
        cc.post { (cc.getChildAt(0) as ViewGroup).getChildAt(3)?.requestFocus() }
    }

    private fun closeControlCenter(animated: Boolean) {
        if (!ccOpen) return
        ccOpen = false
        column.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val done = {
            cc.visibility = View.GONE
            ccScrim.visibility = View.GONE
        }
        if (animated) {
            ccScrim.animate().alpha(0f).setDuration(200).start()
            cc.animate().translationX(dp(360).toFloat()).setDuration(200).setInterpolator(DecelerateInterpolator())
                .withEndAction { done() }.start()
        } else {
            cc.animate().cancel(); ccScrim.animate().cancel()
            ccScrim.alpha = 0f
            done()
        }
        // Плитку, с которой открывали панель, могли за это время убрать (удалили приложение)
        val back = focusBeforeCc?.takeIf { it.isAttachedToWindow }
        focusBeforeCc = null
        (back ?: firstTile())?.requestFocus()
    }

    private fun openSystem(action: String) {
        closeControlCenter(false)
        for (intent in listOf(Intent(action), Intent(Settings.ACTION_SETTINGS))) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Пробуем общий экран настроек
            }
        }
        Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
    }

    // ---------- Часы и погода ----------

    private fun updateClock() {
        // Форматы запоминают пояс при создании — после смены пояса в системе часы бы врали
        val tz = java.util.TimeZone.getDefault()
        clockFormat.timeZone = tz
        dateFormat.timeZone = tz
        val now = Date()
        val t = clockFormat.format(now)
        val d = dateFormat.format(now).replaceFirstChar { it.uppercase() }
        clock.text = t
        date.text = d
        cornerTime.text = t
        ccTime.text = t; ccDate.text = d
        updateDayInfo(weatherNow)
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean =
        (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetworkInfo?.isConnected == true

    /** Значки в углу: кабель / Wi-Fi (яркость по силе сигнала) / нет сети; Bluetooth — только когда включён. */
    @Suppress("DEPRECATION")
    private fun updateStatus() {
        val info = (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetworkInfo
        val (icon, alpha) = when {
            info == null || !info.isConnected -> R.drawable.ic_wifi to 0.3f
            info.type == android.net.ConnectivityManager.TYPE_ETHERNET -> R.drawable.ic_ethernet to 1f
            else -> {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val level = android.net.wifi.WifiManager.calculateSignalLevel(wifi.connectionInfo?.rssi ?: -100, 4)
                R.drawable.ic_wifi to 0.45f + 0.55f * level / 3f
            }
        }
        // Сила сигнала приходит каждые несколько секунд — значок трогаем, только если что-то изменилось
        if (icon != shownNetIcon) { shownNetIcon = icon; netIcon.setImageResource(icon) }
        if (netIcon.alpha != alpha) netIcon.alpha = alpha
        val bt = try { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (e: Exception) { false }
        val btVis = if (bt) View.VISIBLE else View.GONE
        if (btIcon.visibility != btVis) btIcon.visibility = btVis
    }

    private var shownNetIcon = 0

    private fun updateDayInfo(w: WeatherInfo?) {
        val moon = moonPhase()
        val sunset = w?.let { "🌇  Закат ${it.sunset}     " }.orEmpty()
        dayInfo.text = "$sunset${moon.emoji}  ${moon.name} ${moon.percent}%"
    }

    private fun refreshWeather(force: Boolean) {
        val weather = Weather(this)
        if (weatherLoading || (!force && !weather.stale())) return
        weatherLoading = true
        net.safe {
            var w: WeatherInfo? = null
            try {
                w = weather.refresh()
            } finally {
                // Флаг снимаем при любом исходе — иначе после одной ошибки погода больше не обновится
                main.post {
                    weatherLoading = false
                    if (w != null) showWeather(w)
                }
            }
        }
    }

    private fun showWeather(w: WeatherInfo?) {
        weatherNow = w
        if (w == null) {
            weatherIcon.text = ""; weatherTemp.text = ""; weatherDesc.text = ""; ccWeather.text = ""
            return
        }
        weatherIcon.text = w.emoji
        weatherTemp.text = w.tempText
        weatherDesc.text = "${w.city} · ${w.description}"
        ccWeather.text = "${w.emoji}  ${w.tempText} · ${w.city}, днём ${signed(w.max)}, ночью ${signed(w.min)}"
        updateDayInfo(w)
    }

    // ---------- Обои ----------

    private fun loadSavedBackground(force: Boolean = false) {
        wallNet.safe {
            val bmp = if (bgFile.exists()) BitmapFactory.decodeFile(bgFile.path) else null
            val shown = bmp ?: renderAurora(screenW, screenH)
            val glass = GlassSource.prepare(shown)
            val gpu = toGpu(shown)
            main.post {
                if (force || bgGen == 0) showBackground(gpu, glass)
                // Готового фона нет (первый запуск или новая версия) — не ждём 20 минут до смены, качаем сразу
                if (bmp == null) refreshBackground(force = true)
            }
        }
    }

    private fun refreshBackground(force: Boolean) {
        main.removeCallbacks(rotateBackground)
        main.postDelayed(rotateBackground, bgRotateMs)
        if (!force) return
        val category = prefs.category
        wallNet.safe {
            val wp = WallpaperStore.get(this).next(category, screenW, screenH) ?: return@safe
            val composed = composeVitrinaBackground(wp.bitmap, screenW, screenH)
            wp.bitmap.recycle()
            val glass = GlassSource.prepare(composed)
            // Целиком или никак: обрезанный JPEG после пропажи питания не нужен
            writeAtomically(bgFile) { composed.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val gpu = toGpu(composed)
            main.post {
                prefs.bgChangedAt = System.currentTimeMillis()
                if (!started) { bgDropped = true; gpu.recycle(); return@post }
                showBackground(gpu, glass)
            }
        }
    }

    /** Плавная смена обоев: новая картинка проявляется поверх старой. */
    private fun showBackground(bmp: Bitmap, glass: Bitmap) {
        val gen = ++bgGen
        val incoming = backgrounds[1 - bgFront]!!
        val outgoing = backgrounds[bgFront]!!
        bgFront = 1 - bgFront
        incoming.animate().cancel()
        outgoing.animate().cancel()
        outgoing.alpha = 1f
        root.removeView(incoming)
        root.addView(incoming, 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(1200).withEndAction {
            if (gen != bgGen) return@withEndAction
            outgoing.setImageDrawable(null)
            outgoing.alpha = 0f
            window.setBackgroundDrawable(null)
            // Стекло — по новым обоям, когда они уже проявились (иначе 1,2 с стекло не совпадает с фоном)
            GlassSource.set(glass, screenW, screenH)
        }.start()
    }

    /**
     * Обои в памяти видеочипа (HARDWARE): картинка 1280×720 не держится дважды — в памяти процесса
     * и в текстуре. Минус ~3,7 МБ. Дальше её только показываем, программно не рисуем.
     */
    private fun toGpu(b: Bitmap): Bitmap {
        val hw = try { b.copy(Bitmap.Config.HARDWARE, false) } catch (e: Exception) { null } ?: return b
        b.recycle()
        return hw
    }

    // ---------- Заставка и оформление ----------

    private fun resetIdle() {
        main.removeCallbacks(idleTimer)
        val minutes = prefs.idleMinutes
        if (minutes > 0) main.postDelayed(idleTimer, minutes * 60_000L)
    }

    private fun startScreensaver() {
        startActivity(Intent(this, ScreensaverActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun appearanceMenu() {
        val idleText = prefs.idleMinutes.let { if (it == 0) "выключена" else "через $it мин" }
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Обои: ${prefs.category.title}" to { chooseCategory() },
            "Заставка: $idleText" to { chooseIdle() },
            "Ночной режим заставки (23:00–6:00): ${if (prefs.nightMode) "вкл" else "выкл"}" to { prefs.nightMode = !prefs.nightMode },
            "Сменить обои сейчас" to { refreshBackground(force = true) },
            "Сбросить порядок плиток" to { prefs.saveOrder(emptyList()); reloadApps(keepFocus = false) },
        )
        val hidden = prefs.hidden.size
        if (hidden > 0) actions += "Показать скрытые приложения ($hidden)" to { prefs.unhideAll(); reloadApps(keepFocus = false) }
        dialog("Оформление", actions.map { it.first }) { which -> actions[which].second() }
    }

    private fun chooseCategory() {
        val all = Category.values()
        dialog("Обои", all.map { (if (it == prefs.category) "✓  " else "     ") + it.title }) { which ->
            prefs.category = all[which]
            refreshBackground(force = true)
        }
    }

    private fun chooseIdle() {
        val options = listOf(2, 5, 10, 15, 30, 0)
        dialog("Заставка", options.map {
            (if (it == prefs.idleMinutes) "✓  " else "     ") + if (it == 0) "Выключена" else "Через $it мин"
        }) { which ->
            prefs.idleMinutes = options[which]
            resetIdle()
        }
    }

    private fun dialog(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, R.style.Theme_Home_Dialog)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setOnKeyListener { _, _, _ -> resetIdle(); false }
            .setOnDismissListener { resetIdle() }
            .show()
    }
}

/**
 * Фон «Витрины»: фото по размеру экрана и затемнение только там, где лежит текст, —
 * мягкий овал под левым блоком (часы, дата), овал под погодой справа и переход книзу под плитки.
 * Сила затемнения зависит от того, насколько светлое фото в этом месте: тёмные обои остаются
 * яркими и сочными, а на снегу, облаках или небе текст всё равно читается.
 * Всё запекается в одну картинку — на экране одна текстура, на кадр это ничего не стоит.
 */
fun composeVitrinaBackground(photo: Bitmap, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val scale = maxOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
    val cw = (w / scale).toInt(); val ch = (h / scale).toInt()
    val sx = (photo.width - cw) / 2; val sy = (photo.height - ch) / 2
    canvas.drawBitmap(photo, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))

    // Яркость под текстом — по уменьшенной копии 64×36 (доли миллисекунды)
    val small = Bitmap.createScaledBitmap(out, 64, 36, true)
    val leftLum = zoneBrightness(small, 0f, 0f, 0.5f, 0.4f)
    val rightLum = zoneBrightness(small, 0.72f, 0f, 1f, 0.35f)
    small.recycle()

    canvas.drawColor(Color.argb(20, 0, 0, 0))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Низ — под плитки и подписи
    paint.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(160, 0, 0, 0), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    // Левый блок: часы, дата и строка с закатом и луной
    shadeOval(canvas, paint, cx = w * 0.16f, cy = h * 0.17f, rx = w * 0.52f, ry = h * 0.38f, alpha = shadeAlpha(leftLum))
    // Погода справа
    shadeOval(canvas, paint, cx = w * 0.92f, cy = h * 0.14f, rx = w * 0.32f, ry = h * 0.30f, alpha = shadeAlpha(rightLum))
    return out
}

/** Насколько затемнять: тёмное место — чуть-чуть (~30 %), очень светлое — до ~78 %. */
private fun shadeAlpha(brightness: Float): Int {
    val k = ((brightness - 0.30f) / 0.45f).coerceIn(0f, 1f)
    return (80 + 120 * k).toInt()
}

/** Мягкий тёмный овал: в центре [alpha], к краю плавно сходит на нет. */
private fun shadeOval(canvas: Canvas, paint: Paint, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Int) {
    val g = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.argb(alpha, 0, 0, 0), Color.argb(alpha * 3 / 4, 0, 0, 0), Color.TRANSPARENT),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    // Круг радиуса 1 растягиваем в овал нужного размера
    g.setLocalMatrix(android.graphics.Matrix().apply { setScale(rx, ry); postTranslate(cx, cy) })
    paint.shader = g
    canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
}

/**
 * Яркость зоны (0..1): среднее, смещённое к самым светлым точкам — белая полоса облаков
 * под текстом мешает так же, как полностью светлый фон.
 */
private fun zoneBrightness(b: Bitmap, l: Float, t: Float, r: Float, bottom: Float): Float {
    val x0 = (l * b.width).toInt(); val x1 = (r * b.width).toInt().coerceAtMost(b.width)
    val y0 = (t * b.height).toInt(); val y1 = (bottom * b.height).toInt().coerceAtMost(b.height)
    val values = ArrayList<Float>((x1 - x0) * (y1 - y0))
    for (y in y0 until y1) for (x in x0 until x1) {
        val p = b.getPixel(x, y)
        values += (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
    }
    if (values.isEmpty()) return 0f
    values.sort()
    val mean = values.average().toFloat()
    val p90 = values[(values.size * 0.9f).toInt().coerceAtMost(values.size - 1)]
    return 0.6f * mean + 0.4f * p90
}
