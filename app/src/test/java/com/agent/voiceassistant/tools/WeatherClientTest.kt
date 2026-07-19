package com.agent.voiceassistant.tools

import com.agent.voiceassistant.data.StoredLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeatherClientTest {
    private val client = WeatherClient()
    private val location = StoredLocation(
        latitude = 38.04,
        longitude = 114.51,
        address = "河北省石家庄市",
    )

    @Test
    fun `selects requested date and summarizes hourly hazards`() {
        val result = client.parseWeather(
            body = forecastJson,
            location = location,
            targetDate = LocalDate.parse("2026-07-18"),
        )

        assertTrue(result.contains("2026-07-18"))
        assertTrue(result.contains("最低 20.0 到最高 26.0度"))
        assertTrue(result.contains("14时到15时"))
        assertTrue(result.contains("可能出现较强降雨"))
        assertTrue(result.contains("19时到20时"))
        assertTrue(result.contains("55.0公里每小时"))
        assertFalse(result.contains("现在多云"))
    }

    @Test
    fun `uses current conditions when date is omitted`() {
        val result = client.parseWeather(forecastJson, location)

        assertTrue(result.contains("现在多云"))
        assertTrue(result.contains("25.0度"))
        assertTrue(result.contains("最低 22.0 到最高 30.0度"))
    }

    private val forecastJson = """
        {
          "current": {
            "temperature_2m": 25.0,
            "apparent_temperature": 26.0,
            "relative_humidity_2m": 70,
            "precipitation": 0.0,
            "weather_code": 2,
            "wind_speed_10m": 12.0
          },
          "daily": {
            "time": ["2026-07-17", "2026-07-18"],
            "weather_code": [2, 61],
            "temperature_2m_max": [30.0, 26.0],
            "temperature_2m_min": [22.0, 20.0],
            "precipitation_probability_max": [10, 90],
            "precipitation_sum": [0.0, 12.5],
            "wind_speed_10m_max": [20.0, 35.0],
            "wind_gusts_10m_max": [30.0, 55.0]
          },
          "hourly": {
            "time": [
              "2026-07-17T12:00",
              "2026-07-18T13:00",
              "2026-07-18T14:00",
              "2026-07-18T15:00",
              "2026-07-18T19:00",
              "2026-07-18T20:00"
            ],
            "precipitation_probability": [0, 10, 75, 90, 20, 10],
            "precipitation": [0.0, 0.0, 2.5, 6.0, 0.0, 0.0],
            "wind_speed_10m": [10.0, 10.0, 12.0, 15.0, 32.0, 35.0],
            "wind_gusts_10m": [15.0, 20.0, 25.0, 30.0, 48.0, 55.0]
          }
        }
    """.trimIndent()
}
