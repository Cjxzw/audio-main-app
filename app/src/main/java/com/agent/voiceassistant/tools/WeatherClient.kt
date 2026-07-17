package com.agent.voiceassistant.tools

import com.agent.voiceassistant.data.StoredLocation
import com.agent.voiceassistant.cloud.NetworkTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun getCurrent(location: StoredLocation): String = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m",
            )
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "2")
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
                    return@withContext parseWeather(body, location)
                }
            } catch (error: InterruptedIOException) {
                lastTimeout = error
            }
        }
        throw NetworkTimeoutException("weather", lastTimeout)
    }

    private fun parseWeather(body: String, location: StoredLocation): String {
        val root = json.parseToJsonElement(body).jsonObject
        val current = root["current"]?.jsonObject ?: JsonObject(emptyMap())
        val daily = root["daily"]?.jsonObject

        val temp = current.double("temperature_2m")
        val apparent = current.double("apparent_temperature")
        val humidity = current.int("relative_humidity_2m")
        val precipitation = current.double("precipitation")
        val wind = current.double("wind_speed_10m")
        val weatherCode = current.int("weather_code")
        val desc = weatherDescription(weatherCode)

        val todayMin = daily?.arrayDouble("temperature_2m_min", 0)
        val todayMax = daily?.arrayDouble("temperature_2m_max", 0)
        val rainProb = daily?.arrayInt("precipitation_probability_max", 0)

        val place = location.address?.takeIf { it.isNotBlank() }
        return buildString {
            if (place != null) {
                append("$place 现在$desc")
            } else {
                append("现在$desc")
            }
            if (temp != null) append("，${temp.round1()}度")
            if (apparent != null) append("，体感 ${apparent.round1()}度")
            if (humidity != null) append("，湿度 $humidity%")
            if (wind != null) append("，风速 ${wind.round1()}公里每小时")
            if (precipitation != null && precipitation > 0.0) append("，降水 ${precipitation.round1()}毫米")
            if (todayMin != null && todayMax != null) append("。今天 ${todayMin.round1()} 到 ${todayMax.round1()}度")
            if (rainProb != null) append("，降水概率 $rainProb%")
            append("。")
        }
    }

    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.arrayDouble(key: String, index: Int): Double? =
        (this[key] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.arrayInt(key: String, index: Int): Int? =
        (this[key] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.intOrNull

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
