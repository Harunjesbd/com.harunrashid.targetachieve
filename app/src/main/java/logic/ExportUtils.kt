package com.harunrashid.targetachieve.logic

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object ExportUtils {

    fun exportViewAsJpeg(context: Context, view: View, fileName: String) {
        try {
            if (view.width == 0 || view.height == 0) {
                Toast.makeText(context, "রিপোর্ট এখনো লোড হয়নি, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show()
                return
            }

            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            view.draw(canvas)

            val uri = saveBitmapToGallery(context, bitmap, fileName)
            if (uri != null) {
                Toast.makeText(context, "ছবি সেভ হয়েছে: Pictures/TargetAchieve ফোল্ডারে", Toast.LENGTH_LONG).show()
                shareFile(context, uri, "image/jpeg")
            } else {
                Toast.makeText(context, "সেভ করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "এক্সপোর্ট এরর: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TargetAchieve")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return uri
    }

    fun exportViewAsPdf(context: Context, view: View, fileName: String) {
        try {
            if (view.width == 0 || view.height == 0) {
                Toast.makeText(context, "রিপোর্ট এখনো লোড হয়নি, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show()
                return
            }

            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(view.width, view.height, 1).create()
            val page = document.startPage(pageInfo)

            page.canvas.drawColor(Color.WHITE)
            view.draw(page.canvas)
            document.finishPage(page)

            val pdfDir = File(context.cacheDir, "reports")
            if (!pdfDir.exists()) pdfDir.mkdirs()
            val pdfFile = File(pdfDir, "$fileName.pdf")

            FileOutputStream(pdfFile).use { out ->
                document.writeTo(out)
            }
            document.close()

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
            Toast.makeText(context, "PDF তৈরি হয়েছে", Toast.LENGTH_SHORT).show()
            shareFile(context, uri, "application/pdf")

        } catch (e: Exception) {
            Toast.makeText(context, "PDF এক্সপোর্ট এরর: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Report"))
    }
}