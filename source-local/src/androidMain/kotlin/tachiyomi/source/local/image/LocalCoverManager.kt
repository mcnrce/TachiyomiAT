package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

actual class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    actual fun find(mangaUrl: String): UniFile? {
        // 1. جلب قائمة الملفات/المجلدات داخل مجلد المانجا (التي تمثل الفصول)
        val mangaFiles = fileSystem.getFilesInMangaDirectory(mangaUrl)

        // 2. المحاولة الأولى: البحث عن ملف يبدأ اسمه بـ "cover" في المجلد الرئيسي للمانجا
        val explicitCover = mangaFiles
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }

        if (explicitCover != null) return explicitCover

        // 3. المحاولة الثانية: إذا لم يوجد ملف غلاف، ندخل لأول فصل متاح ونجلب أول صورة منه
        return mangaFiles
            .filter { it.isDirectory } // نركز على المجلدات التي تحتوي على الصور
            .sortedBy { it.name }      // ترتيب الفصول تصاعدياً (Chapter 1, 2...)
            .firstNotNullOfOrNull { firstChapter ->
                firstChapter.listFiles().orEmpty()
                    .filter { it.isFile }
                    .sortedBy { it.name } // ترتيب الصور (001.jpg, 002.jpg...)
                    .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
            }
    }

    actual fun update(
        manga: SManga,
        inputStream: InputStream,
    ): UniFile? {
        val directory = fileSystem.getMangaDirectory(manga.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = find(manga.url) ?: directory.createFile(DEFAULT_COVER_NAME)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.createNoMediaFile(directory, context)

        manga.thumbnail_url = targetFile.uri.toString()
        return targetFile
    }
}
