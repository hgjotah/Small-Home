package com.ia.smallhome.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ia.smallhome.model.CryptoAsset
import com.ia.smallhome.model.HomeAssistantEntities
import com.ia.smallhome.model.HomeAssistantEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String, val code: Int? = null) : ApiResult<Nothing>
}

class HomeAssistantClient(private val client: OkHttpClient) {
    suspend fun loadEntities(baseUrl: String, token: String): ApiResult<HomeAssistantEntities> = withContext(Dispatchers.IO) {
        if (!validBaseUrl(baseUrl)) return@withContext ApiResult.Failure("La URL debe empezar por http:// o https://")
        if (token.isBlank()) return@withContext ApiResult.Failure("Introduce el Long-Lived Access Token")
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/states")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        executeJson(request, ::parseHomeAssistantEntities)
    }

    private fun validBaseUrl(url: String): Boolean = runCatching {
        val parsed = url.trimEnd('/').toHttpUrl()
        parsed.scheme == "http" || parsed.scheme == "https"
    }.getOrDefault(false)

    private fun <T> executeJson(request: Request, mapper: (String) -> T): ApiResult<T> = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 401 -> ApiResult.Failure("Home Assistant rechazó el token", 401)
                !response.isSuccessful -> ApiResult.Failure("Home Assistant respondió HTTP ${response.code}", response.code)
                body.isBlank() -> ApiResult.Failure("Home Assistant devolvió una respuesta vacía")
                else -> runCatching { ApiResult.Success(mapper(body)) }
                    .getOrElse { ApiResult.Failure("La respuesta de Home Assistant no es JSON válido") }
            }
        }
    } catch (_: SocketTimeoutException) {
        ApiResult.Failure("Home Assistant no respondió a tiempo")
    } catch (_: IOException) {
        ApiResult.Failure("No se pudo conectar con Home Assistant")
    }
}

internal fun parseHomeAssistantEntities(body: String): HomeAssistantEntities {
    val states = JsonParser.parseString(body).asJsonArray
    val entities = states.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val entity = element.asJsonObject
        val id = entity.get("entity_id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (!com.ia.smallhome.model.PanelRules.validHomeAssistantEntity(id)) return@mapNotNull null
        val attributes = entity.get("attributes")?.takeIf { it.isJsonObject }?.asJsonObject
        val friendlyName = attributes
            ?.get("friendly_name")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() } ?: id
        HomeAssistantEntity(
            id = id,
            name = friendlyName,
            domain = id.substringBefore('.', "sin_dominio"),
            state = entity.get("state")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
        )
    }.distinctBy { it.id }.sortedWith(compareBy<HomeAssistantEntity> { it.domain }.thenBy { it.name.lowercase() })
    return HomeAssistantEntities(entities)
}

class CoinMarketCapClient(private val client: OkHttpClient) {
    suspend fun search(query: String, apiKey: String): ApiResult<List<CryptoAsset>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ApiResult.Failure("Introduce la API key de CoinMarketCap")
        val clean = query.trim()
        if (clean.isBlank()) return@withContext ApiResult.Failure("Escribe un nombre o símbolo")
        val directSymbol = clean.uppercase().takeIf { it.matches(Regex("[A-Z0-9]{2,12}")) }
        val url = if (directSymbol != null) {
            "https://pro-api.coinmarketcap.com/v1/cryptocurrency/map?listing_status=active&symbol=$directSymbol&aux=is_active"
        } else {
            "https://pro-api.coinmarketcap.com/v1/cryptocurrency/map?listing_status=active&sort=cmc_rank&limit=5000&aux=is_active"
        }
        val request = Request.Builder()
            .url(url)
            .header("X-CMC_PRO_API_KEY", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> ApiResult.Failure("CoinMarketCap rechazó la API key", 401)
                    response.code == 429 -> ApiResult.Failure("CoinMarketCap ha limitado temporalmente las peticiones", 429)
                    !response.isSuccessful -> ApiResult.Failure("CoinMarketCap respondió HTTP ${response.code}", response.code)
                    else -> parseAssets(body, clean)
                }
            }
        } catch (_: SocketTimeoutException) {
            ApiResult.Failure("CoinMarketCap no respondió a tiempo")
        } catch (_: IOException) {
            ApiResult.Failure("No se pudo conectar con CoinMarketCap")
        }
    }

    private fun parseAssets(body: String, query: String): ApiResult<List<CryptoAsset>> = runCatching {
        val data = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
        val needle = query.lowercase()
        data.mapNotNull { element ->
            val asset = element.asJsonObject
            val active = asset.get("is_active")?.asInt ?: 1
            val id = asset.get("id")?.asLong ?: return@mapNotNull null
            val name = asset.get("name")?.asString.orEmpty()
            val symbol = asset.get("symbol")?.asString.orEmpty()
            if (active == 1 && (name.lowercase().contains(needle) || symbol.lowercase().contains(needle))) {
                CryptoAsset(id, name, symbol)
            } else null
        }.distinctBy { it.id }.take(30)
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Failure("CoinMarketCap devolvió JSON no válido") },
    )
}

data class ChatMessage(val role: String, val content: String)

class OpenRouterClient(private val client: OkHttpClient) {
    suspend fun complete(apiKey: String, model: String, messages: List<ChatMessage>): ApiResult<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ApiResult.Failure("Falta la API key de OpenRouter")
        if (model.isBlank()) return@withContext ApiResult.Failure("Falta el Model ID de OpenRouter")
        val body = JsonObject().apply {
            addProperty("model", model.trim())
            add("messages", com.google.gson.Gson().toJsonTree(messages))
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://smallhome.local")
            .header("X-Title", "Small Home")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                when (response.code) {
                    401 -> ApiResult.Failure("OpenRouter rechazó la API key", 401)
                    402 -> ApiResult.Failure("La cuenta de OpenRouter no tiene crédito suficiente", 402)
                    429 -> ApiResult.Failure("OpenRouter ha limitado temporalmente las peticiones", 429)
                    else -> when {
                        !response.isSuccessful -> ApiResult.Failure(openRouterError(responseBody, response.code), response.code)
                        responseBody.isBlank() -> ApiResult.Failure("OpenRouter devolvió una respuesta vacía")
                        else -> parseCompletion(responseBody)
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            ApiResult.Failure("OpenRouter no respondió a tiempo")
        } catch (_: IOException) {
            ApiResult.Failure("No se pudo conectar con OpenRouter")
        }
    }

    private fun parseCompletion(body: String): ApiResult<String> = runCatching {
        JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")?.get("content")?.asString.orEmpty().trim()
    }.fold(
        onSuccess = { if (it.isBlank()) ApiResult.Failure("El modelo devolvió una respuesta vacía") else ApiResult.Success(it) },
        onFailure = { ApiResult.Failure("OpenRouter devolvió JSON no válido") },
    )

    private fun openRouterError(body: String, code: Int): String = runCatching {
        JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.get("message")?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "OpenRouter respondió HTTP $code; revisa el Model ID"

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
