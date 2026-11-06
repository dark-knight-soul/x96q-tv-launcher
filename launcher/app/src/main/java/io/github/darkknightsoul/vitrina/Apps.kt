package io.github.darkknightsoul.vitrina

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.io.File
import java.text.Collator
import java.util.Locale

/** Приложение на главном экране. */
class AppEntry(
    val pkg: String,
    val label: String,
    val launch: Intent,
    /** Готовая картинка плитки (баннер или значок на цветном фоне) точно под её размер; null — не удалось нарисовать. */
    val banner: Bitmap?,
    val isSystem: Boolean,
)

/** Служебные пакеты, которые никогда не показываем на главном экране. */
private val EXCLUDED = setOf(
    // Старый лаунчер прошивки и служебные экраны
    "com.droidlogic.xlauncher",
    "com.android.shell",
    "com.android.traceur",
    "com.android.camera2",
    "com.yxt.memoryclean",
    "com.yxt.factorytest",
    "com.softwinner.dragonbox",
    "com.softwinner.agingdragonbox",
    // Рекламное ПО из прошивки (отключено при чистке 06.11.2026) — на случай, если вернётся
    "com.androidkm.store",
    "com.easycontrol.mdm",
    "com.abupdate.fota_demo_iot",
    "com.yxt.appstore",
    "com.vv.browser",
    // Бесполезные на телевизоре плитки
    "com.android.documentsui",
    "com.google.android.voicesearch",
    "com.google.android.googlequicksearchbox",
)

/** Порядок по умолчанию: сначала кино и ТВ, в конце служебные приложения. */
private val DEFAULT_RANK = listOf(
    "com.google.android.youtube.tv",
    "ru.kinopoisk.tv",
    "com.netflix.mediaclient",
    "ru.rutube.app",
    "com.disney.disneyplus",
    "com.amazon.amazonvideo.livingroom",
    "mobi.zona.tvlite",
    "com.spotify.tv.android",
    "org.xbmc.kodi",
    "com.yyt.zapptv",
    "com.metaxtv.amedia",
)
private val UTILITIES = listOf(
    "com.android.tv.settings",
    "com.lonelycatgames.Xplore",
    "cm.aptoidetv.pt",
    "com.softwinner.TvdFileManager",
    "com.softwinner.miracastReceiver",
    "com.android.vending",
    "com.android.chrome",
    "com.softwinner.update",
)

private fun defaultRank(pkg: String): Int {
    DEFAULT_RANK.indexOf(pkg).let { if (it >= 0) return it }
    UTILITIES.indexOf(pkg).let { if (it >= 0) return 2000 + it }
    return 1000
}

/**
 * Собирает список приложений: сначала ТВ-ярлыки (с баннерами), потом обычные.
 * Баннеры сразу готовятся под размер плитки [tileW]×[tileH] со скруглением [radius].
 */
fun loadApps(context: Context, prefs: Prefs, tileW: Int, tileH: Int, radius: Float): List<AppEntry> {
    val pm = context.packageManager
    val found = LinkedHashMap<String, AppEntry>()
    val hidden = prefs.hidden
    val tilesDir = File(context.cacheDir, "tiles").apply { mkdirs() }
    val usedTiles = HashSet<String>()
    for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(category)
        for (ri in pm.queryIntentActivities(query, 0)) {
            val ai = ri.activityInfo
            val pkg = ai.packageName
            if (pkg in found || pkg in EXCLUDED || pkg == context.packageName || pkg in hidden) continue
            // Одно «битое» приложение (ресурсы, подпись) не должно оставлять пустой весь экран
            try {
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                // v2 — плитка всегда готовая картинка: баннер или значок на цветном фоне
                val key = "v2_${pkg}_${File(ai.applicationInfo.sourceDir).lastModified()}_${tileW}x$tileH"
                usedTiles += key
                val tile = try {
                    cachedTile(tilesDir, key) {
                        val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                        if (banner != null) renderRounded(banner, tileW, tileH, radius)
                        else renderIconTile(ai.loadIcon(pm), tileW, tileH, radius)
                    }
                } catch (e: Exception) {
                    null
                }
                found[pkg] = AppEntry(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString().trim(),
                    launch = launch,
                    banner = tile,
                    isSystem = ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            } catch (e: Exception) {
                continue
            }
        }
    }
    cleanTileCache(tilesDir, usedTiles)
    return sortApps(found.values.toList(), prefs)
}

/**
 * Порядок: последнее открытое, закреплённые пользователем, порядок по умолчанию, по алфавиту.
 * Если пользователь расставил плитки сам («Переместить»), «последнее открытое» его не перебивает.
 */
fun sortApps(list: List<AppEntry>, prefs: Prefs): List<AppEntry> {
    val collator = Collator.getInstance(Locale("ru"))
    val order = prefs.order
    val last = if (order.isEmpty()) prefs.lastApp else null
    return list.sortedWith(
        compareBy<AppEntry> { if (it.pkg == last) 0 else 1 }
            .thenBy { order.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { defaultRank(it.pkg) }
            .thenComparator { a, b -> collator.compare(a.label, b.label) }
    )
}

/**
 * Готовая плитка из кэша на диске или, если её нет, — отрисовка из APK и сохранение.
 * Ключ — пакет + время изменения его APK + размер плитки: обновилось приложение — плитка перерисуется.
 * Из кэша PNG ~190×110 читается за 2–3 мс вместо открытия ресурсов чужого APK (десятки мс).
 * Пишем через временный файл: если питание пропадёт посреди записи, битый PNG в кэше не останется.
 */
private fun cachedTile(dir: File, key: String, render: () -> Bitmap): Bitmap {
    val f = File(dir, "$key.png")
    if (f.length() > 0) BitmapFactory.decodeFile(f.path)?.let { return it }
    // Файла нет, он пустой или не читается — перерисовываем
    f.delete()
    val bmp = render()
    writeAtomically(f) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return bmp
}

/** Запись файла целиком или никак: сначала во временный, потом переименование. */
fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
    val tmp = File(target.parentFile, target.name + ".part")
    try {
        tmp.outputStream().use(write)
        if (!tmp.renameTo(target)) tmp.delete()
    } catch (e: Exception) {
        // Кэш не записался — не страшно, в следующий раз отрисуем снова
        tmp.delete()
    }
}

/** Удаляем плитки приложений, которых больше нет (или которые обновились). */
private fun cleanTileCache(dir: File, used: Set<String>) {
    // Заодно удаляются и .part от прерванной записи: их имя не совпадает ни с одним ключом
    dir.listFiles()?.forEach { if (it.name.removeSuffix(".png") !in used) it.delete() }
}

/**
 * Рисует картинку в битмап точного размера плитки со скруглёнными углами.
 * Так видеочипу не нужно на каждом кадре масштабировать баннер и вырезать углы.
 */
fun renderRounded(d: Drawable, w: Int, h: Int, radius: Float): Bitmap {
    val plain = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val copy = d.constantState?.newDrawable()?.mutate() ?: d
    val canvas = Canvas(plain)
    val iw = copy.intrinsicWidth
    val ih = copy.intrinsicHeight
    if (iw > 0 && ih > 0) {
        // Рисуем в родном размере и масштабируем холст: некоторые баннеры (с gravity в XML)
        // при setBounds не растягиваются, а обрезаются
        copy.setBounds(0, 0, iw, ih)
        canvas.scale(w.toFloat() / iw, h.toFloat() / ih)
    } else {
        copy.setBounds(0, 0, w, h)
    }
    copy.draw(canvas)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(plain, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    Canvas(out).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, paint)
    plain.recycle()
    return out
}

/**
 * Плитка для приложения без ТВ-баннера: значок по центру на мягком градиенте его основного цвета.
 * Рисуется один раз и хранится в кэше на диске, как баннеры.
 */
fun renderIconTile(icon: Drawable, w: Int, h: Int, radius: Float): Bitmap {
    val color = dominantColor(icon)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            tone(color, 0.62f), tone(color, 0.30f), Shader.TileMode.CLAMP)
    }
    canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, bg)
    val size = (h * 0.52f).toInt()
    val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
    val l = (w - size) / 2
    val t = (h - size) / 2
    copy.setBounds(l, t, l + size, t + size)
    copy.draw(canvas)
    return out
}

/**
 * Определяет «главный» цвет иконки: уменьшаем её до 16×16 и усредняем
 * насыщенные пиксели (серые и прозрачные почти не учитываем).
 */
fun dominantColor(d: Drawable): Int {
    val size = 16
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val copy = d.constantState?.newDrawable()?.mutate() ?: d
    copy.setBounds(0, 0, size, size)
    copy.draw(Canvas(bmp))
    var r = 0f; var g = 0f; var b = 0f; var total = 0f
    val hsv = FloatArray(3)
    for (x in 0 until size) for (y in 0 until size) {
        val p = bmp.getPixel(x, y)
        if (Color.alpha(p) < 180) continue
        Color.colorToHSV(p, hsv)
        val w = 0.05f + hsv[1] * hsv[2]
        r += Color.red(p) * w; g += Color.green(p) * w; b += Color.blue(p) * w; total += w
    }
    bmp.recycle()
    if (total == 0f) return Color.rgb(52, 64, 100)
    return Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
}

/** Цвет с заданной насыщенностью и яркостью — для приятного тёмного фона плитки. */
fun tone(color: Int, value: Float): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    hsv[1] = hsv[1].coerceIn(0.25f, 0.8f)
    hsv[2] = value
    return Color.HSVToColor(hsv)
}

/** Настройки лаунчера: порядок и скрытые приложения, обои, заставка. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    var category: Category
        get() = Category.byName(sp.getString("category", null))
        set(v) = sp.edit().putString("category", v.name).apply()

    /** Последнее открытое приложение — оно идёт первым («Продолжить просмотр»). */
    var lastApp: String?
        get() = sp.getString("last_app", null)
        set(v) = sp.edit().putString("last_app", v).apply()

    /** Ночной режим заставки (с 23:00 до 6:00 — космос и приглушённая яркость). */
    var nightMode: Boolean
        get() = sp.getBoolean("night", true)
        set(v) = sp.edit().putBoolean("night", v).apply()

    /** Когда последний раз менялись обои на главном экране. */
    var bgChangedAt: Long
        get() = sp.getLong("bg_changed", 0)
        set(v) = sp.edit().putLong("bg_changed", v).apply()

    /** Через сколько минут бездействия на главном экране включать заставку (0 — никогда). */
    var idleMinutes: Int
        get() = sp.getInt("idle", 5)
        set(v) = sp.edit().putInt("idle", v).apply()

    val hidden: Set<String> get() = sp.getStringSet("hidden", emptySet())!!.toSet()

    val order: List<String> get() = sp.getString("order", "")!!.split('\n').filter { it.isNotBlank() }

    fun hide(pkg: String) = sp.edit().putStringSet("hidden", hidden + pkg).apply()

    fun unhideAll() = sp.edit().remove("hidden").apply()

    /** Запоминает весь порядок плиток (после перестановки в режиме «Переместить»). */
    fun saveOrder(pkgs: List<String>) = sp.edit().putString("order", pkgs.joinToString("\n")).apply()

    /** Ставит приложение первым в списке. */
    fun moveFirst(pkg: String) {
        val list = order.toMutableList().apply { remove(pkg); add(0, pkg) }
        sp.edit().putString("order", list.joinToString("\n")).apply()
    }
}
