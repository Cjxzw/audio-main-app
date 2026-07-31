package com.agent.voiceassistant.hub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HubAuthenticator {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(baseUrl: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        require(username.isNotBlank()) { "请输入 Hub 账号" }
        require(password.isNotBlank()) { "请输入 Hub 密码" }
        val url = HubSettings.normalizeBaseUrl(baseUrl).toHttpUrlOrThrow()
            .newBuilder()
            .addPathSegments("api/main/login")
            .build()
        val body = buildJsonObject {
            put("username", JsonPrimitive(username.trim()))
            put("password", JsonPrimitive(password))
        }.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(responseBody).jsonObject["detail"]?.jsonPrimitive?.content
                }.getOrNull()
                error(message ?: "Hub 登录失败（HTTP ${response.code}）")
            }
            val token = runCatching {
                json.parseToJsonElement(responseBody).jsonObject["accessToken"]?.jsonPrimitive?.content
            }.getOrNull().orEmpty()
            require(token.isNotBlank()) { "Hub 登录响应缺少连接凭据" }
            token
        }
    }

}

private fun String.toHttpUrlOrThrow(): okhttp3.HttpUrl = toHttpUrl()
