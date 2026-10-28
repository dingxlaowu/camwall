package com.example.camwall

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var store: DeviceStore
    private lateinit var brightness: BrightnessController

    private lateinit var grid: LinearLayout
    private lateinit var topBar: View
    private lateinit var pageLabel: TextView
    private val layoutButtons = mutableMapOf<Int, TextView>()
    private val cells = mutableListOf<CameraCell>()
    private val handler = Handler(Looper.getMainLooper())

    private var swallowing = false
    private var zoomReturnLayout = 0 // 0 = 当前不是双击放大状态
    private var zoomReturnPage = 0
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        brightness = BrightnessController(this) { store.settings }

        // 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildUi())
        if (store.devices.isEmpty()) handler.post { showDeviceList() }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        rebuildGrid()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        brightness.onUserTouch()
        showBar()
    }

    override fun onPause() {
        super.onPause()
        brightness.stop()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        releaseCells() // 切到后台就断流，省电省流量
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------- 亮度：拦截所有触摸 ----------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowing = store.settings.wakeTouchOnly && brightness.isDim()
            brightness.onUserTouch()
        }
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowing = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) brightness.onUserTouch()
        return super.dispatchKeyEvent(event)
    }

    // ---------------- 界面 ----------------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF1A1A1A.toInt()) }

        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for (n in listOf(1, 4, 9)) {
            val b = barButton(if (n == 1) "单画面" else "${n}分屏") { setLayoutMode(n) }
            layoutButtons[n] = b
            bar.addView(b)
        }
        bar.addView(barButton("◀") { changePage(-1) })
        pageLabel = barButton("1/1") {}
        bar.addView(pageLabel)
        bar.addView(barButton("▶") { changePage(1) })
        bar.addView(barButton("设备") { showDeviceList() })
        bar.addView(barButton("设置") { showSettings() })

        topBar = HorizontalScrollView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        return root
    }

    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener {
            onClick()
            showBar()
        }
    }

    private val hideBarRunnable = Runnable { topBar.visibility = View.GONE }

    private fun showBar() {
        topBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideBarRunnable)
        handler.postDelayed(hideBarRunnable, 6000)
    }

    private fun toggleBar() {
        if (topBar.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideBarRunnable)
            topBar.visibility = View.GONE
        } else {
            showBar()
        }
    }

    private fun refreshBar(pages: Int) {
        val s = store.settings
        layoutButtons.forEach { (n, b) ->
            b.setTextColor(if (n == s.layout && zoomReturnLayout == 0) 0xFF4FC3F7.toInt() else Color.WHITE)
        }
        pageLabel.text = "${s.page + 1}/$pages"
    }

    private fun pageCount(): Int = max(1, ceil(store.devices.size / store.settings.layout.toFloat()).toInt())

    private fun rebuildGrid() {
        releaseCells()
        grid.removeAllViews()
        if (!visible) return

        val s = store.settings
        val n = s.layout
        val cols = when (n) { 1 -> 1; 4 -> 2; else -> 3 }
        val pages = pageCount()
        if (s.page !in 0 until pages) s.page = 0
        val useSub = n > 1 && s.subStreamInGrid

        for (r in 0 until cols) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            for (c in 0 until cols) {
                val idx = s.page * n + r * cols + c
                val cell = CameraCell(this)
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(1, 1, 1, 1) })
                attachGestures(cell, idx)
                cell.bind(store.devices.getOrNull(idx), useSub)
                cells += cell
            }
        }
        refreshBar(pages)
        store.saveSettings()
    }

    private fun releaseCells() {
        cells.forEach { it.release() }
        cells.clear()
    }

    /** 单击：显示/隐藏菜单；双击：放大该路 / 还原；左右滑：翻页 */
    private fun attachGestures(cell: View, idx: Int) {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleBar(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(idx); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 800) {
                    changePage(if (vx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        cell.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
    }

    private fun toggleZoom(idx: Int) {
        val s = store.settings
        if (zoomReturnLayout == 0) {
            if (store.devices.getOrNull(idx) == null || s.layout == 1) return
            zoomReturnLayout = s.layout
            zoomReturnPage = s.page
            s.layout = 1
            s.page = idx
        } else {
            s.layout = zoomReturnLayout
            s.page = zoomReturnPage
            zoomReturnLayout = 0
        }
        rebuildGrid()
    }

    private fun setLayoutMode(n: Int) {
        val s = store.settings
        zoomReturnLayout = 0
        val firstIdx = s.page * s.layout
        s.layout = n
        s.page = firstIdx / n
        rebuildGrid()
    }

    private fun changePage(delta: Int) {
        val pages = pageCount()
        if (pages <= 1) return
        val s = store.settings
        s.page = (s.page + delta + pages) % pages
        rebuildGrid()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---------------- 弹窗（打开期间保持高亮） ----------------

    private fun AlertDialog.Builder.showHeld(): AlertDialog {
        brightness.hold(true)
        setOnDismissListener { brightness.hold(false) }
        return show()
    }

    private fun showDeviceList() {
        val devices = store.devices
        val b = AlertDialog.Builder(this)
            .setTitle("摄像头（${devices.size} 台）")
            .setPositiveButton("添加") { _, _ -> showEditDevice(null) }
            .setNegativeButton("关闭", null)
        if (devices.isEmpty()) {
            b.setMessage("还没有摄像头，点「添加」填写 IP、用户名和密码。")
        } else {
            b.setItems(devices.mapIndexed { i, d -> "${i + 1}. ${d.name.ifBlank { "未命名" }}  ${d.ip}" }.toTypedArray()) { _, which ->
                showDeviceActions(which)
            }
        }
        b.showHeld()
    }

    private fun showDeviceActions(i: Int) {
        val d = store.devices.getOrNull(i) ?: return
        AlertDialog.Builder(this)
            .setTitle(d.name.ifBlank { d.ip })
            .setItems(arrayOf("编辑", "上移", "下移", "删除")) { _, which ->
                when (which) {
                    0 -> showEditDevice(d)
                    1 -> moveDevice(i, -1)
                    2 -> moveDevice(i, 1)
                    3 -> AlertDialog.Builder(this)
                        .setMessage("删除「${d.name.ifBlank { d.ip }}」？")
                        .setPositiveButton("删除") { _, _ ->
                            store.devices.removeAt(i)
                            store.saveDevices()
                            rebuildGrid()
                            showDeviceList()
                        }
                        .setNegativeButton("取消", null)
                        .showHeld()
                }
            }
            .showHeld()
    }

    private fun moveDevice(i: Int, delta: Int) {
        val j = i + delta
        if (j !in store.devices.indices) return
        val list = store.devices
        list[i] = list[j].also { list[j] = list[i] }
        store.saveDevices()
        rebuildGrid()
        showDeviceList()
    }

    private fun showEditDevice(existing: Device?) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_device, null)
        val etName = v.findViewById<EditText>(R.id.etName)
        val etIp = v.findViewById<EditText>(R.id.etIp)
        val etPort = v.findViewById<EditText>(R.id.etPort)
        val etUser = v.findViewById<EditText>(R.id.etUser)
        val etPass = v.findViewById<EditText>(R.id.etPass)
        val spBrand = v.findViewById<Spinner>(R.id.spBrand)
        val etMain = v.findViewById<EditText>(R.id.etMain)
        val etSub = v.findViewById<EditText>(R.id.etSub)
        val tvPreview = v.findViewById<TextView>(R.id.tvPreview)

        val d = existing?.copy() ?: Device(name = "摄像头 ${store.devices.size + 1}")
        etName.setText(d.name)
        etIp.setText(d.ip)
        etPort.setText(d.port.toString())
        etUser.setText(d.user)
        etPass.setText(d.pass)
        etMain.setText(d.mainPath)
        etSub.setText(d.subPath)

        fun readForm(): Device = d.copy(
            name = etName.text.toString().trim(),
            ip = etIp.text.toString().trim(),
            port = etPort.text.toString().toIntOrNull() ?: 554,
            user = etUser.text.toString(),
            pass = etPass.text.toString(),
            brand = BRANDS.getOrNull(spBrand.selectedItemPosition)?.key ?: "custom",
            mainPath = etMain.text.toString().trim(),
            subPath = etSub.text.toString().trim(),
        )

        fun updatePreview() {
            val f = readForm()
            tvPreview.text = if (f.ip.isBlank()) "" else "主码流：${f.displayUrl(false)}\n子码流：${f.displayUrl(true)}"
        }

        spBrand.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, BRANDS.map { it.label })
        val initialBrand = BRANDS.indexOfFirst { it.key == d.brand }.coerceAtLeast(0)
        spBrand.setSelection(initialBrand, false)
        var lastBrandPos = initialBrand
        spBrand.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos == lastBrandPos) { updatePreview(); return } // 打开时的首次回调，不覆盖已有路径
                lastBrandPos = pos
                val b = BRANDS[pos]
                if (b.key != "custom") {
                    etMain.setText(b.main)
                    etSub.setText(b.sub)
                }
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        listOf(etIp, etPort, etUser, etPass, etMain, etSub).forEach { it.addTextChangedListener(watcher) }
        updatePreview()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加摄像头" else "编辑摄像头")
            .setView(v)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .showHeld()

        // 手动处理"保存"，校验失败时不关闭弹窗
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val f = readForm()
            if (f.ip.isBlank()) {
                etIp.error = "请填写 IP"
                return@setOnClickListener
            }
            val list = store.devices
            val pos = list.indexOfFirst { it.id == f.id }
            if (pos >= 0) list[pos] = f else list.add(f)
            store.saveDevices()
            dialog.dismiss()
            rebuildGrid()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSettings() {
        val s = store.settings
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etHigh = v.findViewById<EditText>(R.id.etHigh)
        val etLow = v.findViewById<EditText>(R.id.etLow)
        val etRamp = v.findViewById<EditText>(R.id.etRamp)
        val etHold = v.findViewById<EditText>(R.id.etHold)
        val etFade = v.findViewById<EditText>(R.id.etFade)
        val cbSub = v.findViewById<CheckBox>(R.id.cbSub)
        val cbWake = v.findViewById<CheckBox>(R.id.cbWake)

        etHigh.setText(s.highPct.toString())
        etLow.setText(s.lowPct.toString())
        etRamp.setText(s.rampUpSec.toString())
        etHold.setText(s.holdSec.toString())
        etFade.setText(s.fadeSec.toString())
        cbSub.isChecked = s.subStreamInGrid
        cbWake.isChecked = s.wakeTouchOnly

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                s.highPct = (etHigh.text.toString().toIntOrNull() ?: 80).coerceIn(5, 100)
                s.lowPct = (etLow.text.toString().toIntOrNull() ?: 1).coerceIn(0, s.highPct)
                s.rampUpSec = (etRamp.text.toString().toFloatOrNull() ?: 1.5f).coerceIn(0f, 10f)
                s.holdSec = (etHold.text.toString().toFloatOrNull() ?: 10f).coerceIn(0f, 3600f)
                s.fadeSec = (etFade.text.toString().toFloatOrNull() ?: 50f).coerceIn(0f, 3600f)
                val subChanged = s.subStreamInGrid != cbSub.isChecked
                s.subStreamInGrid = cbSub.isChecked
                s.wakeTouchOnly = cbWake.isChecked
                store.saveSettings()
                if (subChanged) rebuildGrid()
            }
            .setNegativeButton("取消", null)
            .showHeld()
    }
}
