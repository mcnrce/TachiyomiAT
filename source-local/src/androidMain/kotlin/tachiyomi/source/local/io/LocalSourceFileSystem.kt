package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager

actual class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    // 1. تغيير المجلد الأساسي ليكون مجلد التحميلات بدلاً من مجلد local
    actual fun getBaseDirectory(): UniFile? {
        return storageManager.getDownloadsDirectory()
    }

    // 2. تعديل جلب المجلدات ليدخل داخل مجلدات المصادر
    // Downloads -> [Source Name] -> [Manga Name]
    actual fun getFilesInBaseDirectory(): List<UniFile> {
        val root = getBaseDirectory() ?: return emptyList()
        
        return root.listFiles().orEmpty()
            .filter { it.isDirectory } // هذه مجلدات المصادر (مثل MangaDex)
            .flatMap { it.listFiles().orEmpty().toList() } // جلب ما بداخلها (المانجا)
            .filter { it.isDirectory }
    }

    // 3. البحث عن مجلد المانجا في كافة مجلدات المصادر
    actual fun getMangaDirectory(name: String): UniFile? {
        val root = getBaseDirectory() ?: return null
        
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { sourceFolder -> 
                sourceFolder.findFile(name) // البحث عن المانجا داخل كل مصدر
            }
            .firstOrNull { it.isDirectory }
    }

    actual fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }
}
