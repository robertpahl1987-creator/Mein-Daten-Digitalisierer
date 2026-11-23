package de.meindaten.digitalisierer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Texterkennung komplett auf dem Handy (ML Kit, offline) – nichts verlässt das Gerät. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun run(ctx: Context, bytes: ByteArray, mime: String): String =
        if (mime == "application/pdf") pdf(ctx, bytes) else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
            try { recognize(bmp) } finally { bmp.recycle() }
        }

    private suspend fun pdf(ctx: Context, bytes: ByteArray): String {
        val dir = File(ctx.cacheDir, "scans").apply { mkdirs() }
        val tmp = File(dir, "ocr_${System.nanoTime()}.pdf")
        tmp.writeBytes(bytes)
        val sb = StringBuilder()
        try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until minOf(r.pageCount, 10)) {
                        r.openPage(i).use { page ->
                            val w = 1700
                            val h = (w.toFloat() * page.height / page.width).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if (sb.isNotEmpty()) sb.append("\n\n")
                            sb.append(recognize(bmp))
                            bmp.recycle()
                        }
                    }
                }
            }
        } finally {
            tmp.delete()
        }
        return sb.toString()
    }

    private suspend fun recognize(bmp: Bitmap): String = suspendCancellableCoroutine { c ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { c.resume(it.text) }
            .addOnFailureListener { c.resumeWithException(it) }
    }
}
