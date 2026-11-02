package h.Hchat.hooks.items.moments

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import h.Hchat.event.Events
import h.Hchat.hooks.api.sns.SnsContextMenuDispatcher
import h.Hchat.hooks.api.sns.SnsContextMenuTarget
import h.Hchat.hooks.api.sns.SnsForwardContentResolver
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MomentsLocationFeature : BaseFeature() {
    private var resolver: SnsForwardContentResolver? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈定位查看"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsLocationSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        resolver = SnsForwardContentResolver(context, ::logFeatureError)
        val prefs = HchatStorage.preferences(
            context.hostContext(),
            MomentsLocationSettings.PREFS_NAME
        )
        SnsContextMenuDispatcher.register(
            SnsContextMenuDispatcher.Entry(
                owner = OWNER,
                itemId = MENU_ID,
                title = "查看定位[H]",
                order = 45,
                isEnabled = {
                    prefs.getBoolean(
                        MomentsLocationSettings.KEY_ENABLE,
                        MomentsLocationSettings.DEFAULT_ENABLE
                    )
                },
                isApplicable = ::isNormalMomentsPost,
                onClick = { activity, target -> showLocation(activity, target) }
            )
        )
        scheduleInstall(context)
        subscribe(Events.DexReady::class.java) { scheduleInstall(context) }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        SnsContextMenuDispatcher.unregister(OWNER)
        resolver = null
    }

    private fun scheduleInstall(context: FeatureContext) {
        DexInstallScheduler.schedule("${ID}_sns_menu", name()) {
            val current = resolver ?: return@schedule false
            SnsContextMenuDispatcher.install(context, current, ::logFeatureError)
        }
    }

    private fun isNormalMomentsPost(target: SnsContextMenuTarget): Boolean {
        if (target.snsId == null) return false
        val nativeInfo = target.nativeInfo ?: return false
        return KavaReflector.invokeMethod(nativeInfo, "isAd") != true
    }

    private fun showLocation(activity: android.app.Activity, target: SnsContextMenuTarget) {
        val info = runCatching { resolveLocation(target) }
            .onFailure { logFeatureError("解析朋友圈定位失败", it) }
            .getOrNull()
        if (info == null || info.isEmpty()) {
            Toast.makeText(activity, "这条动态没有带位置信息", Toast.LENGTH_SHORT).show()
            return
        }
        val message = info.describe()
        val title = "📍 " + info.displayName().ifBlank { "位置信息" }
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .setNegativeButton("复制") { _, _ ->
                copyText(activity, message)
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            }
        if (info.latitude != null && info.longitude != null) {
            builder.setNeutralButton("地图") { _, _ -> openMap(activity, info) }
        }
        builder.show()
    }

    private fun resolveLocation(target: SnsContextMenuTarget): MomentsLocationInfo? {
        val nativeInfo = target.nativeInfo ?: return null
        val timeline = KavaReflector.invokeMethod(nativeInfo, "getTimeLine") ?: return null
        val location = findLocationObject(timeline)
        if (location == null) {
            logError(
                "[Hchat:MomentsLocation] timeline 未找到 location 字段，字段清单=" +
                    KavaReflector.declaredFields(timeline.javaClass)
                        .joinToString(",") { "${it.name}:${it.type.simpleName}" },
                null
            )
            return null
        }
        return MomentsLocationInfo.from(location, createTimeText(timeline))
    }

    private fun findLocationObject(timeline: Any): Any? {
        KavaReflector.invokeMethod(timeline, "getLocation")?.let { return it }
        KavaReflector.invokeMethod(timeline, "getPoiLocation")?.let { return it }
        return KavaReflector.declaredFields(timeline.javaClass)
            .asSequence()
            .filter { field ->
                field.type != String::class.java &&
                    field.name.contains("ocation", ignoreCase = true)
            }
            .mapNotNull { field -> KavaReflector.readField(field, timeline) }
            .firstOrNull()
    }

    private fun createTimeText(timeline: Any): String {
        val raw = KavaReflector.readField(timeline, "CreateTime")
            ?: KavaReflector.invokeMethod(timeline, "getCreateTime")
            ?: return ""
        val value = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: return ""
            else -> return ""
        }
        if (value <= 0L) return ""
        val millis = if (value > 1_000_000_000_000L) value else value * 1000L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
        }.getOrDefault("")
    }

    private fun copyText(context: Context, text: String) {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("朋友圈定位", text))
        }
    }

    private fun openMap(activity: android.app.Activity, info: MomentsLocationInfo) {
        val lat = info.latitude ?: return
        val lng = info.longitude ?: return
        val label = Uri.encode(info.displayName())
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(activity, "没有可用的地图应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "moments_location"
        private const val OWNER = "moments_location_menu"
        private const val MENU_ID = 0x48434c4f
    }
}

internal data class MomentsLocationInfo(
    val name: String = "",
    val address: String = "",
    val country: String = "",
    val province: String = "",
    val city: String = "",
    val district: String = "",
    val scale: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createTime: String = ""
) {
    fun isEmpty(): Boolean {
        return name.isBlank() && address.isBlank() && latitude == null && longitude == null
    }

    fun displayName(): String {
        return name.ifBlank { address }.ifBlank { city }
    }

    fun describe(): String {
        val lines = mutableListOf<String>()
        if (name.isNotBlank()) lines += "地点：$name"
        if (address.isNotBlank() && address != name) lines += "地址：$address"
        val region = listOf(country, province, city, district)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (region.isNotBlank()) lines += "区域：$region"
        if (latitude != null && longitude != null) {
            lines += String.format(Locale.US, "经纬度：%.6f, %.6f", latitude, longitude)
        }
        if (scale.isNotBlank()) lines += "类型：$scale"
        if (createTime.isNotBlank()) lines += "发布时间：$createTime"
        return lines.joinToString("\n")
    }

    companion object {
        fun from(location: Any, createTime: String): MomentsLocationInfo {
            var name = ""
            var address = ""
            var country = ""
            var province = ""
            var city = ""
            var district = ""
            var scale = ""
            var latitude: Double? = null
            var longitude: Double? = null

            KavaReflector.declaredFields(location.javaClass).forEach { field ->
                val key = field.name.lowercase(Locale.US)
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                val value = KavaReflector.readField(field, location) ?: return@forEach
                when {
                    key.contains("latitude") -> latitude = toCoordinate(value)
                    key.contains("longitude") -> longitude = toCoordinate(value)
                    key.contains("poiname") || key == "name" -> {
                        (value as? String)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    key.contains("address") -> {
                        (value as? String)?.takeIf { it.isNotBlank() }?.let { address = it }
                    }
                    key.contains("country") -> {
                        (value as? String)?.takeIf { it.isNotBlank() }?.let { country = it }
                    }
                    key.contains("province") -> {
                        (value as? String)?.takeIf { it.isNotBlank() }?.let { province = it }
                    }
                    key.contains("city") -> {
                        (value as? String)?.takeIf { it.isNotBlank() }?.let { city = it }
                    }
                    key.contains("district") -> {
                        (value as? String)?.takeIf { it.isNotBlank() }?.let { district = it }
                    }
                    key.contains("scale") -> {
                        (value as? String)?.takeIf { it.isNotBlank() }?.let { scale = it }
                    }
                }
            }

            return MomentsLocationInfo(
                name = name,
                address = address,
                country = country,
                province = province,
                city = city,
                district = district,
                scale = scale,
                latitude = latitude,
                longitude = longitude,
                createTime = createTime
            )
        }

        private fun toCoordinate(value: Any): Double? {
            val raw = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: return null
                else -> return null
            }
            if (raw == 0.0) return null
            val normalized = if (raw > 180.0 || raw < -180.0) raw / 1_000_000.0 else raw
            return normalized.takeIf { it in -180.0..180.0 }
        }
    }
}