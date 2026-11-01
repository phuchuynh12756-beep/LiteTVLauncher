package com.lite.tvlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Launcher siêu nhẹ cho TV box cấu hình yếu.
 * - Không dùng thư viện ngoài, chỉ View cơ bản của framework.
 * - Animation chỉ dùng alpha / translation / scale (chạy trên GPU, không relayout).
 * - Điều khiển hoàn toàn bằng D-pad.
 */
class MainActivity : Activity() {

    private class AppItem(val label: String, val pkg: String, val cn: ComponentName)
    private class Holder(val icon: ImageView, val label: TextView)

    private val apps = ArrayList<AppItem>()
    private val iconCache = LruCache<String, Drawable>(64)
    private val io = Executors.newSingleThreadExecutor()
    private val ease = DecelerateInterpolator(1.6f)

    private lateinit var prefs: SharedPreferences
    private lateinit var wallFile: File
    private lateinit var wallpaper: ImageView
    private lateinit var dim: View
    private lateinit var content: View
    private lateinit var grid: GridView
    private lateinit var panel: View
    private lateinit var btnSettings: View
    private lateinit var rowGrad: TextView
    private lateinit var rowDim: TextView
    private lateinit var rowAnim: TextView
    private lateinit var rowAuto: TextView

    private var wallBitmap: Bitmap? = null
    private var panelOpen = false
    private var firstShow = true
    private var needReveal = false

    private val animOn get() = prefs.getBoolean(K_ANIM, true)

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }

    // ---------------------------------------------------------------- vòng đời

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("lite", MODE_PRIVATE)
        wallFile = File(filesDir, "wallpaper.jpg")

        wallpaper = findViewById(R.id.wallpaper)
        dim = findViewById(R.id.dim)
        content = findViewById(R.id.content)
        grid = findViewById(R.id.grid)
        panel = findViewById(R.id.panel)
        btnSettings = findViewById(R.id.btnSettings)
        rowGrad = findViewById(R.id.rowGrad)
        rowDim = findViewById(R.id.rowDim)
        rowAnim = findViewById(R.id.rowAnim)
        rowAuto = findViewById(R.id.rowAuto)

        setupGrid()
        setupPanel()
        applyBackground()
        refreshRows()
        loadApps()

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onResume() {
        super.onResume()
        if (needReveal) {
            needReveal = false
            if (prefs.getBoolean(K_AUTO, false)) cleanRam(false)
            if (animOn) {
                content.alpha = 0f
                content.scaleX = 1.04f
                content.scaleY = 1.04f
                content.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(240).setInterpolator(ease).withLayer().start()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Nhấn nút HOME khi đang ở launcher: đóng bảng cài đặt, về đầu danh sách
        if (panelOpen) closePanel()
        grid.setSelection(0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
        io.shutdown()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ---------------------------------------------------------------- phím điều khiển

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Launcher không được thoát; BACK chỉ đóng bảng cài đặt
        if (panelOpen) closePanel()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (panelOpen) closePanel() else openPanel()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- danh sách app

    private fun setupGrid() {
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false).also {
                    it.tag = Holder(it.findViewById(R.id.icon), it.findViewById(R.id.label))
                }
                val h = v.tag as Holder
                val a = apps[position]
                h.label.text = a.label
                h.icon.tag = a.pkg
                val cached = iconCache.get(a.pkg)
                if (cached != null) {
                    h.icon.setImageDrawable(cached)
                } else {
                    h.icon.setImageDrawable(null)
                    loadIcon(a, h.icon)
                }
                // view tái sử dụng có thể còn giữ trạng thái phóng to cũ
                val sel = position == grid.selectedItemPosition && grid.hasFocus()
                v.scaleX = if (sel) SCALE_SEL else 1f
                v.scaleY = v.scaleX
                v.translationZ = if (sel) 10f else 0f
                return v
            }
        }

        var last: View? = null
        grid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                last?.let { scaleTo(it, 1f) }
                v?.let { scaleTo(it, SCALE_SEL); last = it }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {
                last?.let { scaleTo(it, 1f) }
                last = null
            }
        }

        grid.setOnItemClickListener { _, _, pos, _ -> launch(apps[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + apps[pos].pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                toast("Không mở được thông tin app")
            }
            true
        }
        grid.requestFocus()
    }

    private fun scaleTo(v: View, s: Float) {
        val z = if (s > 1f) 10f else 0f
        if (animOn) {
            v.animate().scaleX(s).scaleY(s).translationZ(z)
                .setDuration(120).setInterpolator(ease).start()
        } else {
            v.animate().cancel()
            v.scaleX = s; v.scaleY = s; v.translationZ = z
        }
    }

    private fun loadApps() {
        io.execute {
            val pm = packageManager
            val seen = HashSet<String>()
            val list = ArrayList<AppItem>()
            // LEANBACK trước để app TV ưu tiên hiện icon dạng TV
            for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
                for (ri in pm.queryIntentActivities(q, 0)) {
                    val ai = ri.activityInfo ?: continue
                    if (ai.packageName == packageName) continue
                    if (!seen.add(ai.packageName)) continue
                    list.add(
                        AppItem(
                            ri.loadLabel(pm).toString(),
                            ai.packageName,
                            ComponentName(ai.packageName, ai.name)
                        )
                    )
                }
            }
            list.sortBy { it.label.lowercase() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                apps.clear()
                apps.addAll(list)
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                playIntro()
            }
        }
    }

    private fun loadIcon(a: AppItem, target: ImageView) {
        io.execute {
            val d = try { packageManager.getActivityIcon(a.cn) } catch (_: Throwable) { null }
            if (d != null) iconCache.put(a.pkg, d)
            runOnUiThread { if (d != null && target.tag == a.pkg) target.setImageDrawable(d) }
        }
    }

    private fun launch(a: AppItem) {
        try {
            if (prefs.getBoolean(K_AUTO, false)) cleanRam(false)
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(a.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
            needReveal = true
            if (animOn) {
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.zoom_in, R.anim.hold_fade)
            }
        } catch (_: ActivityNotFoundException) {
            toast("Không mở được " + a.label)
        } catch (_: SecurityException) {
            toast("Không mở được " + a.label)
        }
    }

    /** Các icon lần lượt hiện lên (chỉ lần đầu, tối đa 18 icon để giữ nhẹ). */
    private fun playIntro() {
        if (!firstShow || !animOn) { firstShow = false; return }
        firstShow = false
        grid.post {
            val n = minOf(grid.childCount, 18)
            val dy = 24f * resources.displayMetrics.density
            for (i in 0 until n) {
                val c = grid.getChildAt(i)
                c.alpha = 0f
                c.translationY = dy
                c.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 22L).setDuration(240).setInterpolator(ease)
                    .withLayer().start()
            }
        }
    }

    // ---------------------------------------------------------------- bảng cài đặt

    private fun setupPanel() {
        btnSettings.setOnClickListener { openPanel() }

        findViewById<View>(R.id.rowPick).setOnClickListener {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK
                )
            } catch (_: ActivityNotFoundException) {
                toast("Box chưa có app chọn ảnh. Hãy cài một app quản lý file.")
            }
        }

        rowGrad.setOnClickListener {
            val cur = prefs.getInt(K_BG, 0)
            val next = if (cur < 0) 0 else (cur + 1) % GRADS.size
            prefs.edit().putInt(K_BG, next).apply()
            applyBackground(); refreshRows()
        }

        rowDim.setOnClickListener {
            val next = (prefs.getInt(K_DIM, 20) + 20) % 80
            prefs.edit().putInt(K_DIM, next).apply()
            applyBackground(); refreshRows()
        }

        rowAnim.setOnClickListener {
            prefs.edit().putBoolean(K_ANIM, !animOn).apply()
            refreshRows()
        }

        rowAuto.setOnClickListener {
            prefs.edit().putBoolean(K_AUTO, !prefs.getBoolean(K_AUTO, false)).apply()
            refreshRows()
        }

        findViewById<View>(R.id.rowClean).setOnClickListener { cleanRam(true) }
    }

    private fun refreshRows() {
        val bg = prefs.getInt(K_BG, 0)
        rowGrad.text = if (bg < 0) "Nền: ảnh của bạn (nhấn để đổi sang màu)"
        else "Nền: màu ${bg + 1}/${GRADS.size} (nhấn để đổi)"
        rowDim.text = "Độ tối nền: ${prefs.getInt(K_DIM, 20)}%"
        rowAnim.text = "Hiệu ứng chuyển cảnh: " + if (animOn) "BẬT" else "TẮT (nhẹ nhất)"
        rowAuto.text = "Tự dọn RAM khi mở/thoát app: " + if (prefs.getBoolean(K_AUTO, false)) "BẬT" else "TẮT"
    }

    private fun openPanel() {
        if (panelOpen) return
        panelOpen = true
        (content as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel.visibility = View.VISIBLE
        val w = panel.layoutParams.width.toFloat() // đã là pixel
        if (animOn) {
            panel.translationX = w
            panel.animate().translationX(0f).setDuration(220).setInterpolator(ease)
                .withLayer().start()
        } else {
            panel.translationX = 0f
        }
        findViewById<View>(R.id.rowPick).requestFocus()
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        (content as ViewGroup).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (animOn) {
            val w = panel.width.toFloat()
            panel.animate().translationX(w).setDuration(180).setInterpolator(ease)
                .withLayer().withEndAction { if (!panelOpen) panel.visibility = View.GONE }.start()
        } else {
            panel.visibility = View.GONE
        }
        grid.requestFocus()
    }

    // ---------------------------------------------------------------- hình nền

    private fun applyBackground() {
        dim.setBackgroundColor(Color.argb(prefs.getInt(K_DIM, 20) * 255 / 100, 0, 0, 0))
        val idx = prefs.getInt(K_BG, 0)
        if (idx < 0 && wallFile.exists()) {
            io.execute {
                val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bmp = try { BitmapFactory.decodeFile(wallFile.path, o) } catch (_: Throwable) { null }
                runOnUiThread {
                    if (bmp != null) setWallBitmap(bmp) else {
                        prefs.edit().putInt(K_BG, 0).apply()
                        applyBackground(); refreshRows()
                    }
                }
            }
        } else {
            val i = if (idx < 0) 0 else idx.coerceAtMost(GRADS.size - 1)
            wallpaper.setImageDrawable(
                GradientDrawable(GradientDrawable.Orientation.TL_BR, GRADS[i])
            )
            releaseWallBitmap()
        }
    }

    private fun setWallBitmap(bmp: Bitmap) {
        wallpaper.setImageBitmap(bmp)
        releaseWallBitmap()
        wallBitmap = bmp
    }

    private fun releaseWallBitmap() {
        wallBitmap?.let { if (!it.isRecycled && wallpaper.drawable?.let { d -> (d as? android.graphics.drawable.BitmapDrawable)?.bitmap } !== it) it.recycle() }
        wallBitmap = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { importImage(it) }
        }
    }

    /** Đọc ảnh đã giảm kích thước về đúng độ phân giải màn hình, lưu lại, dùng RGB_565 để tiết kiệm RAM. */
    private fun importImage(uri: Uri) {
        toast("Đang xử lý ảnh...")
        io.execute {
            try {
                val dm = resources.displayMetrics
                val tw = minOf(dm.widthPixels, 1920)
                val th = minOf(dm.heightPixels, 1080)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var s = 1
                while (bounds.outWidth / (s * 2) >= tw && bounds.outHeight / (s * 2) >= th) s *= 2

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = s
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: throw IllegalStateException("decode")

                FileOutputStream(wallFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                prefs.edit().putInt(K_BG, -1).apply()
                runOnUiThread {
                    setWallBitmap(bmp)
                    refreshRows()
                    toast("Đã đổi hình nền")
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Không đọc được ảnh này (có thể quá lớn)") }
            }
        }
    }

    // ---------------------------------------------------------------- dọn RAM

    private fun cleanRam(showResult: Boolean) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val before = freeMb(am)
        val pkgs = apps.map { it.pkg }
        io.execute {
            for (p in pkgs) try { am.killBackgroundProcesses(p) } catch (_: Throwable) {}
            if (showResult) {
                Thread.sleep(600)
                val after = freeMb(am)
                runOnUiThread {
                    toast("RAM trống: ${after}MB (giải phóng ${maxOf(0L, after - before)}MB)")
                }
            }
        }
    }

    private fun freeMb(am: ActivityManager): Long {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 1
        private const val SCALE_SEL = 1.1f
        private const val K_BG = "bg"        // -1 = ảnh của người dùng, >=0 = chỉ số màu
        private const val K_DIM = "dim"
        private const val K_ANIM = "anim"
        private const val K_AUTO = "auto"

        private val GRADS = arrayOf(
            intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt()),
            intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt()),
            intArrayOf(0xFF200122.toInt(), 0xFF6F0000.toInt()),
            intArrayOf(0xFF0F0C29.toInt(), 0xFF302B63.toInt()),
            intArrayOf(0xFF1D2B1F.toInt(), 0xFF3A6B35.toInt()),
            intArrayOf(0xFF000000.toInt(), 0xFF2A2A2A.toInt())
        )
    }
}
