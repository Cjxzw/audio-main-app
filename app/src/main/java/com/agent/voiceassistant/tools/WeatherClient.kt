package com.agent.voiceassistant.tools

import com.agent.voiceassistant.data.StoredLocation
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun getCurrent(location: StoredLocation): String = getForecast(location)

    suspend fun getForecast(location: StoredLocation, requestedDate: String? = null): String = withContext(Dispatchers.IO) {
        val targetDate = resolveDate(requestedDate)
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m",
            )
            .addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min," +
                    "precipitation_probability_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max",
            )
            .addQueryParameter(
                "hourly",
                "temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m",
            )
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("wind_speed_unit", "kmh")
            .addQueryParameter("forecast_days", "7")
            .build()

        val request = Request.Builder().url(url).build()
        var lastTimeout: IOException? = null
        repeat(MAX_ATTEMPTS) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("weather HTTP ${response.code}: ${body.take(300)}")
                    }
                    return@withContext parseWeather(body, location, targetDate)
                }
            } catch (error: InterruptedIOException) {
                lastTimeout = error
            }
        }
        throw NetworkTimeoutException("weather", lastTimeout)
    }

    internal fun parseWeather(body: String, location: StoredLocation, targetDate: LocalDate? = null): String {
        val root = json.parseToJsonElement(body).jsonObject
        val current = root["current"]?.jsonObject ?: JsonObject(emptyMap())
        val daily = root["daily"]?.jsonObject
        val dailyTimes = daily?.stringArray("time").orEmpty()
        val selectedDate = targetDate?.toString() ?: dailyTimes.firstOrNull()
        val selectedIndex = selectedDate?.let { dailyTimes.indexOf(it) } ?: 0
        if (selectedIndex < 0) {
            return "天气接口暂未覆盖 $selectedDate 的预报，目前只能查询未来 7 天内的天气。"
        }

        val isToday = selectedIndex == 0
        val temp = current.double("temperature_2m").takeIf { isToday }
        val apparent = current.double("apparent_temperature").takeIf { isToday }
        val humidity = current.int("relative_humidity_2m").takeIf { isToday }
        val precipitation = current.double("precipitation").takeIf { isToday }
        val wind = current.double("wind_speed_10m").takeIf { isToday }
        val weatherCode = if (isToday) {
            current.int("weather_code")
        } else {
            daily?.arrayInt("weather_code", selectedIndex)
        }
        val desc = weatherDescription(weatherCode)
        val min = daily?.arrayDouble("temperature_2m_min", selectedIndex)
        val max = daily?.arrayDouble("temperature_2m_max", selectedIndex)
        val rainProb = daily?.arrayInt("precipitation_probability_max", selectedIndex)
        val rainSum = daily?.arrayDouble("precipitation_sum", selectedIndex)
        val gustMax = daily?.arrayDouble("wind_gusts_10m_max", selectedIndex)

        val place = location.address?.takeIf { it.isNotBlank() }
        return buildString {
            if (isToday) {
                if (place != null) append("$place 现在$desc") else append("现在$desc")
                if (temp != null) append("，${temp.round1()}度")
                if (apparent != null) append("，体感 ${apparent.round1()}度")
                if (humidity != null) append("，湿度 $humidity%")
                if (wind != null) append("，风速 ${wind.round1()}公里每小时")
                if (precipitation != null && precipitation > 0.0) append("，当前降水 ${precipitation.round1()}毫米")
            } else {
                if (place != null) append("$place $selectedDate 预计$desc") else append("$selectedDate 预计$desc")
            }
            if (min != null && max != null) append("，最低 ${min.round1()} 到最高 ${max.round1()}度")
            if (rainProb != null) append("，全天降水概率 $rainProb%")
            if (rainSum != null && rainSum > 0.0) append("，预计降水量 ${rainSum.round1()}毫米")
            if (gustMax != null) append("，最大阵风 ${gustMax.round1()}公里每小时")
            append("。")
            hourlyHighlights(root["hourly"]?.jsonObject, selectedDate)?.let {
                append(it)
            }
        }
    }

    private fun resolveDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when {
            value.isBlank() || value == "today" || value == "今天" -> null
            value == "tomorrow" || value == "明天" -> LocalDate.now().plusDays(1)
            value == "后天" -> LocalDate.now().plusDays(2)
            else -> LocalDate.parse(value)
        }
    }

    private fun hourlyHighlights(hourly: JsonObject?, date: String?): String? {
        if (hourly == null || date.isNullOrBlank()) return null
        val times = hourly.stringArray("time")
        val rainProb = hourly.doubleArray("precipitation_probability")
        val precipitation = hourly.doubleArray("precipitation")
        val wind = hourly.doubleArray("wind_speed_10m")
        val gusts = hourly.doubleArray("wind_gusts_10m")
        val points = times.mapIndexedNotNull { index, time ->
            if (!time.startsWith(date)) return@mapIndexedNotNull null
            HourlyPoint(
                index = index,
                time = time,
                rainProbability = rainProb.getOrNull(index) ?: 0.0,
                precipitation = precipitation.getOrNull(index) ?: 0.0,
                windSpeed = wind.getOrNull(index) ?: 0.0,
                gust = gusts.getOrNull(index) ?: 0.0,
            )
        }
        if (points.isEmpty()) return null

        val rain = points.filter { it.rainProbability >= 60.0 || it.precipitation >= 2.0 }
        val strongWind = points.filter { it.gust >= 40.0 || it.windSpeed >= 30.0 }
        if (rain.isEmpty() && strongWind.isEmpty()) return "暂无明显的强降雨或大风时段。"

        return buildString {
            if (rain.isNotEmpty()) {
                val peak = rain.maxOf { it.precipitation }
                append("预计 ${formatRanges(rain)} 有较高降雨概率")
                if (peak >= 5.0) append("，可能出现较强降雨")
                append("。")
            }
            if (strongWind.isNotEmpty()) {
                val peak = strongWind.maxOf { maxOf(it.gust, it.windSpeed) }
                append("预计 ${formatRanges(strongWind)} 风力较强，峰值约 ${peak.round1()}公里每小时。")
            }
        }
    }

    private fun formatRanges(points: List<HourlyPoint>): String {
        val groups = mutableListOf<MutableList<HourlyPoint>>()
        points.forEach { point ->
            val group = groups.lastOrNull()
            if (group == null || point.index != group.last().index + 1) {
                groups += mutableListOf(point)
            } else {
                group += point
            }
        }
        return groups.take(3).joinToString("、") { group ->
            val start = hourLabel(group.first().time)
            val end = hourLabel(group.last().time)
            if (start == end) start else "${start}到$end"
        }
    }

    private fun hourLabel(value: String): String = value.substringAfter('T').take(2) + "时"

    private data class HourlyPoint(
        val index: Int,
        val time: String,
        val rainProbability: Double,
        val precipitation: Double,
        val windSpeed: Double,
        val gust: Double,
    )

    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.arrayDouble(key: String, index: Int): Double? =
        (this[key] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.arrayInt(key: String, index: Int): Int? =
        (this[key] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.intOrNull

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun JsonObject.doubleArray(key: String): List<Double> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()

    private fun Double.round1(): String = "%.1f".format(this)

    private companion object {
        const val MAX_ATTEMPTS = 2
    }

    private fun weatherDescription(code: Int?): String = when (code) {
        0 -> "晴"
        1, 2, 3 -> "多云"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "下雨"
        66, 67 -> "冻雨"
        71, 73, 75 -> "下雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "天气状态未知"
    }
}
