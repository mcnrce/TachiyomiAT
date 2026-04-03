package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream
import tachiyomi.source.local.io.Archive

private const val DEFAULT_COVER_NAME = "cover.jpg"

actual class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    actual fun find(mangaUrl: String): UniFile? {
        // 1. الحصول على المجلد الحقيقي للمانجا (الذي يحتوي على الفصول)
        val mangaDir = fileSystem.getMangaDirectory(mangaUrl) ?: return null
        val mangaFiles = mangaDir.listFiles().orEmpty()

        // 2. التحقق أولاً إذا كان هناك ملف غلاف صريح (cover.jpg/png) في مجلد المانجا الرئيسي
        val explicitCover = mangaFiles
            .filter { it.isFile && it.nameWithoutExtension.orEmpty().equals("cover", ignoreCase = true) }
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }

        if (explicitCover != null) return explicitCover

        // 3. إذا لم يوجد، نطبق منطقك: نختار أول صورة من أول فصل (مجلد)
        return mangaFiles
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') } // تجاهل المجلدات المخفية
            .sortedWith { f1, f2 -> f1.name.orEmpty().compareTo(f2.name.orEmpty(), ignoreCase = true) } // ترتيب الفصول (001, 002...)
            .firstNotNullOfOrNull { firstChapter ->
                // الدخول للمجلد وجلب أول ملف صورة مر تب
                firstChapter.listFiles().orEmpty()
                    .filter { it.isFile && !it.name.orEmpty().startsWith('.') }
                    .sortedWith { f1, f2 -> f1.name.orEmpty().compareTo(f2.name.orEmpty(), ignoreCase = true) } // ترتيب الصور (01.jpg, 02.jpg...)
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

        // محاولة إيجاد ملف الغلاف الحالي أو إنشاء ملف جديد باسم cover.jpg في مجلد المانجا
        val targetFile = find(manga.url) ?: directory.createFile(DEFAULT_COVER_NAME) 
            ?: return null

        return try {
            inputStream.use { input ->
                targetFile.openOutputStream().use { output ->
                    input.copyTo(output)
                }
            }
            DiskUtil.createNoMediaFile(directory, context)
            manga.thumbnail_url = targetFile.uri.toString()
            targetFile
        } catch (e: Exception) {
            null
        }
    }
}
