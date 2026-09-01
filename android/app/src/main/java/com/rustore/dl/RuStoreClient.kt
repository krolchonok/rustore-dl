package com.rustore.dl

import android.util.Log
import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@Serializable
data class AppRating(
    val average: Double? = null,
    val votes: Long? = null,
)

@Serializable
data class AgeRestriction(
    val category: String? = null,
    val name: String? = null,
    val description: String? = null,
    @SerialName("imageUrl") val imageUrl: String? = null,
)

@Serializable
data class AppScreenshot(
    @SerialName("fileUrl") val fileUrl: String,
    val type: String? = null,
    val ordinal: Int? = null,
    val orientation: String? = null,
)

@Serializable
data class AppInfo(
    @SerialName("appId") val appId: Long,
    @SerialName("packageName") val packageName: String,
    @SerialName("appName") val appName: String,
    @SerialName("versionCode") val versionCode: Long? = null,
    @SerialName("versionName") val versionName: String? = null,
    @SerialName("shortDescription") val shortDescription: String? = null,
    @SerialName("fullDescription") val fullDescription: String? = null,
    @SerialName("whatsNew") val whatsNew: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("companyName") val companyName: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    @SerialName("downloads") val downloads: Long? = null,
    @SerialName("roundedDownloadsText") val roundedDownloadsText: String? = null,
    val categories: List<String>? = null,
    val rating: AppRating? = null,
    @SerialName("averageUserRating") val averageUserRating: Double? = null,
    @SerialName("totalRatings") val totalRatings: Long? = null,
    @SerialName("ageRestriction") val ageRestriction: AgeRestriction? = null,
    @SerialName("ageLegal") val ageLegal: String? = null,
    @SerialName("fileUrls") val fileUrls: List<AppScreenshot>? = null,
    @SerialName("appVerUpdatedAt") val appVerUpdatedAt: String? = null,
    @SerialName("minSdkVersion") val minSdkVersion: Int? = null,
) {
    val displayRating: Double?
        get() = rating?.average ?: averageUserRating

    val displayRatingVotes: Long?
        get() = rating?.votes ?: totalRatings

    val screenshots: List<String>
        get() = fileUrls
            ?.filter { it.type.equals("SCREENSHOT", ignoreCase = true) }
            ?.sortedBy { it.ordinal ?: Int.MAX_VALUE }
            ?.map { it.fileUrl }
            .orEmpty()

    val hasRichDetails: Boolean
        get() = screenshots.isNotEmpty() ||
            !fullDescription.isNullOrBlank() ||
            !whatsNew.isNullOrBlank() ||
            fileSize != null

    fun mergeDetails(other: AppInfo): AppInfo {
        if (appId != other.appId) {
            return other
        }
        return copy(
            versionCode = other.versionCode ?: versionCode,
            versionName = other.versionName ?: versionName,
            shortDescription = other.shortDescription ?: shortDescription,
            fullDescription = other.fullDescription ?: fullDescription,
            whatsNew = other.whatsNew ?: whatsNew,
            iconUrl = other.iconUrl ?: iconUrl,
            companyName = other.companyName ?: companyName,
            fileSize = other.fileSize ?: fileSize,
            downloads = other.downloads ?: downloads,
            roundedDownloadsText = other.roundedDownloadsText ?: roundedDownloadsText,
            categories = other.categories ?: categories,
            rating = other.rating ?: rating,
            averageUserRating = other.averageUserRating ?: averageUserRating,
            totalRatings = other.totalRatings ?: totalRatings,
            ageRestriction = other.ageRestriction ?: ageRestriction,
            ageLegal = other.ageLegal ?: ageLegal,
            fileUrls = other.fileUrls ?: fileUrls,
            appVerUpdatedAt = other.appVerUpdatedAt ?: appVerUpdatedAt,
            minSdkVersion = other.minSdkVersion ?: minSdkVersion,
        )
    }
}

data class DownloadArtifact(
    val url: String,
    val fileName: String,
)

@Serializable
private data class ApiEnvelope<T>(
    val code: String? = null,
    val message: String? = null,
    val body: T? = null,
)

@Serializable
private data class SearchBody(
    val content: List<AppInfo> = emptyList(),
)

@Serializable
private data class NonceResponse(
    val nonce: String,
)

@Serializable
private data class DownloadUrlItem(
    val url: String,
)

@Serializable
private data class DownloadLinkBody(
    @SerialName("downloadUrls") val downloadUrls: List<DownloadUrlItem> = emptyList(),
)

@Serializable
private data class DownloadLinkResponse(
    @SerialName("downloadUrls") val downloadUrls: List<DownloadUrlItem>? = null,
    val body: DownloadLinkBody? = null,
)

class RuStoreClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val deviceId = randomDeviceId()
    private var signature: String? = null

    fun resolveQuery(query: String, pageSize: Int = 20): List<AppInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }

        val packageName = extractPackageName(trimmed)
        if (packageName != null) {
            try {
                return listOf(getAppInfo(packageName))
            } catch (_: RuStoreException) {
                // Continue with fuzzy search below.
            }
        }

        return search(trimmed, pageSize = pageSize)
    }

    fun search(query: String, page: Int = 0, pageSize: Int = 20): List<AppInfo> {
        val url = "$BASE_URL/applicationData/apps?pageNumber=$page&pageSize=$pageSize&query=${encode(query)}&buyeruid=null"
        val response = signedRequest("GET", url)
        val envelope = json.decodeFromString<ApiEnvelope<SearchBody>>(response)
        return envelope.body?.content.orEmpty()
    }

    fun getAppInfo(packageName: String): AppInfo {
        val response = signedRequest("GET", "$BASE_URL/applicationData/overallInfo/$packageName")
        val envelope = json.decodeFromString<ApiEnvelope<AppInfo>>(response)
        if (envelope.code == "OK" && envelope.body != null) {
            return envelope.body
        }

        for (page in 0 until MAX_SEARCH_PAGES) {
            val matches = search(packageName, page = page, pageSize = 50)
            matches.firstOrNull { it.packageName == packageName }?.let { return it }
            if (matches.size < 50) {
                break
            }
        }

        throw RuStoreException("App not found: $packageName (${envelope.message})")
    }

    fun getDownloadArtifacts(appId: Long): List<DownloadArtifact> {
        val payload = """{"appId":$appId,"firstInstall":true}"""
        val response = signedRequest(
            method = "POST",
            url = "$BASE_URL/v3/showcase/apps/download-link",
            body = payload,
        )

        val parsed = json.decodeFromString<DownloadLinkResponse>(response)
        val items = parsed.downloadUrls ?: parsed.body?.downloadUrls.orEmpty()
        val artifacts = items.mapNotNull { item ->
            normalizeDownloadUrl(item.url)?.let { url ->
                DownloadArtifact(url = url, fileName = fileNameFromUrl(url))
            }
        }

        return artifacts.ifEmpty {
            throw RuStoreException("RuStore returned no download URLs")
        }
    }

    fun getDownloadUrls(appId: Long): List<String> = getDownloadArtifacts(appId).map { it.url }

    fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuStoreException("Download failed: HTTP ${response.code}")
            }
            return response.body?.bytes() ?: throw RuStoreException("Empty download body")
        }
    }

    fun downloadToFile(
        url: String,
        destination: File,
        onProgress: ((downloaded: Long, total: Long?) -> Unit)? = null,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuStoreException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw RuStoreException("Empty download body")
            val totalBytes = body.contentLength().takeIf { it > 0 }
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, totalBytes)
                    }
                    output.flush()
                }
            }
            if (destination.length() <= 0L) {
                destination.delete()
                throw RuStoreException("Downloaded file is empty")
            }
            Log.d(TAG, "Saved ${destination.name}: ${destination.length()} bytes")
        }
    }

    private fun signedRequest(method: String, url: String, body: String? = null): String {
        var responseBody = executeSignedRequest(method, url, body)
        if (responseBody == SESSION_REJECTED) {
            signature = null
            responseBody = executeSignedRequest(method, url, body)
        }
        if (responseBody == SESSION_REJECTED) {
            throw RuStoreException("RuStore rejected secure session (HTTP 419)")
        }
        return responseBody
    }

    private fun executeSignedRequest(method: String, url: String, body: String?): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(deviceHeaders(requireSignature()).build())

        val request = if (method == "POST") {
            requestBuilder.post(
                (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE),
            ).build()
        } else {
            requestBuilder.get().build()
        }

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 419) {
                return SESSION_REJECTED
            }
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuStoreException("HTTP ${response.code}: $text")
            }
            return text
        }
    }

    private fun requireSignature(): String {
        signature?.let { return it }

        val nonceRequest = Request.Builder()
            .url(NONCE_URL)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .headers(deviceHeaders(null).build())
            .build()

        httpClient.newCall(nonceRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuStoreException("Nonce request failed: HTTP ${response.code}")
            }
            val nonce = json.decodeFromString<NonceResponse>(response.body?.string().orEmpty()).nonce
            signature = signNonce(nonce)
            return signature!!
        }
    }

    private fun deviceHeaders(clientSignature: String?) =
        okhttp3.Headers.Builder().apply {
            add("deviceId", deviceId)
            add("firmwareVer", FIRMWARE_VER)
            add("androidSdkVer", ANDROID_SDK_VER)
            add("deviceManufacturerName", DEVICE_MANUFACTURER)
            add("deviceModelName", DEVICE_MODEL)
            add("deviceModel", "$DEVICE_MANUFACTURER $DEVICE_MODEL")
            add("firmwareLang", FIRMWARE_LANG)
            add("ruStoreVerCode", RU_STORE_VER_CODE)
            add("deviceType", "mobile")
            add("User-Agent", USER_AGENT)
            clientSignature?.let { add("X-Client-Signature", it) }
        }

    private fun signNonce(nonceBase64: String): String {
        val nonce = Base64.decode(nonceBase64, Base64.DEFAULT)
        val payload = nonce + APK_CERT_SHA256
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY, "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(payload), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "RuStoreClient"
        private const val SESSION_REJECTED = "__SESSION_REJECTED__"
        private const val MAX_SEARCH_PAGES = 5
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val BASE_URL = "https://backapi.rustore.ru"
        private const val NONCE_URL = "https://api.rustore.ru/v1/secure/nonce"
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private val CATALOG_URL_REGEX = Regex("(?:https?://)?(?:www\\.)?rustore\\.ru/catalog/app/([^/?#]+)")

        private val HMAC_KEY = Base64.decode(
            "K+eeiCbnVFnZ71KEVal0g5siHaX6v6drh8upeLgEPoU=",
            Base64.DEFAULT,
        )
        private val APK_CERT_SHA256 = Base64.decode(
            "Zh8ggo73gN4LebxZ8mowhkMWNV8w5Pkc+hSiB5GDmRQ=",
            Base64.DEFAULT,
        )

        private const val DEVICE_MANUFACTURER = "Google"
        private const val DEVICE_MODEL = "Pixel 8 Pro"
        private const val DEVICE_HARDWARE = "husky"
        private const val FIRMWARE_VER = "16"
        private const val ANDROID_SDK_VER = "36"
        private const val FIRMWARE_LANG = "ru"
        private const val RU_STORE_VER_CODE = "1105002"
        private const val USER_AGENT =
            "RuStore/1.105.0.2 (Android 16; SDK 36; arm64-v8a; Google Pixel 8 Pro; ru)"

        fun extractPackageName(input: String): String? {
            CATALOG_URL_REGEX.find(input)?.groupValues?.getOrNull(1)?.let { return it }
            val trimmed = input.trim()
            return trimmed.takeIf { looksLikePackageName(it) }
        }

        fun looksLikePackageName(value: String): Boolean = PACKAGE_NAME_REGEX.matches(value)

        fun normalizeDownloadUrl(rawUrl: String): String? {
            val url = rawUrl.trim()
            if (url.isEmpty()) {
                return null
            }
            if (!url.contains("/apk/") && !url.endsWith(".apk") && !url.endsWith(".zip")) {
                return null
            }
            return if (url.endsWith(".zip")) {
                url.dropLast(4) + ".apk"
            } else {
                url
            }
        }

        fun fileNameFromUrl(url: String): String {
            val name = url.substringAfterLast('/')
            return when {
                name.endsWith(".apk") -> name
                name.endsWith(".zip") -> name.dropLast(4) + ".apk"
                else -> "$name.apk"
            }
        }

        private fun javaStringHash(value: String): Int {
            var hash = 0L
            for (ch in value) {
                hash = (31L * hash + ch.code) and 0xFFFFFFFFL
            }
            if (hash >= 0x80000000L) {
                hash -= 0x100000000L
            }
            return hash.toInt()
        }

        private fun deviceIdSuffix(): String {
            val manufacturer = javaStringHash(DEVICE_MANUFACTURER).toLong()
            val model = javaStringHash(DEVICE_MODEL).toLong()
            val hardware = javaStringHash(DEVICE_HARDWARE).toLong()
            val device = javaStringHash(DEVICE_HARDWARE).toLong()
            var combined = device + ((hardware + ((model + (manufacturer * 31L)) * 31L)) * 31L)
            combined = (combined + 0x80000000L) and 0xFFFFFFFFL
            if (combined >= 0x80000000L) {
                combined -= 0x100000000L
            }
            return combined.toString()
        }

        private fun randomDeviceId(): String {
            val androidId = buildString {
                repeat(16) {
                    append(Integer.toHexString(Random.nextInt(16)))
                }
            }
            return "$androidId-${deviceIdSuffix()}"
        }

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.MINUTES)
                .writeTimeout(15, TimeUnit.MINUTES)
                .callTimeout(20, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

class RuStoreException(message: String) : Exception(message)
