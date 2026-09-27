package party.qwer.hayulgui.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/** 입력 소스 모음 (spec §5.2): 설치된 앱 어시스트 / Downloads / SAF staging. */
object ApkSources {

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val versionCode: Long,
        val base: String,
        val splits: List<String>,
        val systemApp: Boolean,
        val ownPackage: Boolean,
        val readable: Boolean,
    ) {
        /** 어시스트 카드에 띄울 adb 명령 (adb 만 사용 — 호스트 OS 무관).
         *  split 없는 앱은 Download 로 single pull,
         *  split 앱은 Download 하위 디렉터리에 base+split 전부 pull. */
        fun adbPullCommand(): String {
            val dir = "/sdcard/Download/$packageName"
            if (splits.isEmpty()) return "adb pull \"$base\" $dir.apk"
            // split 세트: 디렉터리 하나에 base+split 을 모아 온다. zip 등 호스트 도구는 불요 —
            // mkdir/`adb pull` 은 디바이스 측이라 adb 자체만 있으면 OS 무관.
            val pulls = (listOf(base) + splits).map { p ->
                "adb pull \"$p\" $dir/${p.substringAfterLast('/')}"
            }
            return (listOf("adb shell mkdir -p $dir") + pulls).joinToString("\n")
        }
    }

    fun installedApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val mode = if (Build.VERSION.SDK_INT >= 31)
            PackageManager.GET_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        else PackageManager.GET_DISABLED_COMPONENTS
        val self = context.packageName
        return pm.getInstalledPackages(mode).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val base = ai.sourceDir ?: return@mapNotNull null
            InstalledApp(
                packageName = pi.packageName,
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pi.packageName),
                versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
                base = base,
                splits = ai.splitSourceDirs?.toList() ?: emptyList(),
                systemApp = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                ownPackage = pi.packageName == self,
                // sourceDir 는 /data/app/… = apk_data_file: self 패키지만 open 가능 (own 은 readable)
                readable = pi.packageName == self,
            ).takeIf { it.packageName.isNotEmpty() }
        }.sortedWith(compareByDescending<InstalledApp> { !it.systemApp }.thenBy { it.label })
    }

    /** Downloads 계열 파일 (apk/xapk/apkm) 항목. */
    data class DownloadEntry(val id: Long, val name: String, val kind: String,
                             val size: Long, val mtime: Long)

    /** 전체파일접근(MANAGE_EXTERNAL_STORAGE) 이 필요한지 여부 (API 30+ 만 의미). */
    fun needsAllFiles(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()

    /** Downloads 의 apk/xapk/apkm 목록 (최신순). API30+ 는 전체파일접근 시 디렉터리 스캔. */
    fun downloads(context: Context): List<DownloadEntry> {
        if (Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) {
                val out2 = ArrayList<DownloadEntry>()
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?.listFiles()?.forEach { f ->
                        if (f.isDirectory) {
                            val apks = f.listFiles { g -> g.isFile && g.name.lowercase().endsWith(".apk") }
                                ?.toList() ?: emptyList()
                            if (apks.isNotEmpty()) {
                                val newest = apks.maxOf { it.lastModified() }
                                out2.add(DownloadEntry(-1, f.name + "/", "set",
                                    apks.sumOf { it.length() }, newest))
                            }
                        } else {
                            val kind = f.extension.lowercase()
                            if (f.isFile && kind in setOf("apk", "xapk", "apkm"))
                                out2.add(DownloadEntry(-1, f.name, kind, f.length(), f.lastModified()))
                        }
                    }
                return out2.sortedByDescending { it.mtime }
            }
        }
        val out = ArrayList<DownloadEntry>()
        if (Build.VERSION.SDK_INT < 29) {
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dir?.listFiles()?.forEach { f ->
                val kind = f.extension.lowercase()
                if (f.isFile && kind in setOf("apk", "xapk", "apkm"))
                    out.add(DownloadEntry(-1, f.name, kind, f.length(), f.lastModified()))
            }
            return out.sortedByDescending { it.mtime }
        }
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val cols = MediaStore.Files.FileColumns.DISPLAY_NAME
        val sel = "$cols LIKE ? OR $cols LIKE ? OR $cols LIKE ?"
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"), proj, sel,
                arrayOf("%.apk", "%.xapk", "%.apkm"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val name = c.getString(1) ?: continue
                    val kind = name.substringAfterLast('.').lowercase()
                    if (kind !in setOf("apk", "xapk", "apkm")) continue
                    out.add(DownloadEntry(id, name, kind,
                        c.getLong(2), c.getLong(3) * 1000L))
                }
            }
        }
        return out.sortedByDescending { it.mtime }
    }

    /** Downloads 항목을 staging 에 배치 — xapk/apkm 은 unzip 하여 base+split 세트 반환. */
    fun stageDownload(context: Context, entry: DownloadEntry, stagingDir: File): List<File> {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        if (entry.kind == "set") {
            // Download 내 split-set 디렉터리 — base.apk 우선으로 그대로 사용 (재복사 불요)
            val dir = File(android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                entry.name.removeSuffix("/"))
            val apks = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".apk") }
                ?.toList() ?: emptyList()
            if (apks.isEmpty()) throw IllegalStateException("디렉터리에 apk 가 없습니다: $dir")
            val base = apks.firstOrNull { it.name.equals("base.apk", true) }
                ?: apks.first()
            return (listOf(base) + apks.filter { it != base }.sortedBy { it.name })
        }
        val archive = File(stagingDir, safeName(entry.name))
        if (entry.id >= 0) {
            context.contentResolver.openInputStream(
                MediaStore.Files.getContentUri("external", entry.id)
            )?.use { ins -> archive.outputStream().use { ins.copyTo(it) } }
                ?: throw IllegalStateException("MediaStore 열기 실패 id=${entry.id}")
        } else {
            val src = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                .let { File(it, entry.name) }
            src.inputStream().use { i -> archive.outputStream().use { i.copyTo(it) } }
        }
        return finishStaging(archive, stagingDir)
    }

    /** SAF/content uri 파일을 staging 에 배치하고 base+split 세트 반환. */
    fun stageUri(context: Context, uri: Uri, name: String, stagingDir: File): List<File> {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        val archive = File(stagingDir, safeName(name))
        context.contentResolver.openInputStream(uri)?.use { ins ->
            archive.outputStream().use { ins.copyTo(it) }
        } ?: throw IllegalStateException("uri 열기 실패: $uri")
        return finishStaging(archive, stagingDir)
    }

    private fun finishStaging(archive: File, stagingDir: File): List<File> {
        val kind = archive.name.substringAfterLast('.', "").lowercase()
        if (kind == "apk") return listOf(archive)
        val dir = File(stagingDir, "unpacked")
        dir.mkdirs()
        java.util.zip.ZipInputStream(java.io.FileInputStream(archive)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.substringAfterLast('/')
                if (!n.lowercase().endsWith(".apk")) continue
                File(dir, n).outputStream().use { zin.copyTo(it) }
            }
        }
        val apks = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".apk") }
            ?.toList() ?: emptyList()
        if (apks.isEmpty()) {
            // unpack 실패 = 사실 zip 이 아닌 apk 더미? .apk 로 rename 하고 단일 파일 취급.
            val single = File(stagingDir, "base.apk")
            if (!single.name.equals(archive.name)) archive.copyTo(single, overwrite = true)
            return listOf(single)
        }
        val base = apks.firstOrNull { it.name.equals("base.apk", true) }
            ?: apks.singleOrNull() ?: throw IllegalStateException("base.apk 를 찾을 수 없습니다 (${apks.size}개)")
        return (listOf(base) + apks.filter { it != base })
    }

    private fun safeName(n: String) = n.replace(Regex("[/\\:]"), "_")

    /** apk의 manifest(package 명) 읽기. */
    fun packageOf(apk: File): String? = runCatching {
        Zip.readEntry(apk, "AndroidManifest.xml")?.let { Axml.parse(it).readAttribute("manifest", "package") }
    }.getOrNull()

    /** SAF/OpenDocument 로 선택된 문서를 staging dir 에 복사. */
    fun importSaf(context: Context, uri: Uri, stagingDir: File): File {
        stagingDir.mkdirs()
        val name = queryName(context, uri) ?: "imported-${System.currentTimeMillis()}.apk"
        val dest = File(stagingDir, name.replace(Regex("[/\\\\:]"), "_"))
        context.contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { ins.copyTo(it) }
        } ?: throw IllegalStateException("URI 열기 실패: $uri")
        return dest
    }

    /** MediaStore 항목을 staging 에 복사. */
    fun importMediaStore(context: Context, id: Long, name: String, stagingDir: File): File {
        stagingDir.mkdirs()
        val dest = File(stagingDir, name.replace(Regex("[/\\\\:]"), "_"))
        val uri = MediaStore.Files.getContentUri("external", id)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { ins.copyTo(it) }
        } ?: throw IllegalStateException("MediaStore 열기 실패 id=$id")
        return dest
    }

    /** HayulGUI 자신의 설치 APK (self-patch 지원; base+split 모두). */
    fun copyOwnApk(context: Context, stagingDir: File): List<File> {
        stagingDir.mkdirs()
        val ai = context.applicationInfo
        val list = (listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList()))
        return list.map { src ->
            val dest = File(stagingDir, File(src).name)
            File(src).inputStream().use { i -> dest.outputStream().use { i.copyTo(it) } }
            dest
        }
    }

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && !it.isNull(i)) return it.getString(i)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
