package com.example.neo_wheel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.atan2

class MainActivity : Activity(), SensorEventListener {
    // ---- live values ----
    @Volatile var steer = 0f
    @Volatile var gas = 0f
    @Volatile var brake = 0f
    @Volatile var ip = ""
    @Volatile var running = true
    @Volatile var haptic = 0f
    @Volatile var mask = 0L              // bit n = vJoy button n+1

    // ---- settings (saved) ----
    @Volatile var lockAngle = 45         // degrees of tilt for full lock
    @Volatile var smoothing = 25         // % (higher = more responsive)
    @Volatile var deadzone = 3           // % around center
    @Volatile var softness = 0           // % center softness (response curve)
    @Volatile var invert = false
    @Volatile var hapticOn = true
    @Volatile var hapticStrength = 100   // %
    @Volatile var volEnabled = false
    @Volatile var volUpBtn = 9
    @Volatile var volDownBtn = 10
    @Volatile var btnCount = 6           // 0..8 extra buttons
    val btnNum = IntArray(8) { it + 1 }  // vJoy button number for each extra button
    val btnToggle = BooleanArray(8)

    // ---- internal state ----
    private val held = BooleanArray(8)
    private val toggled = BooleanArray(8)
    private var volUpHeld = false
    private var volDownHeld = false
    private var smooth = 0f
    private var zero = 0f
    private var lastDeg = 0f
    private val rot = FloatArray(9)
    private lateinit var prefs: SharedPreferences
    private lateinit var mid: LinearLayout
    private val padViews = ArrayList<PadButton>()

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, wt: Float, m: Int = 0) =
        LinearLayout.LayoutParams(w, h, wt).apply { setMargins(m, m, m, m) }

    // ================= settings storage =================
    private fun loadSettings() {
        lockAngle = prefs.getInt("lock", 45)
        smoothing = prefs.getInt("smooth", 25)
        deadzone = prefs.getInt("dz", 3)
        softness = prefs.getInt("soft", 0)
        invert = prefs.getBoolean("invert", false)
        hapticOn = prefs.getBoolean("hapOn", true)
        hapticStrength = prefs.getInt("hapStr", 100)
        volEnabled = prefs.getBoolean("volOn", false)
        volUpBtn = prefs.getInt("volUp", 9)
        volDownBtn = prefs.getInt("volDown", 10)
        btnCount = prefs.getInt("btnCount", 6)
        for (i in 0 until 8) {
            btnNum[i] = prefs.getInt("bn$i", i + 1)
            btnToggle[i] = prefs.getBoolean("bt$i", false)
        }
    }

    private fun saveSettings() {
        val e = prefs.edit()
        e.putInt("lock", lockAngle).putInt("smooth", smoothing).putInt("dz", deadzone)
        e.putInt("soft", softness).putBoolean("invert", invert)
        e.putBoolean("hapOn", hapticOn).putInt("hapStr", hapticStrength)
        e.putBoolean("volOn", volEnabled).putInt("volUp", volUpBtn).putInt("volDown", volDownBtn)
        e.putInt("btnCount", btnCount)
        for (i in 0 until 8) {
            e.putInt("bn$i", btnNum[i]).putBoolean("bt$i", btnToggle[i])
        }
        e.apply()
    }

    // ================= buttons =================
    private fun recompute() {
        var m = 0L
        for (i in 0 until btnCount) {
            if (held[i] || toggled[i]) m = m or (1L shl (btnNum[i] - 1))
        }
        if (volEnabled) {
            if (volUpHeld) m = m or (1L shl (volUpBtn - 1))
            if (volDownHeld) m = m or (1L shl (volDownBtn - 1))
        }
        mask = m
        for (i in padViews.indices) {
            padViews[i].lit = held[i] || toggled[i]
            padViews[i].invalidate()
        }
    }

    private fun pressBtn(i: Int) {
        if (btnToggle[i]) toggled[i] = !toggled[i] else held[i] = true
        recompute()
    }

    private fun releaseBtn(i: Int) {
        held[i] = false
        recompute()
    }

    private fun buildMiddle() {
        mid.removeAllViews()
        padViews.clear()
        java.util.Arrays.fill(held, false)
        java.util.Arrays.fill(toggled, false)
        var i = 0
        while (i < btnCount) {
            val row = LinearLayout(this)
            for (k in 0..1) {
                val idx = i + k
                if (idx < btnCount) {
                    val label = "B" + btnNum[idx] + if (btnToggle[idx]) " (T)" else ""
                    val pb = PadButton(this, label, { pressBtn(idx) }, { releaseBtn(idx) })
                    padViews.add(pb)
                    row.addView(pb, lp(0, MATCH, 1f, 6))
                } else {
                    row.addView(View(this), lp(0, MATCH, 1f))
                }
            }
            mid.addView(row, lp(MATCH, 0, 1f))
            i += 2
        }
        recompute()
    }

    // ================= activity =================
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getPreferences(Context.MODE_PRIVATE)
        loadSettings()

        val ipBox = EditText(this).apply {
            hint = "PC IP, e.g. 192.168.1.20"
            setText(prefs.getString("ip", ""))
        }
        val setIp = Button(this).apply {
            text = "Set IP"
            setOnClickListener {
                ip = ipBox.text.toString().trim()
                prefs.edit().putString("ip", ip).apply()
            }
        }
        val center = Button(this).apply {
            text = "Center"
            setOnClickListener { zero = lastDeg }
        }
        val gear = Button(this).apply {
            text = "Settings"
            setOnClickListener { showSettings() }
        }

        val top = LinearLayout(this).apply {
            addView(ipBox, lp(0, WRAP, 1f))
            addView(setIp)
            addView(center)
            addView(gear)
        }

        mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val pedals = LinearLayout(this).apply {
            addView(Pedal(this@MainActivity, "BRAKE", 0xFFCC3333.toInt()) { brake = it }, lp(0, MATCH, 1f))
            addView(mid, lp(0, MATCH, 2f))
            addView(Pedal(this@MainActivity, "GAS", 0xFF33AA33.toInt()) { gas = it }, lp(0, MATCH, 1f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, lp(MATCH, WRAP, 0f))
            addView(pedals, lp(MATCH, 0, 1f))
        })

        buildMiddle()
        ip = ipBox.text.toString().trim()

        // UDP sender: steering, pedals, button mask -> PC (~100 Hz)
        Thread {
            val sock = DatagramSocket()
            while (running) {
                try {
                    val target = ip
                    if (target.isNotEmpty()) {
                        val msg = String.format(
                            java.util.Locale.US, "%.3f,%.3f,%.3f,%d", steer, gas, brake, mask
                        ).toByteArray()
                        sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(target), 5005))
                    }
                } catch (_: Exception) {
                }
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
            }
            sock.close()
        }.start()

        // UDP receiver: haptic intensity from the PC
        Thread {
            val sock = DatagramSocket(5006)
            sock.soTimeout = 400
            val buf = ByteArray(64)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    haptic = String(p.data, 0, p.length).trim().toFloat()
                } catch (_: SocketTimeoutException) {
                    haptic = 0f
                } catch (_: Exception) {
                }
            }
            sock.close()
        }.start()

        // Vibration thread
        val vib: Vibrator =
            if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else
                @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)

        Thread {
            var wasOn = false
            while (running) {
                val h = if (hapticOn) haptic * hapticStrength / 100f else 0f
                if (h > 0.03f) {
                    wasOn = true
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (vib.hasAmplitudeControl()) {
                            val amp = (h * 255).toInt().coerceIn(1, 255)
                            vib.vibrate(VibrationEffect.createOneShot(60, amp))
                        } else {
                            val ms = (h * 50).toLong().coerceAtLeast(8)
                            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    } else {
                        @Suppress("DEPRECATION") vib.vibrate((h * 50).toLong().coerceAtLeast(8))
                    }
                } else if (wasOn) {
                    vib.cancel()
                    wasOn = false
                }
                try { Thread.sleep(40) } catch (_: InterruptedException) {}
            }
            vib.cancel()
        }.start()
    }

    // ================= volume buttons =================
    // Returning true consumes the key, so Android neither changes the volume nor shows the popup.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if ((event?.repeatCount ?: 0) == 0) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUpHeld = true else volDownHeld = true
                recompute()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUpHeld = false else volDownHeld = false
            recompute()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ================= settings dialog =================
    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        buildSettings(box)
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildSettings(box: LinearLayout) {
        box.removeAllViews()

        header(box, "Steering")
        slider(box, "Full-lock angle", 20, 90, lockAngle, "°") { lockAngle = it; saveSettings() }
        slider(box, "Smoothing", 5, 100, smoothing, "%") { smoothing = it; saveSettings() }
        slider(box, "Center deadzone", 0, 15, deadzone, "%") { deadzone = it; saveSettings() }
        slider(box, "Center softness", 0, 100, softness, "%") { softness = it; saveSettings() }
        check(box, "Invert steering", invert) { invert = it; saveSettings() }

        header(box, "Haptics")
        check(box, "Haptic feedback", hapticOn) { hapticOn = it; saveSettings() }
        slider(box, "Haptic strength", 0, 100, hapticStrength, "%") { hapticStrength = it; saveSettings() }

        header(box, "Volume buttons")
        check(box, "Use volume buttons as buttons (also hides the volume popup)", volEnabled) {
            volEnabled = it
            volUpHeld = false; volDownHeld = false
            saveSettings(); recompute()
        }
        stepper(box, "Volume UP = vJoy button", 1, 32, volUpBtn) { volUpBtn = it; saveSettings() }
        stepper(box, "Volume DOWN = vJoy button", 1, 32, volDownBtn) { volDownBtn = it; saveSettings() }

        header(box, "Extra buttons")
        stepper(box, "Number of buttons", 0, 8, btnCount) {
            btnCount = it
            saveSettings()
            buildMiddle()
            buildSettings(box)
        }
        for (i in 0 until btnCount) {
            stepper(box, "Button ${i + 1} = vJoy button", 1, 32, btnNum[i]) {
                btnNum[i] = it
                saveSettings()
                buildMiddle()
            }
            check(box, "Button ${i + 1}: toggle (tap on / tap off)", btnToggle[i]) {
                btnToggle[i] = it
                saveSettings()
                buildMiddle()
            }
        }

        box.addView(TextView(this).apply {
            text = "\nvJoy button numbers must be enabled in Configure vJoy (set it to 32 buttons). " +
                    "In the game, assign each control by pressing the button when it asks for input."
            textSize = 12f
        })
    }

    private fun header(box: ViewGroup, title: String) {
        box.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        })
    }

    private fun slider(
        box: ViewGroup, title: String, lo: Int, hi: Int, cur: Int, unit: String, onChange: (Int) -> Unit
    ) {
        val label = TextView(this).apply { text = "$title: $cur$unit" }
        val bar = SeekBar(this).apply {
            this.max = hi - lo
            progress = cur - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    label.text = "$title: $v$unit"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(label)
        box.addView(bar)
    }

    private fun stepper(
        box: ViewGroup, title: String, lo: Int, hi: Int, cur: Int, onChange: (Int) -> Unit
    ) {
        var v = cur
        val label = TextView(this).apply { text = "$title: $v" }
        fun step(d: Int) {
            val n = (v + d).coerceIn(lo, hi)
            if (n != v) {
                v = n
                label.text = "$title: $v"
                onChange(v)
            }
        }
        val minus = Button(this).apply { text = "-"; setOnClickListener { step(-1) } }
        val plus = Button(this).apply { text = "+"; setOnClickListener { step(1) } }
        val row = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(label, lp(0, WRAP, 1f))
            addView(minus)
            addView(plus)
        }
        box.addView(row)
    }

    private fun check(box: ViewGroup, title: String, cur: Boolean, onChange: (Boolean) -> Unit) {
        box.addView(CheckBox(this).apply {
            text = title
            isChecked = cur
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    // ================= sensors =================
    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sm.registerListener(this, sensor, 5000)
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        // release anything held so buttons don't get stuck while the app is in the background
        java.util.Arrays.fill(held, false)
        volUpHeld = false
        volDownHeld = false
        gas = 0f
        brake = 0f
        recompute()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val gx: Float
        val gy: Float
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            gx = rot[6]
            gy = rot[7]
        } else {
            gx = e.values[0]
            gy = e.values[1]
        }

        val s = if (gx >= 0f) 1f else -1f
        lastDeg = Math.toDegrees(atan2((gy * s).toDouble(), abs(gx).toDouble())).toFloat()
        val deg = lastDeg - zero
        val dir = if (invert) -1f else 1f
        val target = (dir * deg / lockAngle).coerceIn(-1f, 1f)

        smooth += (smoothing / 100f) * (target - smooth)

        val dz = deadzone / 100f
        val o = if (abs(smooth) < dz) 0f
        else Math.signum(smooth) * (abs(smooth) - dz) / (1f - dz)
        val k = softness / 100f
        steer = o * (1f - k) + o * abs(o) * k
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

/** Analog pedal: touch position sets the value. Bottom = 0, top = 1. Lift finger = 0. */
class Pedal(
    ctx: Context,
    private val label: String,
    color: Int,
    private val onValue: (Float) -> Unit
) : View(ctx) {
    private var v = 0f
    private val bg = Paint().apply { this.color = 0xFF222222.toInt() }
    private val fill = Paint().apply { this.color = color }
    private val txt = Paint().apply {
        this.color = 0xFFFFFFFF.toInt()
        textSize = 56f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        c.drawRect(0f, height * (1f - v), width.toFloat(), height.toFloat(), fill)
        c.drawText("$label ${(v * 100).toInt()}%", width / 2f, height / 2f, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                v = (1f - e.y / height).coerceIn(0f, 1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v = 0f
        }
        onValue(v)
        invalidate()
        return true
    }
}

/** Assignable on-screen button. Lights up while pressed (or while toggled on). */
class PadButton(
    ctx: Context,
    private val label: String,
    private val onDown: () -> Unit,
    private val onUp: () -> Unit
) : View(ctx) {
    var lit = false
    private val rect = RectF()
    private val offPaint = Paint().apply { this.color = 0xFF333333.toInt(); isAntiAlias = true }
    private val onPaint = Paint().apply { this.color = 0xFF3377DD.toInt(); isAntiAlias = true }
    private val txt = Paint().apply {
        this.color = 0xFFFFFFFF.toInt()
        textSize = 44f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, 24f, 24f, if (lit) onPaint else offPaint)
        c.drawText(label, width / 2f, height / 2f + 15f, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp()
        }
        return true
    }
}