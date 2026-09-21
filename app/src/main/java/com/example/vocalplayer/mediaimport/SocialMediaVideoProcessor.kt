package com.example.vocalplayer.mediaimport

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Resilient, multi-platform Web Media & Video Import Processor.
 * Supports YouTube, TikTok, Instagram, X (Twitter), Facebook, Vimeo, Dailymotion,
 * universal HTML5 web pages, and direct HTTP/HLS streams.
 */
class SocialMediaVideoProcessor(private val context: Context) {

    private val tag = "SocialMediaProcessor"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Fast HTTP client specifically for stream probing to prevent UI hangs (max 4s connect / 5s read)
    private val probeHttpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val importCacheDir = File(context.cacheDir, "imported_media").apply { mkdirs() }

    private val defaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // Regex patterns for platform detection
    private val youtubeRegex = Pattern.compile(
        """(?:https?:\/\/)?(?:www\.|m\.)?(?:youtube\.com\/(?:watch\?v=|embed\/|v\/|shorts\/)|youtu\.be\/)([\w-]{6,})""",
        Pattern.CASE_INSENSITIVE
    )
    private val vimeoRegex = Pattern.compile(
        """(?:https?:\/\/)?(?:www\.)?(?:player\.)?vimeo\.com\/(?:channels\/(?:\w+\/)?|groups\/[^\/]*\/videos\/|album\/(?:\d+\/)?video\/|video\/|)(\d+)""",
        Pattern.CASE_INSENSITIVE
    )
    private val dailymotionRegex = Pattern.compile(
        """(?:https?:\/\/)?(?:www\.)?(?:dailymotion\.com\/(?:video|embed\/video)\/|dai\.ly\/)([a-zA-Z0-9]+)""",
        Pattern.CASE_INSENSITIVE
    )
    private val tiktokRegex = Pattern.compile(
        """(?:https?:\/\/)?(?:www\.|vm\.|vt\.)?tiktok\.com\/""",
        Pattern.CASE_INSENSITIVE
    )
    private val twitterRegex = Pattern.compile(
        """(?:https?:\/\/)?(?:www\.)?(?:twitter\.com|x\.com)\/""",
        Pattern.CASE_INSENSITIVE
    )
    private val facebookRegex = Pattern.compile(
        """(?:https?:\/\/)?(?:www\.|web\.|m\.)?(?:facebook\.com|fb\.watch)\/""",
        Pattern.CASE_INSENSITIVE
    )
    private val directMediaRegex = Pattern.compile(
        """.*\.(mp4|webm|mkv|mov|m4a|mp3|wav|ogg|aac)(\?.*)?$""",
        Pattern.CASE_INSENSITIVE
    )
    private val hlsRegex = Pattern.compile(
        """.*\.m3u8(\?.*)?$""",
        Pattern.CASE_INSENSITIVE
    )

    // Public Invidious and Cobalt instances for fallback resilience
    private val invidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://inv.tux.pizza",
        "https://invidious.io.lol"
    )

    private val cobaltInstances = listOf(
        "https://api.cobalt.tools",
        "https://cobalt.api.scav.top"
    )

    // =========================================================================
    // Phase 1: Platform Detection & Resolution Probing (Pre-Download)
    // =========================================================================

    suspend fun probeMediaUrl(rawUrl: String): ProbedMediaInfo = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        Log.i(tag, "Probing media URL: $cleanUrl")

        // 1. Direct HLS
        if (hlsRegex.matcher(cleanUrl).find()) {
            return@withContext probeHlsStream(cleanUrl)
        }

        // 2. Direct Video or Audio URL
        if (directMediaRegex.matcher(cleanUrl).find()) {
            return@withContext probeDirectMedia(cleanUrl)
        }

        // 3. YouTube & YouTube Shorts
        val ytMatcher = youtubeRegex.matcher(cleanUrl)
        if (ytMatcher.find()) {
            val videoId = ytMatcher.group(1) ?: ""
            return@withContext probeYouTube(cleanUrl, videoId)
        }

        // 4. Vimeo
        val vimeoMatcher = vimeoRegex.matcher(cleanUrl)
        if (vimeoMatcher.find()) {
            val vimeoId = vimeoMatcher.group(1) ?: ""
            return@withContext probeVimeo(cleanUrl, vimeoId)
        }

        // 5. Dailymotion
        val dmMatcher = dailymotionRegex.matcher(cleanUrl)
        if (dmMatcher.find()) {
            val dmId = dmMatcher.group(1) ?: ""
            return@withContext probeDailymotion(cleanUrl, dmId)
        }

        // 6. TikTok
        if (tiktokRegex.matcher(cleanUrl).find()) {
            return@withContext probeTikTok(cleanUrl)
        }

        // 7. Universal HTML5 web scraper / generic page
        return@withContext probeUniversalHtml5(cleanUrl)
    }

    private suspend fun probeYouTube(url: String, videoId: String): ProbedMediaInfo {
        Log.i(tag, "Probing YouTube video: $videoId")
        val resolutions = mutableListOf<MediaResolutionOption>()

        // Try Y2Mate analyze endpoints
        try {
            val y2mateEndpoints = listOf(
                "https://www.y2mate.com/mates/analyzeV2/ajax",
                "https://t-y2mate.com/mates/analyzeV2/ajax"
            )

            for (endpoint in y2mateEndpoints) {
                try {
                    val formBody = "k_query=${URLEncoder.encode(url, "UTF-8")}&k_page=home&hl=en&q_auto=0"
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                        .header("User-Agent", defaultUserAgent)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://www.y2mate.com/en")
                        .build()

                    val response = probeHttpClient.newCall(request).execute()
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful && body.contains("\"status\":\"success\"")) {
                        val json = JSONObject(body)
                        val title = json.optString("title", "YouTube Video ($videoId)")
                        val links = json.optJSONObject("links")

                        if (links != null) {
                            val mp4 = links.optJSONObject("mp4")
                            if (mp4 != null) {
                                val keys = mp4.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val item = mp4.getJSONObject(k)
                                    val q = item.optString("q", "") // e.g. "1080p", "720p", "480p"
                                    val size = item.optString("size", "")
                                    val kVal = item.optString("k", "")
                                    val height = extractHeightFromQuality(q)

                                    if (height > 0) {
                                        resolutions.add(
                                            MediaResolutionOption(
                                                id = q,
                                                label = "$q ${if (q == "720p") "(Recommended)" else ""}".trim(),
                                                height = height,
                                                format = "mp4",
                                                sizeText = if (size.isNotBlank()) size else null,
                                                isRecommended = q == "720p" || q == "1080p",
                                                internalKey = kVal,
                                                isFromSource = false
                                            )
                                        )
                                    }
                                }
                            }

                            // Audio
                            val mp3 = links.optJSONObject("mp3")
                            if (mp3 != null) {
                                val autoMp3 = mp3.optJSONObject("mp3128") ?: mp3.optJSONObject(mp3.keys().asSequence().firstOrNull() ?: "")
                                if (autoMp3 != null) {
                                    resolutions.add(
                                        MediaResolutionOption(
                                            id = "audio",
                                            label = "Audio Only (HQ MP3)",
                                            height = 0,
                                            format = "mp3",
                                            sizeText = autoMp3.optString("size", null),
                                            isRecommended = false,
                                            internalKey = autoMp3.optString("k", "")
                                        )
                                    )
                                }
                            }

                            if (resolutions.isNotEmpty()) {
                                resolutions.sortByDescending { it.height }
                                return ProbedMediaInfo(
                                    originalUrl = url,
                                    title = title,
                                    platformName = "YouTube",
                                    resolutions = resolutions
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Y2Mate endpoint query failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Y2Mate probe error: ${e.message}")
        }

        // InnerTube or Invidious metadata probe fallback
        try {
            for (inv in invidiousInstances) {
                try {
                    val req = Request.Builder()
                        .url("$inv/api/v1/videos/$videoId")
                        .header("User-Agent", defaultUserAgent)
                        .build()
                    val res = probeHttpClient.newCall(req).execute()
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val json = JSONObject(body)
                        val title = json.optString("title", "YouTube Video ($videoId)")
                        val thumb = json.optJSONArray("videoThumbnails")?.optJSONObject(0)?.optString("url")
                        val formatStreams = json.optJSONArray("formatStreams")

                        if (formatStreams != null && formatStreams.length() > 0) {
                            for (i in 0 until formatStreams.length()) {
                                val s = formatStreams.getJSONObject(i)
                                val q = s.optString("qualityLabel", "")
                                val directUrl = s.optString("url", "")
                                val height = extractHeightFromQuality(q)
                                if (height > 0 && directUrl.isNotBlank()) {
                                    resolutions.add(
                                        MediaResolutionOption(
                                            id = q,
                                            label = "$q Video",
                                            height = height,
                                            format = "mp4",
                                            isRecommended = q.contains("720"),
                                            directUrl = directUrl,
                                            isFromSource = true
                                        )
                                    )
                                }
                            }
                        }

                        if (resolutions.isNotEmpty()) {
                            resolutions.sortByDescending { it.height }
                            return ProbedMediaInfo(
                                originalUrl = url,
                                title = title,
                                platformName = "YouTube",
                                thumbnailUrl = thumb,
                                resolutions = resolutions
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(tag, "Invidious probe error: ${e.message}")
        }

        // Standard Fallback Preset Options if online probe didn't finish
        val fallbackResolutions = listOf(
            MediaResolutionOption("1080p", "1080p (Full HD)", 1080, "mp4", isRecommended = false),
            MediaResolutionOption("720p", "720p (HD - Fast Download)", 720, "mp4", isRecommended = true),
            MediaResolutionOption("480p", "480p (Standard Definition)", 480, "mp4", isRecommended = false),
            MediaResolutionOption("360p", "360p (Low Data)", 360, "mp4", isRecommended = false),
            MediaResolutionOption("audio", "Audio Only (Separation Extraction)", 0, "mp3", isRecommended = false)
        )

        return ProbedMediaInfo(
            originalUrl = url,
            title = "YouTube Video ($videoId)",
            platformName = "YouTube",
            resolutions = fallbackResolutions
        )
    }

    private suspend fun probeVimeo(url: String, vimeoId: String): ProbedMediaInfo {
        val embedUrl = "https://player.vimeo.com/video/$vimeoId?app_id=122963"
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", defaultUserAgent)
            .header("Referer", "https://vimeo.com/")
            .header("Sec-Fetch-Dest", "iframe")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""

            val configPattern = Pattern.compile("""(?:window\.playerConfig|var config)\s*=\s*(\{.+?\});""", Pattern.DOTALL)
            val matcher = configPattern.matcher(html)

            val resolutions = mutableListOf<MediaResolutionOption>()
            var videoTitle = "Vimeo Video ($vimeoId)"
            var thumb: String? = null

            if (matcher.find()) {
                val jsonStr = matcher.group(1) ?: "{}"
                val json = JSONObject(jsonStr)
                val videoObj = json.optJSONObject("video")
                videoTitle = videoObj?.optString("title", videoTitle) ?: videoTitle
                thumb = videoObj?.optJSONArray("thumbs")?.optJSONObject(0)?.optString("url")

                val files = json.optJSONObject("request")?.optJSONObject("files")
                val progressive = files?.optJSONArray("progressive")
                if (progressive != null) {
                    for (i in 0 until progressive.length()) {
                        val item = progressive.getJSONObject(i)
                        val q = item.optString("quality", "")
                        val directUrl = item.optString("url", "")
                        val height = extractHeightFromQuality(q)
                        if (height > 0) {
                            resolutions.add(
                                MediaResolutionOption(
                                    id = q,
                                    label = "$q (Progressive MP4)",
                                    height = height,
                                    format = "mp4",
                                    isRecommended = q == "720p" || q == "1080p",
                                    directUrl = directUrl,
                                    isFromSource = true
                                )
                            )
                        }
                    }
                }

                // HLS fallback
                val hlsDefault = files?.optJSONObject("hls")?.optJSONObject("default_cdn")?.optString("url")
                if (hlsDefault != null && hlsDefault.isNotBlank() && resolutions.isEmpty()) {
                    return probeHlsStream(hlsDefault).copy(
                        title = videoTitle,
                        platformName = "Vimeo",
                        thumbnailUrl = thumb
                    )
                }
            }

            if (resolutions.isNotEmpty()) {
                resolutions.sortByDescending { it.height }
                return ProbedMediaInfo(
                    originalUrl = url,
                    title = videoTitle,
                    platformName = "Vimeo",
                    thumbnailUrl = thumb,
                    resolutions = resolutions
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Vimeo probe error: ${e.message}")
        }

        return ProbedMediaInfo(
            originalUrl = url,
            title = "Vimeo Video ($vimeoId)",
            platformName = "Vimeo",
            resolutions = listOf(
                MediaResolutionOption("720p", "720p HD", 720, "mp4", isRecommended = true),
                MediaResolutionOption("1080p", "1080p Full HD", 1080, "mp4", isRecommended = false)
            )
        )
    }

    private suspend fun probeDailymotion(url: String, dmId: String): ProbedMediaInfo {
        val metaUrl = "https://www.dailymotion.com/player/metadata/video/$dmId"
        val request = Request.Builder()
            .url(metaUrl)
            .header("User-Agent", defaultUserAgent)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val title = json.optString("title", "Dailymotion Video ($dmId)")
                val posters = json.optJSONObject("posters")
                val posterUrl = posters?.optString("60", null)
                val qualities = json.optJSONObject("qualities")

                val resolutions = mutableListOf<MediaResolutionOption>()
                if (qualities != null) {
                    val keys = qualities.keys()
                    while (keys.hasNext()) {
                        val qKey = keys.next()
                        val qArr = qualities.getJSONArray(qKey)
                        if (qArr.length() > 0) {
                            val streamItem = qArr.getJSONObject(0)
                            val directUrl = streamItem.optString("url")
                            val isHls = streamItem.optString("type").contains("x-mpegURL")
                            val height = extractHeightFromQuality(qKey)
                            if (directUrl.isNotBlank()) {
                                resolutions.add(
                                    MediaResolutionOption(
                                        id = qKey,
                                        label = "${qKey}p ${if (isHls) "(HLS)" else ""}".trim(),
                                        height = height,
                                        format = "mp4",
                                        isRecommended = qKey == "720" || qKey == "auto",
                                        directUrl = directUrl,
                                        isFromSource = true
                                    )
                                )
                            }
                        }
                    }
                }

                if (resolutions.isNotEmpty()) {
                    resolutions.sortByDescending { it.height }
                    return ProbedMediaInfo(
                        originalUrl = url,
                        title = title,
                        platformName = "Dailymotion",
                        thumbnailUrl = posterUrl,
                        resolutions = resolutions
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Dailymotion probe error: ${e.message}")
        }

        return ProbedMediaInfo(
            originalUrl = url,
            title = "Dailymotion Video ($dmId)",
            platformName = "Dailymotion",
            resolutions = listOf(
                MediaResolutionOption("720", "720p HD", 720, "mp4", isRecommended = true)
            )
        )
    }

    private suspend fun probeTikTok(url: String): ProbedMediaInfo {
        val tikwmUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(url, "UTF-8")}"
        val request = Request.Builder()
            .url(tikwmUrl)
            .header("User-Agent", defaultUserAgent)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            if (json.optInt("code") == 0) {
                val data = json.getJSONObject("data")
                val title = data.optString("title", "TikTok Video").take(60)
                val playUrl = data.optString("play")
                val hdUrl = data.optString("hdplay")
                val wmUrl = data.optString("wmplay")
                val cover = data.optString("cover")

                val resolutions = mutableListOf<MediaResolutionOption>()
                if (hdUrl.isNotBlank()) {
                    resolutions.add(
                        MediaResolutionOption(
                            id = "hd",
                            label = "HD (Watermark Free)",
                            height = 1080,
                            format = "mp4",
                            isRecommended = true,
                            directUrl = hdUrl,
                            isFromSource = true
                        )
                    )
                }
                if (playUrl.isNotBlank()) {
                    resolutions.add(
                        MediaResolutionOption(
                            id = "sd",
                            label = "Standard Video (No Watermark)",
                            height = 720,
                            format = "mp4",
                            isRecommended = hdUrl.isBlank(),
                            directUrl = playUrl,
                            isFromSource = true
                        )
                    )
                }

                val music = data.optString("music")
                if (music.isNotBlank()) {
                    resolutions.add(
                        MediaResolutionOption(
                            id = "audio",
                            label = "Soundtrack Only (MP3)",
                            height = 0,
                            format = "mp3",
                            directUrl = music,
                            isFromSource = true
                        )
                    )
                }

                return ProbedMediaInfo(
                    originalUrl = url,
                    title = title.ifBlank { "TikTok Video" },
                    platformName = "TikTok",
                    thumbnailUrl = cover,
                    resolutions = resolutions
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "TikWM probe error: ${e.message}")
        }

        return probeUniversalHtml5(url)
    }

    private suspend fun probeHlsStream(url: String): ProbedMediaInfo {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", defaultUserAgent)
            .build()

        val resolutions = mutableListOf<MediaResolutionOption>()
        var isMaster = false

        try {
            val response = httpClient.newCall(request).execute()
            val manifest = response.body?.string() ?: ""

            if (manifest.contains("#EXT-X-STREAM-INF")) {
                isMaster = true
                val lines = manifest.lines()
                var currentRes: String? = null
                var currentBw: Long? = null

                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatch = Pattern.compile("""RESOLUTION=(\d+x\d+)""").matcher(line)
                        if (resMatch.find()) {
                            currentRes = resMatch.group(1)
                        }
                        val bwMatch = Pattern.compile("""BANDWIDTH=(\d+)""").matcher(line)
                        if (bwMatch.find()) {
                            currentBw = bwMatch.group(1)?.toLongOrNull()
                        }
                    } else if (line.isNotEmpty() && !line.startsWith("#") && currentRes != null) {
                        val variantUrl = resolveUrl(url, line)
                        val height = currentRes.split("x").getOrNull(1)?.toIntOrNull() ?: 720
                        resolutions.add(
                            MediaResolutionOption(
                                id = "${height}p",
                                label = "$currentRes (${(currentBw ?: 0) / 1000} kbps)",
                                height = height,
                                format = "mp4",
                                isRecommended = height == 720 || height == 1080,
                                directUrl = variantUrl,
                                isFromSource = true
                            )
                        )
                        currentRes = null
                        currentBw = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "HLS probe error: ${e.message}")
        }

        if (resolutions.isEmpty()) {
            resolutions.add(
                MediaResolutionOption(
                    id = "auto",
                    label = "HLS Live/VOD Stream (Auto)",
                    height = 720,
                    format = "mp4",
                    isRecommended = true,
                    directUrl = url,
                    isFromSource = true
                )
            )
        }

        resolutions.sortByDescending { it.height }

        return ProbedMediaInfo(
            originalUrl = url,
            title = "HLS Stream (${URI(url).host ?: "Media"})",
            platformName = "HLS Stream",
            isHls = true,
            defaultDirectUrl = url,
            resolutions = resolutions
        )
    }

    private suspend fun probeDirectMedia(url: String): ProbedMediaInfo {
        var sizeMb: String? = null
        var mimeType = "video/mp4"

        try {
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", defaultUserAgent)
                .build()
            val response = httpClient.newCall(headRequest).execute()
            val len = response.header("Content-Length")?.toLongOrNull()
            if (len != null && len > 0) {
                sizeMb = "%.1f MB".format(len / (1024f * 1024f))
            }
            mimeType = response.header("Content-Type") ?: mimeType
        } catch (_: Exception) {}

        val fileName = url.substringAfterLast("/").substringBefore("?").ifBlank { "direct_media.mp4" }
        val isAudio = mimeType.startsWith("audio/") || fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".m4a")

        return ProbedMediaInfo(
            originalUrl = url,
            title = fileName,
            platformName = "Direct File",
            defaultDirectUrl = url,
            resolutions = listOf(
                MediaResolutionOption(
                    id = if (isAudio) "audio" else "source",
                    label = if (isAudio) "Source Audio ($fileName)" else "Direct Video Stream",
                    height = if (isAudio) 0 else 1080,
                    format = if (isAudio) "mp3" else "mp4",
                    sizeText = sizeMb,
                    isRecommended = true,
                    directUrl = url,
                    isFromSource = true
                )
            )
        )
    }

    private suspend fun probeUniversalHtml5(pageUrl: String): ProbedMediaInfo {
        Log.i(tag, "Probing Universal HTML5 web page: $pageUrl")
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", defaultUserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        var pageTitle = "Web Media"
        var candidateStreams = mutableListOf<String>()

        try {
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""

            // Title
            val titleMatch = Pattern.compile("""<title[^>]*>(.*?)</title>""", Pattern.CASE_INSENSITIVE).matcher(html)
            if (titleMatch.find()) {
                pageTitle = titleMatch.group(1)?.trim()?.replace("&amp;", "&") ?: pageTitle
            }

            // OpenGraph Video
            val ogVideoRegex = Pattern.compile(
                """<meta\s+property=["']og:video(?::secure_url|:url)?["']\s+content=["'](.*?)["']""",
                Pattern.CASE_INSENSITIVE
            )
            val ogMatcher = ogVideoRegex.matcher(html)
            while (ogMatcher.find()) {
                val vUrl = ogMatcher.group(1)
                if (!vUrl.isNullOrBlank()) candidateStreams.add(resolveUrl(pageUrl, vUrl))
            }

            // Twitter Video Player Stream
            val twVideoRegex = Pattern.compile(
                """<meta\s+name=["']twitter:player:stream["']\s+content=["'](.*?)["']""",
                Pattern.CASE_INSENSITIVE
            )
            val twMatcher = twVideoRegex.matcher(html)
            if (twMatcher.find()) {
                val vUrl = twMatcher.group(1)
                if (!vUrl.isNullOrBlank()) candidateStreams.add(resolveUrl(pageUrl, vUrl))
            }

            // HTML5 <video> / <source> tags
            val videoTagRegex = Pattern.compile(
                """<(?:video|source)[^>]+src=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE
            )
            val videoMatcher = videoTagRegex.matcher(html)
            while (videoMatcher.find()) {
                val vUrl = videoMatcher.group(1)
                if (!vUrl.isNullOrBlank() && !vUrl.startsWith("blob:")) {
                    candidateStreams.add(resolveUrl(pageUrl, vUrl))
                }
            }

            // Javascript player configs
            val jsPlayerRegex = Pattern.compile(
                """["'](?:contentUrl|videoUrl|videoSrc|file)["']\s*:\s*["']([^"']+\.(?:mp4|webm|m3u8|mov)[^"']*)["']""",
                Pattern.CASE_INSENSITIVE
            )
            val jsMatcher = jsPlayerRegex.matcher(html)
            while (jsMatcher.find()) {
                val vUrl = jsMatcher.group(1)
                if (!vUrl.isNullOrBlank()) {
                    candidateStreams.add(resolveUrl(pageUrl, vUrl.replace("\\/", "/")))
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing HTML5 page: ${e.message}")
        }

        val resolutions = mutableListOf<MediaResolutionOption>()
        val distinctCandidates = candidateStreams.distinct()

        for (candidate in distinctCandidates) {
            val isHls = candidate.contains(".m3u8")
            val isAudio = candidate.contains(".mp3") || candidate.contains(".m4a") || candidate.contains(".wav")
            resolutions.add(
                MediaResolutionOption(
                    id = if (isAudio) "audio" else if (isHls) "hls" else "video",
                    label = if (isHls) "HLS Stream" else if (isAudio) "Web Audio Track" else "HTML5 Video Track",
                    height = if (isAudio) 0 else 720,
                    format = if (isAudio) "mp3" else "mp4",
                    isRecommended = true,
                    directUrl = candidate,
                    isFromSource = true
                )
            )
        }

        if (resolutions.isEmpty()) {
            resolutions.add(
                MediaResolutionOption(
                    id = "standard",
                    label = "Auto-Resolve Web Stream",
                    height = 720,
                    format = "mp4",
                    isRecommended = true
                )
            )
        }

        return ProbedMediaInfo(
            originalUrl = pageUrl,
            title = pageTitle.take(60),
            platformName = "Web Page",
            resolutions = resolutions
        )
    }

    // =========================================================================
    // Phase 2: Cascading Fallback Stream Extraction
    // =========================================================================

    suspend fun resolveMediaStream(
        probedInfo: ProbedMediaInfo,
        selectedOption: MediaResolutionOption
    ): ResolvedMediaResult = withContext(Dispatchers.IO) {
        val originalUrl = probedInfo.originalUrl

        // 1. If resolution option already has a tested direct progressive/HLS URL
        if (!selectedOption.directUrl.isNullOrBlank()) {
            Log.i(tag, "Using already probed direct URL: ${selectedOption.directUrl}")
            return@withContext ResolvedMediaResult.Success(
                streamUrl = selectedOption.directUrl,
                title = probedInfo.title,
                isHls = selectedOption.directUrl.contains(".m3u8")
            )
        }

        // 2. YouTube Cascading Pipeline
        val ytMatcher = youtubeRegex.matcher(originalUrl)
        if (ytMatcher.find()) {
            val videoId = ytMatcher.group(1) ?: ""
            return@withContext resolveYouTubePipeline(originalUrl, videoId, selectedOption)
        }

        // 3. Facebook
        if (facebookRegex.matcher(originalUrl).find()) {
            return@withContext resolveFacebookPipeline(originalUrl)
        }

        // 4. Fallback to Cobalt instances
        for (cobalt in cobaltInstances) {
            try {
                val jsonPayload = JSONObject().apply {
                    put("url", originalUrl)
                    put("videoQuality", if (selectedOption.height > 0) selectedOption.height.toString() else "720")
                    if (selectedOption.id == "audio") {
                        put("downloadMode", "audio")
                    }
                }
                val request = Request.Builder()
                    .url(cobalt)
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("Accept", "application/json")
                    .header("User-Agent", defaultUserAgent)
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val status = json.optString("status")
                val streamUrl = json.optString("url")
                if ((status == "stream" || status == "tunnel" || status == "redirect") && streamUrl.isNotBlank()) {
                    return@withContext ResolvedMediaResult.Success(
                        streamUrl = streamUrl,
                        title = probedInfo.title
                    )
                }
            } catch (e: Exception) {
                Log.w(tag, "Cobalt fallback attempt failed: ${e.message}")
            }
        }

        // 5. Invidious fallback for video
        if (ytMatcher.find()) {
            val videoId = ytMatcher.group(1) ?: ""
            for (inv in invidiousInstances) {
                try {
                    val streamEndpoint = "$inv/api/v1/videos/$videoId"
                    val res = httpClient.newCall(Request.Builder().url(streamEndpoint).build()).execute()
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    val formatStreams = json.optJSONArray("formatStreams")
                    if (formatStreams != null && formatStreams.length() > 0) {
                        val firstUrl = formatStreams.getJSONObject(0).optString("url")
                        if (firstUrl.isNotBlank()) {
                            return@withContext ResolvedMediaResult.Success(
                                streamUrl = firstUrl,
                                title = probedInfo.title
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return@withContext ResolvedMediaResult.Error("Could not resolve media stream for: $originalUrl. Please verify the link or try another format.")
    }

    private suspend fun resolveYouTubePipeline(
        originalUrl: String,
        videoId: String,
        selectedOption: MediaResolutionOption
    ): ResolvedMediaResult {
        // Tier 1: Y2Mate Converter
        if (!selectedOption.internalKey.isNullOrBlank()) {
            val convertEndpoints = listOf(
                "https://www.y2mate.com/mates/convertV2/index",
                "https://t-y2mate.com/mates/convertV2/index"
            )
            for (endpoint in convertEndpoints) {
                try {
                    val bodyStr = "vid=$videoId&k=${URLEncoder.encode(selectedOption.internalKey, "UTF-8")}"
                    val req = Request.Builder()
                        .url(endpoint)
                        .post(bodyStr.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                        .header("User-Agent", defaultUserAgent)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://www.y2mate.com/en")
                        .build()

                    val res = httpClient.newCall(req).execute()
                    val respText = res.body?.string() ?: ""
                    val json = JSONObject(respText)
                    if (json.optString("status") == "success") {
                        val dlink = json.optString("dlink")
                        val title = json.optString("title", "YouTube Video ($videoId)")
                        if (dlink.isNotBlank()) {
                            return ResolvedMediaResult.Success(
                                streamUrl = dlink,
                                title = title
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Y2Mate convertV2 failed: ${e.message}")
                }
            }
        }

        // Tier 2: Loader.to Converter
        try {
            val formatParam = if (selectedOption.id == "audio") "mp3" else when (selectedOption.height) {
                1080 -> "1080"
                720 -> "720"
                480 -> "480"
                360 -> "360"
                else -> "720"
            }
            val loaderInitUrl = "https://loader.to/ajax/download.php?format=$formatParam&url=${URLEncoder.encode(originalUrl, "UTF-8")}"
            val initReq = Request.Builder()
                .url(loaderInitUrl)
                .header("User-Agent", defaultUserAgent)
                .build()

            val initRes = httpClient.newCall(initReq).execute()
            val initJson = JSONObject(initRes.body?.string() ?: "{}")
            val taskId = initJson.optString("id")

            if (taskId.isNotBlank()) {
                // Poll progress up to 40 times (every 1.5s)
                for (pollCount in 0 until 40) {
                    delay(1500)
                    val pollUrl = "https://lto2.affadaffa.com/api/progress?id=$taskId"
                    val pollReq = Request.Builder()
                        .url(pollUrl)
                        .header("User-Agent", defaultUserAgent)
                        .build()

                    val pollRes = httpClient.newCall(pollReq).execute()
                    val pollJson = JSONObject(pollRes.body?.string() ?: "{}")
                    val downloadUrl = pollJson.optString("download_url")
                    val isSuccess = pollJson.optInt("success", 0) == 1

                    if (isSuccess && downloadUrl.isNotBlank()) {
                        val title = pollJson.optString("title", "YouTube Video ($videoId)")
                        return ResolvedMediaResult.Success(
                            streamUrl = downloadUrl,
                            title = title
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Loader.to pipeline failed: ${e.message}")
        }

        // Tier 3: Invidious Instances
        for (inv in invidiousInstances) {
            try {
                val req = Request.Builder()
                    .url("$inv/api/v1/videos/$videoId")
                    .header("User-Agent", defaultUserAgent)
                    .build()
                val res = httpClient.newCall(req).execute()
                val body = res.body?.string() ?: ""
                val json = JSONObject(body)
                val formatStreams = json.optJSONArray("formatStreams")
                if (formatStreams != null && formatStreams.length() > 0) {
                    var bestUrl: String? = null
                    for (i in 0 until formatStreams.length()) {
                        val s = formatStreams.getJSONObject(i)
                        val q = s.optString("qualityLabel")
                        val u = s.optString("url")
                        if (q.contains(selectedOption.id) && u.isNotBlank()) {
                            bestUrl = u
                            break
                        }
                    }
                    val chosen = bestUrl ?: formatStreams.getJSONObject(0).optString("url")
                    if (chosen.isNotBlank()) {
                        return ResolvedMediaResult.Success(
                            streamUrl = chosen,
                            title = json.optString("title", "YouTube Video ($videoId)")
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return ResolvedMediaResult.Error("Unable to resolve YouTube stream with available resolvers.")
    }

    private suspend fun resolveFacebookPipeline(url: String): ResolvedMediaResult {
        val embedUrl = "https://www.facebook.com/plugins/video.php?href=${URLEncoder.encode(url, "UTF-8")}"
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", defaultUserAgent)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""

            val hdMatch = Pattern.compile("""playable_url_quality_hd["']\s*:\s*["']([^"']+)["']""").matcher(html)
            if (hdMatch.find()) {
                val clean = hdMatch.group(1)?.replace("\\/", "/") ?: ""
                if (clean.isNotBlank()) {
                    return ResolvedMediaResult.Success(streamUrl = clean, title = "Facebook Video HD")
                }
            }

            val sdMatch = Pattern.compile("""playable_url["']\s*:\s*["']([^"']+)["']""").matcher(html)
            if (sdMatch.find()) {
                val clean = sdMatch.group(1)?.replace("\\/", "/") ?: ""
                if (clean.isNotBlank()) {
                    return ResolvedMediaResult.Success(streamUrl = clean, title = "Facebook Video SD")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Facebook resolver failed: ${e.message}")
        }

        return ResolvedMediaResult.Error("Unable to resolve Facebook video stream.")
    }

    // =========================================================================
    // Phase 3: Direct Stream Downloader & HLS Chunk Engine
    // =========================================================================

    /**
     * Downloads a resolved stream directly or through HLS chunks into local storage.
     */
    suspend fun downloadMedia(
        resolved: ResolvedMediaResult.Success,
        outputFileName: String,
        onProgress: (MediaImportProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val safeName = outputFileName.ifBlank { "imported_media" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .let { if (!it.endsWith(".mp4") && !it.endsWith(".mp3")) "$it.mp4" else it }

        val destinationFile = File(importCacheDir, safeName)
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        if (resolved.isHls) {
            downloadHlsStream(resolved.streamUrl, destinationFile, onProgress)
        } else {
            downloadDirectStream(resolved.streamUrl, destinationFile, resolved.headers, onProgress)
        }

        // Verify HTML masquerade protection: ensure server didn't send an HTML error page
        verifyFileNotHtmlMasquerade(destinationFile)

        Log.i(tag, "Media successfully downloaded to: ${destinationFile.absolutePath} (${destinationFile.length() / 1024} KB)")
        destinationFile
    }

    private suspend fun downloadDirectStream(
        url: String,
        destinationFile: File,
        extraHeaders: Map<String, String>,
        onProgress: (MediaImportProgress) -> Unit
    ) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", defaultUserAgent)

        extraHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to download stream, HTTP response code: ${response.code}")
        }

        val body = response.body ?: throw IllegalStateException("Empty response body from media source.")
        val contentLength = body.contentLength()

        val buffer = ByteArray(64 * 1024) // 64 KB buffer chunks
        var totalBytesRead = 0L
        val startTime = System.currentTimeMillis()

        body.byteStream().use { input ->
            FileOutputStream(destinationFile).use { output ->
                while (currentCoroutineContext().isActive) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.1f)
                    val speedKbps = (totalBytesRead / 1024f) / elapsedSec
                    val mbRead = totalBytesRead / (1024f * 1024f)
                    val progressRatio = if (contentLength > 0) (totalBytesRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f) else 0f

                    val stageText = if (contentLength > 0) {
                        val totalMb = contentLength / (1024f * 1024f)
                        "Downloading (${(progressRatio * 100).toInt()}% • ${"%.1f".format(mbRead)}/${"%.1f".format(totalMb)} MB • ${"%.0f".format(speedKbps)} KB/s)"
                    } else {
                        "Downloading (${"%.2f".format(mbRead)} MB • ${"%.0f".format(speedKbps)} KB/s)"
                    }

                    onProgress(
                        MediaImportProgress(
                            bytesDownloaded = totalBytesRead,
                            totalBytes = contentLength,
                            percentage = progressRatio,
                            speedKbps = speedKbps,
                            stage = stageText
                        )
                    )
                }
            }
        }
    }

    private suspend fun downloadHlsStream(
        m3u8Url: String,
        destinationFile: File,
        onProgress: (MediaImportProgress) -> Unit
    ) {
        val req = Request.Builder()
            .url(m3u8Url)
            .header("User-Agent", defaultUserAgent)
            .build()
        val res = httpClient.newCall(req).execute()
        val manifest = res.body?.string() ?: ""

        val chunkUrls = mutableListOf<String>()
        val lines = manifest.lines()

        for (line in lines) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#")) {
                chunkUrls.add(resolveUrl(m3u8Url, t))
            }
        }

        if (chunkUrls.isEmpty()) {
            throw IllegalStateException("No media segments found in HLS playlist.")
        }

        var totalBytes = 0L
        val startTime = System.currentTimeMillis()

        FileOutputStream(destinationFile).use { output ->
            chunkUrls.forEachIndexed { index, chunkUrl ->
                var attempts = 0
                var chunkDownloaded = false

                while (attempts < 2 && !chunkDownloaded && currentCoroutineContext().isActive) {
                    attempts++
                    try {
                        val chunkReq = Request.Builder()
                            .url(chunkUrl)
                            .header("User-Agent", defaultUserAgent)
                            .build()
                        val chunkRes = httpClient.newCall(chunkReq).execute()
                        if (chunkRes.isSuccessful && chunkRes.body != null) {
                            val bytes = chunkRes.body!!.bytes()
                            output.write(bytes)
                            totalBytes += bytes.size
                            chunkDownloaded = true
                        }
                    } catch (_: Exception) {
                        delay(500)
                    }
                }

                val progressRatio = (index + 1).toFloat() / chunkUrls.size.toFloat()
                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.1f)
                val speedKbps = (totalBytes / 1024f) / elapsedSec

                onProgress(
                    MediaImportProgress(
                        bytesDownloaded = totalBytes,
                        totalBytes = 0L,
                        percentage = progressRatio,
                        speedKbps = speedKbps,
                        stage = "Merging HLS chunk ${index + 1}/${chunkUrls.size} (${(progressRatio * 100).toInt()}%)"
                    )
                )
            }
        }
    }

    private fun verifyFileNotHtmlMasquerade(file: File) {
        if (!file.exists() || file.length() < 64) {
            file.delete()
            throw IllegalStateException("Downloaded file is empty or corrupted.")
        }

        val headerBytes = ByteArray(128)
        file.inputStream().use { it.read(headerBytes) }
        val headerStr = String(headerBytes).lowercase()

        if (headerStr.contains("<!doctype") ||
            headerStr.contains("<html") ||
            headerStr.contains("{\"error\"") ||
            headerStr.contains("{\"status\":\"error\"")
        ) {
            file.delete()
            throw IllegalStateException("Downloaded media payload was rejected by host (HTML masquerade).")
        }
    }

    /**
     * Inspects duration and true title using Android MediaMetadataRetriever.
     */
    fun extractMediaMetadata(file: File): Pair<String?, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durStr?.toLongOrNull() ?: 0L
            Pair(title, durationMs)
        } catch (_: Exception) {
            Pair(null, 0L)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // Helper functions
    private fun extractHeightFromQuality(q: String): Int {
        val digits = q.filter { it.isDigit() }.toIntOrNull() ?: 0
        return when {
            digits >= 1080 -> 1080
            digits >= 720 -> 720
            digits >= 480 -> 480
            digits >= 360 -> 360
            digits >= 240 -> 240
            else -> digits
        }
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            URL(URL(baseUrl), relativeOrAbsolute).toString()
        } catch (_: Exception) {
            relativeOrAbsolute
        }
    }
}
