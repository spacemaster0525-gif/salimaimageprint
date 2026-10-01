package com.salimaprint.app.render

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PdfPrint
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * يحمّل صفحة ويب (رابط) أو HTML في WebView غير ظاهر ثم يحوّلها إلى PDF بحجم A4.
 * يجب استدعاء الدوال من الخيط الرئيسي؛ النتيجة تصل على الخيط الرئيسي أيضاً.
 */
object WebToPdf {
    private const val TIMEOUT_MS = 60_000L
    private const val SETTLE_MS = 900L

    fun convertUrl(context: Context, url: String, out: File, onDone: (Result<File>) -> Unit) {
        run(context, intArrayOf(400, 400, 400, 400), out, url, { it.loadUrl(url) }, onDone)
    }

    /** marginsMils = [يسار، أعلى، يمين، أسفل] بالميل (1/1000 بوصة). */
    fun convertHtml(
        context: Context, html: String, marginsMils: IntArray, out: File,
        onDone: (Result<File>) -> Unit
    ) {
        run(context, marginsMils, out, null, {
            it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }, onDone)
    }

    private fun run(
        context: Context, margins: IntArray, out: File, mainUrl: String?,
        load: (WebView) -> Unit, cb: (Result<File>) -> Unit
    ) {
        try { WebView.enableSlowWholeDocumentDraw() } catch (_: Throwable) { }
        val handler = Handler(Looper.getMainLooper())
        val web = WebView(context)
        var finished = false

        fun finish(r: Result<File>) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            try { web.stopLoading(); web.destroy() } catch (_: Throwable) { }
            cb(r)
        }

        val startPdf = Runnable {
            if (finished) return@Runnable
            try {
                val attrs = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                    .setMinMargins(PrintAttributes.Margins(margins[0], margins[1], margins[2], margins[3]))
                    .build()
                val adapter = web.createPrintDocumentAdapter("SalimaPrint")
                PdfPrint(attrs).print(adapter, out, object : PdfPrint.Callback {
                    override fun onDone(pdf: File) { finish(Result.success(pdf)) }
                    override fun onError(message: String) {
                        finish(Result.failure(Exception("PDF: $message")))
                    }
                })
            } catch (t: Throwable) {
                finish(Result.failure(Exception(t.message ?: t.toString())))
            }
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // قد تتكرر بسبب إعادة التوجيه: نعيد ضبط المهلة ونأخذ آخر صفحة
                handler.removeCallbacks(startPdf)
                handler.postDelayed(startPdf, SETTLE_MS)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?, errorCode: Int, description: String?, failingUrl: String?
            ) {
                // أخطاء الموارد الفرعية (صور ناقصة) لا تُفشل الطباعة؛ فقط خطأ الصفحة نفسها
                if (mainUrl != null && failingUrl == mainUrl) {
                    finish(Result.failure(Exception("تعذّر تحميل الصفحة: ${description ?: errorCode}")))
                }
            }
        }
        handler.postDelayed({ finish(Result.failure(Exception("انتهت مهلة تحميل الصفحة"))) }, TIMEOUT_MS)
        load(web)
    }
}
