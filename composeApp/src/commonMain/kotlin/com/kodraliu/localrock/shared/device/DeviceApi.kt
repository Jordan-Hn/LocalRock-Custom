package com.kodraliu.localrock.shared.device

import com.kodraliu.localrock.shared.http.ProdJson
import com.kodraliu.localrock.shared.http.serverErrorText
import com.kodraliu.localrock.shared.model.FirmwareOtaResponse
import com.kodraliu.localrock.shared.model.FirmwareStartResult
import com.kodraliu.localrock.shared.model.FirmwareUpdateInfo
import com.kodraliu.localrock.shared.model.Home
import com.kodraliu.localrock.shared.model.HomeDetail
import com.kodraliu.localrock.shared.model.LastKnown
import com.kodraliu.localrock.shared.model.Room
import com.kodraliu.localrock.shared.model.Scene
import com.kodraliu.localrock.shared.model.SceneRunResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.setBody
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

class DeviceApi(private val client: HttpClient) {


    suspend fun ncPrepare(): JsonElement = client.get("/nc/prepare").body()


    suspend fun getHomeDetail(token: String): HomeDetail {
        return client.get("/api/v1/getHomeDetail") {
            header(HttpHeaders.Authorization, token)
        }.unwrapData()
    }

    suspend fun getHome(homeId: Long): Home {
        return client.get("/v3/user/homes/$homeId").unwrapData()
    }

    /**
     * Create a cloud room, or get the existing one with the same name (the server matches names
     * case-insensitively). Returns the room id the robot's segment table should point at.
     */
    suspend fun createRoom(homeId: Long, name: String): Room {
        // Only `data` is read: the server puts the bare room id in `result` for the official app.
        return client.submitForm(
            url = "/user/homes/$homeId/rooms",
            formParameters = parameters { append("name", name) },
        ).unwrapData()
    }

    /** The voice-pkg `info` list for [model]; the server passes it through with pack URLs pointing at itself. */
    suspend fun voicePackages(model: String): JsonElement {
        val resp = client.get("/voice-pkg/info") { parameter("model", model) }
        if (!resp.status.isSuccess()) {
            throw DeviceApiException(resp.status.value, "Voice packs unavailable (HTTP ${resp.status.value})")
        }
        return resp.body()
    }

    /** The routines saved for one robot, in the order the server keeps them. */
    suspend fun getScenes(duid: String): List<Scene> {
        val resp = client.get("/user/scene/device/$duid")
        if (!resp.status.isSuccess()) {
            throw DeviceApiException(resp.status.value, "Routines unavailable (HTTP ${resp.status.value})")
        }
        val response: SceneListResponse = resp.body()
        if (response.code != 200) throw DeviceApiException(response.code, response.msg)
        return response.result ?: response.data ?: emptyList()
    }

    /** Asks the server to run a routine; the server sends the robot each step itself. */
    suspend fun executeScene(sceneId: Long): SceneRunResult {
        val resp = client.post("/user/scene/$sceneId/execute")
        if (!resp.status.isSuccess()) {
            throw DeviceApiException(resp.status.value, "The server could not run the routine (HTTP ${resp.status.value})")
        }
        val response: SceneRunResponse = resp.body()
        if (response.code != 200) throw DeviceApiException(response.code, response.msg)
        return response.result ?: response.data ?: SceneRunResult()
    }

    /** Saves a new routine in the official app's layout; returns it with the id the server gave it. */
    suspend fun createScene(name: String, duid: String, homeId: Long?, param: JsonObject): Scene {
        val body = buildJsonObject {
            put("name", name)
            put("enabled", true)
            put("type", "WORKFLOW")
            homeId?.let { put("homeId", it) }
            put("deviceId", duid)
            put("param", param)
        }
        val resp = client.post("/v2/user/scene") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return resp.sceneWriteResult("save the routine")
            ?.let { runCatching { ProdJson.decodeFromJsonElement<Scene>(it) }.getOrNull() }
            ?: throw DeviceApiException(resp.status.value, "The server did not return the new routine")
    }

    /** Replaces a routine's steps. The param carries its triggers, so the schedule is kept. */
    suspend fun updateSceneParam(sceneId: Long, param: JsonObject) {
        val resp = client.put("/user/scene/$sceneId/param") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("param", param) }.toString())
        }
        resp.sceneWriteResult("update the routine")
    }

    suspend fun renameScene(sceneId: Long, name: String) {
        val resp = client.put("/user/scene/$sceneId/name") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name) }.toString())
        }
        resp.sceneWriteResult("rename the routine")
    }

    suspend fun deleteScene(sceneId: Long) {
        client.delete("/user/scene/$sceneId").sceneWriteResult("delete the routine")
    }

    /** Checks a routine write went through; returns the reply's `result`/`data`, which may be null. */
    private suspend fun HttpResponse.sceneWriteResult(action: String): JsonElement? {
        if (!status.isSuccess()) throw DeviceApiException(status.value, "The server couldn't $action (HTTP ${status.value})")
        val reply = runCatching { ProdJson.parseToJsonElement(bodyAsText()) as? JsonObject }.getOrNull()
            ?: return null
        val code = (reply["code"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (code != null && code != 200) {
            throw DeviceApiException(code, (reply["msg"] as? JsonPrimitive)?.content ?: "The server couldn't $action")
        }
        return reply["result"]?.takeUnless { it is JsonNull } ?: reply["data"]?.takeUnless { it is JsonNull }
    }

    /** The robot's last-known state from the server, with its last map when [includeMap]. */
    suspend fun lastKnown(duid: String, includeMap: Boolean): LastKnown {
        return client.get("/user/devices/$duid/last_known") {
            if (includeMap) parameter("map", "1")
        }.unwrapData()
    }

    /** The Update button: asks the server to install the version the cloud offers. */
    suspend fun startFirmwareUpdate(duid: String): FirmwareStartResult {
        val resp = client.post("/ota/device/$duid/upgrade") { header(HttpHeaders.Accept, "application/json") }
        if (!resp.status.isSuccess()) {
            throw DeviceApiException(resp.status.value, firmwareStartRefusal(resp.status.value))
        }
        val root = resp.body<JsonObject>()
        val data = (root["data"] ?: root["result"]) as? JsonObject
        fun text(key: String) = (data?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val started = (data?.get("started") as? JsonPrimitive)?.content == "true"
        // The server answers its own refusals with HTTP 200 and the status in `code`.
        val code = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
        // A body 404 is the server naming an unknown robot, so only 501 means the install option is off.
        val message = if (!started && code == 501) firmwareStartRefusal(code) else
            text("reason") ?: text("error") ?: (root["msg"] as? JsonPrimitive)?.content
        return FirmwareStartResult(started, text("version"), if (started) null else message)
    }

    suspend fun checkFirmwareUpdate(duid: String): FirmwareUpdateInfo? {
        val resp = client.get("/ota/firmware/$duid/updatev2") {
            parameter("lang", "en")
            header(HttpHeaders.Accept, "application/json")
        }
        if (!resp.status.isSuccess()) {
            throw DeviceApiException(resp.status.value, "Firmware check unavailable (HTTP ${resp.status.value})")
        }
        return resp.body<FirmwareOtaResponse>().result
    }
}

/** A server without install support answers HTTP 404 (older) or code 501 in the body (install option off). */
internal fun firmwareStartRefusal(status: Int): String = when (status) {
    404, 501 -> "This server cannot install firmware. Turn on the server's firmware install option (enable_install), then try again."
    else -> "The server did not accept the update request (HTTP $status)"
}

/** Only `code`/`msg` are guaranteed; `data` is absent or shaped differently on error replies. */
@Serializable
private data class DataEnvelope(
    val code: Int = 0,
    val msg: String = "",
    val data: JsonElement? = null,
)

@Serializable
private data class SceneListResponse(
    val code: Int,
    val msg: String = "",
    val data: List<Scene>? = null,
    val result: List<Scene>? = null,
)

@Serializable
private data class SceneRunResponse(
    val code: Int,
    val msg: String = "",
    val data: SceneRunResult? = null,
    val result: SceneRunResult? = null,
)

class DeviceApiException(val responseCode: Int, message: String) : RuntimeException(message)

/**
 * Reads the `{code, msg, data}` envelope before `data` is decoded as [T]: an error reply (e.g. 401
 * authentication_required) carries a different `data`, and decoding it as [T] up front would hide
 * the server's own message behind a "fields are required" serialization error.
 */
private suspend inline fun <reified T> HttpResponse.unwrapData(): T {
    val httpStatus = status.value
    val text = bodyAsText()
    val envelope = try {
        ProdJson.decodeFromString<DataEnvelope>(text)
    } catch (e: SerializationException) {
        throw DeviceApiException(httpStatus, "HTTP $httpStatus: ${text.replace(Regex("\\s+"), " ").trim().take(120)}")
    }
    if (envelope.code != 200 || !status.isSuccess()) {
        throw DeviceApiException(envelope.code, "HTTP $httpStatus, code ${envelope.code}: ${serverErrorText(envelope.msg, envelope.data)}")
    }
    val data = envelope.data
        ?: throw DeviceApiException(envelope.code, "HTTP $httpStatus, code ${envelope.code}: response contained no data")
    return ProdJson.decodeFromJsonElement<T>(data)
}
