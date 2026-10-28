package com.example.camwall

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 常见品牌的 RTSP 路径预设（主码流 / 子码流） */
data class Brand(val key: String, val label: String, val main: String, val sub: String)

val BRANDS = listOf(
    Brand("hik", "海康威视 Hikvision", "/Streaming/Channels/101", "/Streaming/Channels/102"),
    Brand("dahua", "大华 Dahua / 乐橙", "/cam/realmonitor?channel=1&subtype=0", "/cam/realmonitor?channel=1&subtype=1"),
    Brand("ezviz", "萤石 / 海康旧款", "/h264/ch1/main/av_stream", "/h264/ch1/sub/av_stream"),
    Brand("uniview", "宇视 Uniview", "/media/video1", "/media/video2"),
    Brand("tplink", "TP-Link 普联", "/stream1", "/stream2"),
    Brand("custom", "自定义", "/", ""),
)

data class Device(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var ip: String = "",
    var port: Int = 554,
    var user: String = "admin",
    var pass: String = "",
    var brand: String = "hik",
    var mainPath: String = BRANDS[0].main,
    var subPath: String = BRANDS[0].sub,
) {
    /** 生成 rtsp 地址。IP 栏里直接填完整 rtsp:// 地址也可以。 */
    fun rtspUrl(sub: Boolean): String {
        val raw = ip.trim()
        if (raw.startsWith("rtsp://", ignoreCase = true)) return raw
        var path = if (sub && subPath.isNotBlank()) subPath.trim() else mainPath.trim()
        if (!path.startsWith("/")) path = "/$path"
        val auth = if (user.isNotEmpty()) "${Uri.encode(user)}:${Uri.encode(pass)}@" else ""
        val host = if (raw.contains(':') && !raw.startsWith("[")) "[$raw]" else raw // IPv6
        return "rtsp://$auth$host:$port$path"
    }

    /** 给界面显示用，密码打码 */
    fun displayUrl(sub: Boolean): String =
        rtspUrl(sub).replace(Regex("(rtsp://[^:/@]+:)[^@]*@"), "$1****@")

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("ip", ip); put("port", port)
        put("user", user); put("pass", pass); put("brand", brand)
        put("main", mainPath); put("sub", subPath)
    }

    companion object {
        fun fromJson(o: JSONObject) = Device(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            ip = o.optString("ip"),
            port = o.optInt("port", 554),
            user = o.optString("user"),
            pass = o.optString("pass"),
            brand = o.optString("brand", "custom"),
            mainPath = o.optString("main", "/"),
            subPath = o.optString("sub", ""),
        )
    }
}

class Settings(
    var layout: Int = 4,            // 1 / 4 / 9
    var page: Int = 0,
    var highPct: Int = 80,          // 触摸后的亮度
    var lowPct: Int = 1,            // 待机最低亮度
    var rampUpSec: Float = 1.5f,    // 从最低升到高亮所需时间
    var holdSec: Float = 10f,       // 最后一次触摸后保持高亮多久
    var fadeSec: Float = 50f,       // 然后用多久渐暗到最低
    var subStreamInGrid: Boolean = true, // 4/9 分屏时用子码流
    var wakeTouchOnly: Boolean = true,   // 暗屏时第一下触摸只负责点亮
) {
    fun toJson() = JSONObject().apply {
        put("layout", layout); put("page", page); put("high", highPct); put("low", lowPct)
        put("ramp", rampUpSec.toDouble()); put("hold", holdSec.toDouble()); put("fade", fadeSec.toDouble())
        put("subGrid", subStreamInGrid); put("wakeOnly", wakeTouchOnly)
    }

    companion object {
        fun fromJson(o: JSONObject) = Settings(
            layout = o.optInt("layout", 4).let { if (it in listOf(1, 4, 9)) it else 4 },
            page = o.optInt("page", 0),
            highPct = o.optInt("high", 80),
            lowPct = o.optInt("low", 1),
            rampUpSec = o.optDouble("ramp", 1.5).toFloat(),
            holdSec = o.optDouble("hold", 10.0).toFloat(),
            fadeSec = o.optDouble("fade", 50.0).toFloat(),
            subStreamInGrid = o.optBoolean("subGrid", true),
            wakeTouchOnly = o.optBoolean("wakeOnly", true),
        )
    }
}

/** 设备与设置保存在本应用私有存储里 */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("camwall", Context.MODE_PRIVATE)

    val devices: MutableList<Device> = runCatching {
        val arr = JSONArray(prefs.getString("devices", "[]"))
        MutableList(arr.length()) { Device.fromJson(arr.getJSONObject(it)) }
    }.getOrElse { mutableListOf() }

    val settings: Settings = runCatching {
        Settings.fromJson(JSONObject(prefs.getString("settings", "{}")!!))
    }.getOrElse { Settings() }

    fun saveDevices() {
        val arr = JSONArray().also { a -> devices.forEach { a.put(it.toJson()) } }
        prefs.edit().putString("devices", arr.toString()).apply()
    }

    fun saveSettings() {
        prefs.edit().putString("settings", settings.toJson().toString()).apply()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
