package tw.nekomimi.nekogram.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import tw.nekomimi.nekogram.NekoConfig
import java.io.File
import java.util.*

object EnvUtil {

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    val rootDirectories: List<File> by lazy {

        try {
            val mStorageManager = ApplicationLoader.applicationContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            (mStorageManager.javaClass.getMethod("getVolumePaths").invoke(mStorageManager) as Array<String>).map { File(it) }
        } catch (_:  Throwable) {
            AndroidUtilities.getRootDirs()
        }

    }

    @JvmStatic
    val availableDirectories
        get() = LinkedList<File>().apply {

            add(File(ApplicationLoader.getDataDirFixed(), "files/media"))
            add(File(ApplicationLoader.getDataDirFixed(), "cache/media"))

            rootDirectories.forEach {

                add(File(it, "Android/data/" + ApplicationLoader.applicationContext.packageName + "/files"))
                add(File(it, "Android/data/" + ApplicationLoader.applicationContext.packageName + "/cache"))

            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                add(Environment.getExternalStoragePublicDirectory("NCgram"))
            }

        }.map { it.path }.toTypedArray()

    // This is the only media path of NekoX, don't use other!
    @JvmStatic
    fun getTelegramPath(): File {

        if (NekoConfig.cachePath.String() == "") {
            // https://github.com/NekoX-Dev/NekoX/issues/284
            NekoConfig.cachePath.setConfigString(availableDirectories[2])
        }
        var telegramPath = File(NekoConfig.cachePath.String())
        if (telegramPath.isDirectory || telegramPath.mkdirs()) {
            return telegramPath
        } else {
            NekoConfig.cachePath.setConfigString(availableDirectories[2])
        }

        // fallback

        telegramPath = ApplicationLoader.applicationContext.getExternalFilesDir(null) ?: File(availableDirectories[1])

        if (telegramPath.isDirectory || telegramPath.mkdirs()) {

            return telegramPath

        }

        telegramPath = File(availableDirectories[1])

        if (!telegramPath.isDirectory) telegramPath.mkdirs()

        return telegramPath

    }

    @JvmStatic
    fun getShareCachePath(): File {
        // fix SDK < A11
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getTelegramPath()
        }
        return File(availableDirectories[3])
    }
}
