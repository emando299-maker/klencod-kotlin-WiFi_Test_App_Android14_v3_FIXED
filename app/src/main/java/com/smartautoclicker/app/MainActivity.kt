package com.smartautoclicker.app

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var intervalSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var latency: TextView
    private lateinit var results: TextView
    private lateinit var networks: TextView
    private lateinit var fileStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var testTask: ScheduledFuture<*>? = null
    private var testCount = 0
    private val prefs by lazy { getSharedPreferences("wifi_test_settings", MODE_PRIVATE) }
    private val intervals = listOf(1, 5, 10, 15, 20, 25, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 17, 23)
        window.navigationBarColor = Color.rgb(13, 17, 23)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(13, 17, 23))
        }

        fun label(text: String, size: Float, bold: Boolean = false): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 10, 0, 10)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }

        fun button(text: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
        }

        root.addView(label("اختبار الواي فاي", 26f, true))
        root.addView(label("اختبر اتصال الإنترنت وسجّل زمن الاستجابة", 15f))

        root.addView(label("الفاصل الزمني بين الاختبارات", 17f, true))
        intervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                intervals.map { "$it ثانية" }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(intervals.indexOf(prefs.getInt("interval", 5)).coerceAtLeast(0))
        }
        root.addView(intervalSpinner, LinearLayout.LayoutParams(-1, -2))

        connection = label("الشبكة الحالية: جارٍ التحقق...", 16f)
        status = label("الحالة: متوقف", 16f, true)
        latency = label("آخر نتيجة: —", 18f, true)
        results = label("عدد الاختبارات: 0", 14f)
        root.addView(connection)
        root.addView(status)
        root.addView(latency)
        root.addView(results)

        root.addView(label("الشبكات القريبة", 18f, true))
        networks = label("اضغط على تحديث الشبكات لعرض الشبكات القريبة.", 14f)
        networks.gravity = Gravity.RIGHT
        root.addView(networks, LinearLayout.LayoutParams(-1, -2))
        button("تحديث الشبكات القريبة") { refreshNetworks() }

        root.addView(label("ملف كلمات المرور", 18f, true))
        fileStatus = label("لم يتم اختيار ملف بعد.", 14f)
        root.addView(fileStatus)
        button("استيراد ملف كلمات المرور") { choosePasswordFile() }

        button("بدء الاختبار") { startTesting() }
        button("إيقاف الاختبار") { stopTesting() }
        button("تحديث حالة الشبكة") { updateConnectionLabel() }
        root.addView(label("يقيس التطبيق اتصال الإنترنت ويعرض الشبكات القريبة. استيراد الملف يحصي السطور فقط؛ لا يجرب كلمات المرور تلقائيًا.", 12f))

        setContentView(ScrollView(this).apply { addView(root) })
        updateConnectionLabel()
    }

    private fun selectedInterval(): Int {
        val selected = intervals.getOrElse(intervalSpinner.selectedItemPosition) { 5 }
        prefs.edit().putInt("interval", selected).apply()
        return selected
    }

    private fun startTesting() {
        stopTaskOnly()
        val seconds = selectedInterval()
        testCount = 0
        results.text = "عدد الاختبارات: 0"
        status.text = "الحالة: الاختبار يعمل كل $seconds ثانية"
        testTask = executor.scheduleAtFixedRate({ runConnectivityTest() }, 0, seconds.toLong(), TimeUnit.SECONDS)
    }

    private fun stopTesting() {
        stopTaskOnly()
        status.text = "الحالة: متوقف"
    }

    private fun stopTaskOnly() {
        testTask?.cancel(true)
        testTask = null
    }

    private fun runConnectivityTest() {
        val start = System.nanoTime()
        var resultText: String
        var success = false
        var code: Int? = null
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false
            }
            code = connection.responseCode
            val elapsed = (System.nanoTime() - start) / 1_000_000
            success = code == 204 || (code != null && code in 200..299)
            resultText = if (success) "نجح الاتصال — زمن الاستجابة: ${elapsed} ms" else "استجابة غير متوقعة: HTTP $code"
        } catch (e: Exception) {
            resultText = "فشل الوصول للإنترنت (${e.javaClass.simpleName})"
        } finally {
            connection?.disconnect()
        }
        handler.post {
            testCount++
            latency.text = "آخر نتيجة: $resultText"
            results.text = "عدد الاختبارات: $testCount"
            updateConnectionLabel()
        }
    }

    private fun updateConnectionLabel() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        connection.text = when {
            caps == null -> "الشبكة الحالية: لا يوجد اتصال نشط"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "الشبكة الحالية: Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "الشبكة الحالية: بيانات الهاتف"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "الشبكة الحالية: Ethernet"
            else -> "الشبكة الحالية: اتصال آخر"
        }
    }


    private fun refreshNetworks() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 410)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            networks.text = "فعّل الموقع من إعدادات الهاتف ثم اضغط تحديث الشبكات مرة أخرى."
            return
        }
        networks.text = "جارٍ البحث عن الشبكات..."
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startScan()
        } catch (_: SecurityException) {
            networks.text = "لا توجد صلاحية لعرض الشبكات. اسمح بصلاحية الموقع والشبكات القريبة."
            return
        }
        handler.postDelayed({
            try {
                val list = wifi.scanResults
                    .distinctBy { it.BSSID ?: it.SSID }
                    .sortedByDescending { it.level }
                networks.text = if (list.isEmpty()) {
                    "لم تظهر شبكات. تأكد من تشغيل Wi-Fi والموقع، ثم حاول مرة أخرى."
                } else {
                    list.take(40).joinToString("\n") { item ->
                        val name = item.SSID.takeIf { it.isNotBlank() } ?: "شبكة مخفية"
                        "$name  —  الإشارة ${item.level} dBm  —  ${item.frequency} MHz"
                    }
                }
            } catch (_: SecurityException) {
                networks.text = "تعذّر قراءة الشبكات. راجع صلاحيات التطبيق."
            }
        }, 1800)
    }

    private fun choosePasswordFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        startActivityForResult(intent, 411)
    }

    @Deprecated("Deprecated in Android, retained for compatibility with the project's simple Activity setup")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 411 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val count = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    reader.lineSequence().count { it.isNotBlank() }
                } ?: 0
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف نصي"
                fileStatus.text = "تمت قراءة الملف: $fileName\nعدد السطور غير الفارغة: $count\nيتم فحص الملف فقط، ولا تُجرّب كلمات المرور تلقائيًا."
            } catch (e: Exception) {
                fileStatus.text = "تعذّرت قراءة الملف. اختَر ملفًا نصيًا TXT أو CSV."
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 410) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                refreshNetworks()
            } else {
                networks.text = "لازم تسمح بصلاحية الموقع والشبكات القريبة لعرض الشبكات على أندرويد 14."
            }
        }
    }

    override fun onDestroy() {
        stopTaskOnly()
        executor.shutdownNow()
        super.onDestroy()
    }
}