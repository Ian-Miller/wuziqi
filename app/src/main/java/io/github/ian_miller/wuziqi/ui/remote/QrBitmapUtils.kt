package io.github.ian_miller.wuziqi.ui.remote

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import java.io.File

/** 将 Compose Painter（如 qrose QrCodePainter）渲染为 Bitmap */
fun painterToBitmap(painter: Painter, density: Density, sizePx: Int = 512): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val androidCanvas = android.graphics.Canvas(bitmap)
    androidCanvas.drawColor(android.graphics.Color.WHITE)
    val composeCanvas = Canvas(androidCanvas)
    val drawScope = CanvasDrawScope()
    drawScope.draw(density, LayoutDirection.Ltr, composeCanvas, Size(sizePx.toFloat(), sizePx.toFloat())) {
        with(painter) { draw(Size(sizePx.toFloat(), sizePx.toFloat())) }
    }
    return bitmap
}

/** 将 Bitmap 保存到相册 Pictures/五子棋 目录，返回保存的 Uri */
fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String = "gomoku_qr_${System.currentTimeMillis()}.png"): Uri? {
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/五子棋")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
    resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
    }
    return uri
}

/** 将 Bitmap 写入 cache，通过 FileProvider 分享 */
fun shareBitmapAsImage(context: Context, bitmap: Bitmap) {
    val cacheFile = File(context.cacheDir, "qr_share.png")
    cacheFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "五子棋邀请码 QR，用 App 扫描加入对局")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享邀请码"))
}
