package com.example.cancerapp.documents

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

class DocumentTextExtractor(private val context: Context) {
    data class Result(val displayName: String, val mimeType: String, val text: String)

    fun extract(uri: Uri): Result {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = displayName(uri)
        val text = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(uri)
            mime.startsWith("image/") -> extractImage(uri)
            mime.startsWith("text/") || name.endsWith(".txt", true) || name.endsWith(".csv", true) ->
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("The selected text file could not be opened")
            else -> error("Unsupported file type: ${mime.ifBlank { name.substringAfterLast('.', "unknown") }}")
        }.trim()
        require(text.isNotBlank()) { "No readable text was found in $name" }
        return Result(name, mime, text.take(120_000))
    }

    private fun extractPdf(uri: Uri): String {
        PDFBoxResourceLoader.init(context)
        val temporary = File.createTempFile("medical_", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            } ?: error("The selected PDF could not be opened")
            return PDDocument.load(temporary).use { PDFTextStripper().getText(it) }
        } finally {
            temporary.delete()
        }
    }

    private fun extractImage(uri: Uri): String {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
            ?: error("The selected image could not be decoded")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text.orEmpty()
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "document"
    }
}
