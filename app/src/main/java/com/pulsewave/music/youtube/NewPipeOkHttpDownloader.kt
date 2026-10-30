package com.pulsewave.music.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.brotli.BrotliInterceptor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class NewPipeOkHttpDownloader private constructor(
    val okHttpClient: OkHttpClient
) : Downloader() {

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = when {
            dataToSend != null -> dataToSend.toRequestBody(null)
            httpMethod.equals("POST", ignoreCase = true) ||
                httpMethod.equals("PUT", ignoreCase = true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(httpMethod, requestBody)
            .header("User-Agent", USER_AGENT)

        if (url.contains("youtube.com")) {
            builder.header("Cookie", "CONSENT=PENDING+987; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
        }

        headers.forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value ->
                builder.addHeader(name, value)
            }
        }

        okHttpClient.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("Rate limit / ReCaptcha requested", url)
            }
            val bodyString = response.body?.string()
            val finalUrl = response.request.url.toString()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                finalUrl
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

        @Volatile
        private var instance: NewPipeOkHttpDownloader? = null

        fun getInstance(): NewPipeOkHttpDownloader {
            return instance ?: synchronized(this) {
                instance ?: NewPipeOkHttpDownloader(
                    OkHttpClient.Builder()
                        .connectTimeout(25, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(25, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .addInterceptor(BrotliInterceptor)
                        .build()
                ).also { instance = it }
            }
        }
    }
}
