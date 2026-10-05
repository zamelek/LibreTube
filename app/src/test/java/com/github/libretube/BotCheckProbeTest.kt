package com.github.libretube

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Manual diagnostic for the "sign in to confirm you're not a bot" answer of YouTube. It sends the
 * requests of the extractor from the development machine, i.e. from the same network as the phone,
 * to see whether the answer depends on the client (visionOS, iOS, ...) or on the IP family.
 *
 * It makes real network requests, so it only runs on request:
 * `BOT_CHECK_PROBE=1 ./gradlew testDebugUnitTest --tests '*BotCheckProbeTest*' -i | grep PROBE`
 * Run it while the problem is happening on the phone and compare. Do not run it in a loop, YouTube
 * throttles clients that send many requests.
 */
class BotCheckProbeTest {
    private class ProbeDownloader(private val ipFamily: String) : Downloader() {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).filter {
                    when (ipFamily) {
                        "ipv4" -> it is Inet4Address
                        "ipv6" -> it is Inet6Address
                        else -> true
                    }
                }.ifEmpty { throw UnknownHostException("no $ipFamily address for $hostname") }
            })
            .build()

        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0")
            for ((key, values) in request.headers()) {
                builder.removeHeader(key)
                values.forEach { builder.addHeader(key, it) }
            }
            client.newCall(builder.build()).execute().use { response ->
                return Response(
                    response.code, response.message, response.headers.toMultimap(),
                    response.body.string(), response.request.url.toString()
                )
            }
        }
    }

    private val helper = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper")
    private val parsing = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper")
    private val countryClass = ContentCountry::class.java
    private val localizationClass = Localization::class.java
    private val stringClass = String::class.java

    private val videoIds = listOf(
        "jNQXAC9IVRw", "9bZkp7q19f0", "dQw4w9WgXcQ", "kJQP7kiw5Fk", "JGwWNGJdvx8", "RgKAFK5djSk",
        "OPf0YbXqDm0", "fJ9rUzIMcZQ", "hT_nvWreIhg", "CevxZvSJLk8", "09R8_2nJtjg", "YQHsXMglC9A",
        "60ItHLz5WEA", "pRpeEdMmmQ0", "lp-EO5I60KA", "kXYiU_JCYtU", "ktvTqknDobU", "e-ORhEE9VVg",
        "BQ0mxQXmLsk", "SlPhMPnQ58k", "nfWlot6h_JM", "QcIy9NiNbmo", "LsoLEjrDogU", "450p7goxZqg",
    )

    private fun describe(call: () -> Any?): String = try {
        val json = call() as Map<*, *>
        val playability = json["playabilityStatus"] as? Map<*, *>
        val formats = (json["streamingData"] as? Map<*, *>)?.get("adaptiveFormats") as? List<*>
        "${playability?.get("status")}${(playability?.get("reason") as? String)?.let { " ($it)" } ?: ""} formats=${formats?.size ?: 0}"
    } catch (e: Throwable) {
        (e.cause ?: e).let { "${it.javaClass.simpleName}: ${it.message?.take(90)}" }
    }

    private fun nonce() = parsing.getMethod("generateContentPlaybackNonce").invoke(null) as String

    /**
     * Compares the clients of the extractor for every IP family. The web client does not return
     * streams in this call, and the android client needs a PoToken, those two are not comparable.
     */
    @Test
    fun clientsAndIpFamilies() {
        Assume.assumeTrue(System.getenv("BOT_CHECK_PROBE") == "1")
        val country = ContentCountry.DEFAULT
        val localization = Localization.DEFAULT
        for (ipFamily in listOf("both", "ipv4", "ipv6")) {
            NewPipe.init(ProbeDownloader(ipFamily))
            for (video in videoIds.take(2)) {
                val results = linkedMapOf(
                    "ios" to describe {
                        helper.getMethod("getIosPlayerResponse", countryClass, localizationClass, stringClass, stringClass, PoTokenResult::class.java)
                            .invoke(null, country, localization, video, nonce(), null)
                    },
                    "visionOS" to describe {
                        helper.getMethod("getVisionOsPlayerResponse", countryClass, localizationClass, stringClass, stringClass)
                            .invoke(null, country, localization, video, nonce())
                    },
                    "whole app path (StreamInfo.getInfo)" to try {
                        "OK, ${StreamInfo.getInfo("https://www.youtube.com/watch?v=$video").videoOnlyStreams.size} video streams"
                    } catch (e: Throwable) {
                        "${e.javaClass.simpleName}: ${e.message?.take(80)}"
                    },
                )
                results.forEach { (client, result) -> println("PROBE | $ipFamily | $video | $client | $result") }
                Thread.sleep(1500)
            }
        }
    }

    /**
     * Sends the request of the app (visionOS client) one video after the other and stops at the
     * first three bot checks in a row.
     */
    @Test
    fun visionOsBurst() {
        Assume.assumeTrue(System.getenv("BOT_CHECK_PROBE") == "1")
        NewPipe.init(ProbeDownloader("both"))
        val start = System.currentTimeMillis()
        var challengesInARow = 0
        for ((index, video) in videoIds.withIndex()) {
            val result = describe {
                helper.getMethod("getVisionOsPlayerResponse", countryClass, localizationClass, stringClass, stringClass)
                    .invoke(null, ContentCountry.DEFAULT, Localization.DEFAULT, video, nonce())
            }
            val isChallenge = "LOGIN_REQUIRED" in result || "SignInConfirm" in result || "bot" in result.lowercase()
            println("PROBE | #${index + 1} | ${(System.currentTimeMillis() - start) / 1000}s | $video | ${if (isChallenge) "BOT CHECK" else result}")
            challengesInARow = if (isChallenge) challengesInARow + 1 else 0
            if (challengesInARow >= 3) break
            Thread.sleep(600)
        }
    }
}
