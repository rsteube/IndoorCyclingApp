package app.indoorbike.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Image-share pipeline for ride summaries.
 *
 *   Compose card  ─drawn into─►  GraphicsLayer  ─toImageBitmap─►  Bitmap
 *                                                                     ├─►  MediaStore (Pictures/Indoor Bike)
 *                                                                     └─►  FileProvider URI  ──► ACTION_SEND chooser
 *
 * The capture step is a suspend fun because [GraphicsLayer.toImageBitmap] hops to
 * the GPU. Caller is responsible for invoking it from a coroutine after the card
 * has been laid out at least once (a single [androidx.compose.runtime.LaunchedEffect]
 * tick is enough on every device tested).
 */
object ShareImageGenerator {

    private const val ALBUM_NAME = "Indoor Bike"
    private const val SHARED_DIR = "shared_images"

    suspend fun captureLayer(layer: GraphicsLayer): Bitmap {
        val image = layer.toImageBitmap()
        return image.asAndroidBitmap()
    }

    /**
     * Writes [bitmap] to the device's Pictures collection under "Indoor Bike/".
     * Uses scoped storage (MediaStore) on Q+, legacy filesystem on 26..28.
     * Returns the inserted MediaStore URI on success or null on any failure.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? = try {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val albumDir = File(picturesDir, ALBUM_NAME).apply { mkdirs() }
                val target = File(albumDir, "$displayName.png")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, target.absolutePath)
            }
        }

        val itemUri = resolver.insert(collection, values) ?: return@saveToGallery null
        resolver.openOutputStream(itemUri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: run {
            resolver.delete(itemUri, null, null)
            return@saveToGallery null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }
        itemUri
    } catch (t: Throwable) {
        null
    }

    /**
     * Writes [bitmap] to the app's cache and returns a content:// URI granted to
     * receivers via FileProvider — the safe way to hand a file to ACTION_SEND
     * since Android N (file:// triggers FileUriExposedException).
     */
    fun stageForShare(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val file = File(dir, "$displayName.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Builds and launches the system share-sheet chooser with [uri] attached as
     * a `image/png` stream. Single-use — the chooser closes itself after pick.
     */
    fun launchShareChooser(context: Context, uri: Uri, chooserTitle: String = "Share ride") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Some receivers (Drive, Files, Gmail) check ClipData rather than EXTRA_STREAM.
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
