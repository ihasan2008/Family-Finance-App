package com.example.core.reports

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PdfExportHelper {

    suspend fun writePdfToUri(
        context: Context,
        uri: Uri,
        report: FamilyReportData
    ): Result<PdfGenerationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val result = FamilyReportPdfDocumentBuilder.generatePdfBytes(report)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(result.bytes)
                out.flush()
            } ?: error("Unable to open destination file stream.")
            result
        }
    }

    suspend fun shareReportPdf(
        context: Context,
        report: FamilyReportData
    ): Result<PdfGenerationResult> {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val result = FamilyReportPdfDocumentBuilder.generatePdfBytes(report)
                val dir = File(context.cacheDir, "shared_reports").apply { mkdirs() }
                val outFile = File(dir, result.suggestedFileName)
                FileOutputStream(outFile).use { stream ->
                    stream.write(result.bytes)
                    stream.flush()
                }
                result to outFile
            }
        }
        return prepared.mapCatching { (result, file) ->
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "${report.familyName} - ${report.reportType.displayName}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Attached ${report.reportType.displayName} (${report.periodLabel}) generated on-device by Family Finance."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Share Financial Report PDF").apply {
                if (context.findActivity() == null) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
            result
        }
    }

    suspend fun renderPreviewBitmaps(
        context: Context,
        report: FamilyReportData,
        maxPreviewPages: Int = 12
    ): List<ImageBitmap> = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "ff_preview_${System.currentTimeMillis()}.pdf")
        try {
            val bytes = FamilyReportPdfDocumentBuilder.generatePdfBytes(report).bytes
            if (bytes.isEmpty()) return@withContext emptyList()
            FileOutputStream(tempFile).use { it.write(bytes) }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val count = minOf(renderer.pageCount, maxPreviewPages)
                    val pages = ArrayList<ImageBitmap>(count)
                    for (i in 0 until count) {
                        renderer.openPage(i).use { page ->
                            val scale = 2
                            val bitmap = Bitmap.createBitmap(
                                page.width * scale,
                                page.height * scale,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            pages += bitmap.asImageBitmap()
                        }
                    }
                    pages
                }
            }
        } catch (_: Throwable) {
            // Fallback to structured Compose A4 sheet preview if native PdfRenderer is unavailable
            emptyList()
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    fun printReportPdf(
        context: Context,
        report: FamilyReportData,
        onError: (String) -> Unit
    ) {
        val activity = context.findActivity() ?: context
        val printManager = activity.getSystemService(PrintManager::class.java)
        if (printManager == null) {
            onError("Android Print service is not available on this device.")
            return
        }
        val jobName = FamilyReportPdfDocumentBuilder.suggestedFileName(report).removeSuffix(".pdf")
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        runCatching {
            printManager.print(
                jobName,
                FamilyReportPrintAdapter(report),
                attributes
            )
        }.onFailure { err ->
            onError("Unable to start print job: ${err.message ?: "Unknown error"}")
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private class FamilyReportPrintAdapter(
    private val report: FamilyReportData
) : PrintDocumentAdapter() {

    private var cachedBytes: ByteArray? = null
    private var totalPages: Int = 1

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        try {
            val generated = FamilyReportPdfDocumentBuilder.generatePdfBytes(report)
            cachedBytes = generated.bytes
            totalPages = generated.pageCount.coerceAtLeast(1)
            val info = PrintDocumentInfo.Builder(generated.suggestedFileName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(totalPages)
                .build()
            callback.onLayoutFinished(info, true)
        } catch (err: Exception) {
            callback.onLayoutFailed(err.message ?: "Failed to prepare PDF for printing.")
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }
        try {
            val bytes = cachedBytes ?: FamilyReportPdfDocumentBuilder.generatePdfBytes(report).bytes
            FileOutputStream(destination.fileDescriptor).use { out ->
                out.write(bytes)
                out.flush()
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (err: Exception) {
            callback.onWriteFailed(err.message ?: "Failed to write PDF to printer spool.")
        }
    }
}
