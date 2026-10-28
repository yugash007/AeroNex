package com.aeronex.app

import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

data class LocalModelFile(val size: Long, val sha: String)

data class LocalModelProfile(val file: String, val size: Long, val sha: String, val sourceUrl: String? = null,
                             val archiveFiles: Map<String, LocalModelFile> = emptyMap()) {
    val runtimeFile: String get() = if (file.endsWith(".zip")) file.removeSuffix(".zip") else file
    /** The pinned download source; a profile without one (e.g. a GitHub-archive-only KWS file) is import-only. */
    val url: String get() = checkNotNull(sourceUrl) { "$file has no download source; import it from a file." }
}

/** Inventory uses a read lock; publication uses a write lock. A Vosk runtime must
 * hold a read lock on its worker for the entire Model/Recognizer session, including
 * closing them, so replacement cannot remove files still used by the model. */
internal object LocalModelDirectoryLocks {
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()
    fun forDirectory(directory: File): ReentrantReadWriteLock =
        locks.computeIfAbsent(directory.canonicalPath) { ReentrantReadWriteLock(true) }
}

/** Delete links as leaf nodes, including a link at the root. Never use Kotlin's
 * File.deleteRecursively here: its walker follows old model directory links. */
internal object LocalModelFileCleanup {
    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

/** An import/download becomes visible only after its size and hash are verified. */
class VerifiedModelStore(private val directory: File) {
    fun install(profile: LocalModelProfile, source: InputStream,
                cancelled: () -> Boolean, progress: (Long) -> Unit = {}) {
        val archive = profile.file.endsWith(".zip")
        check(profile.file.isNotEmpty() && profile.file.none { it == '/' || it == '\\' || it == ':' }
            && profile.file != "." && profile.file != "..") { "Invalid model filename." }
        check(!archive || profile.archiveFiles.isNotEmpty()) { "The ZIP has no verified model manifest." }
        val extractedSize = profile.archiveFiles.values.fold(0L) { size, file ->
            check(file.size >= 0 && file.size <= Long.MAX_VALUE - size) { "Invalid model manifest size." }
            size + file.size
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model storage." }
        recover(listOf(profile), cancelled)
        sweepStaleTransfers()
        check(profile.size >= 0 && profile.size <= Long.MAX_VALUE - extractedSize - 64L * 1024 * 1024) {
            "Invalid model archive size."
        }
        check(directory.usableSpace > profile.size + extractedSize + 64L * 1024 * 1024) {
            "Not enough storage. Free space and try again."
        }
        val temporary = registerTransfer { File.createTempFile("model-", ".part", directory) }
        var staging: File? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            temporary.outputStream().use { target ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (cancelled()) throw CancellationException("Model setup cancelled.")
                    val n = source.read(buffer)
                    if (n < 0) break
                    count += n
                    check(count <= profile.size) { "This file is larger than the selected model. Choose ${profile.file}." }
                    digest.update(buffer, 0, n)
                    target.write(buffer, 0, n)
                    progress(count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(count == profile.size && hash == profile.sha) {
                "The model file is incomplete or does not match ${profile.file}. Download it again."
            }
            if (cancelled()) throw CancellationException("Model setup cancelled.")
            if (archive) {
                staging = registerTransfer { Files.createTempDirectory(directory.toPath(), "model-stage-").toFile() }
                val model = File(staging, profile.runtimeFile).also { check(it.mkdir()) }
                extract(profile, temporary, model, cancelled)
                publish(profile, model, File(directory, profile.runtimeFile), cancelled)
            } else {
                publish(profile, temporary, File(directory, profile.file), cancelled)
            }
        } finally {
            temporary.delete()
            staging?.let { cleanupAfterPublication(it) }
            activeTransfers.remove(temporary.path)
            staging?.let { activeTransfers.remove(it.path) }
        }
    }

    /** Remove transfer files left by a process that was terminated mid-transfer.
     * Live transfers in this process are registered, so their files are kept. */
    private fun sweepStaleTransfers() {
        synchronized(activeTransfers) {
            directory.listFiles()?.forEach { entry ->
                val path = entry.toPath()
                val stale = if (entry.name.startsWith("model-stage-")) Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    else entry.name.startsWith("model-") && entry.name.endsWith(".part") &&
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                if (!stale) return@forEach
                // A sweep problem must never block a new install: skip entries that cannot be resolved.
                val key = try { entry.canonicalPath }
                    catch (_: java.io.IOException) { return@forEach }
                    catch (_: SecurityException) { return@forEach }
                if (key !in activeTransfers) cleanupAfterPublication(entry)
            }
        }
    }

    /** Creation and registration are atomic with respect to a sweep. */
    private fun registerTransfer(create: () -> File): File = synchronized(activeTransfers) {
        create().canonicalFile.also { activeTransfers.add(it.path) }
    }

    /** Recover before a runtime takes its read lease. Read lease holders must not
     * upgrade; states() skips recovery under such a lease and reports non-ready.
     * The deterministic backup survives termination at either publication rename. */
    internal fun recover(profiles: Collection<LocalModelProfile>, cancelled: () -> Boolean = { false }) {
        val pending = profiles.filter { profile -> profile.file.endsWith(".zip") &&
            profile.archiveFiles.isNotEmpty() && safePath(profile.runtimeFile) && '/' !in profile.runtimeFile &&
            Files.exists(backupFor(profile.runtimeFile).toPath(), LinkOption.NOFOLLOW_LINKS) }
        if (pending.isEmpty()) return
        val directoryLock = LocalModelDirectoryLocks.forDirectory(directory)
        check(directoryLock.readHoldCount == 0 || directoryLock.isWriteLockedByCurrentThread) {
            "Recover model storage before acquiring its runtime read lease."
        }
        val lock = directoryLock.writeLock()
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) checkCancelled(cancelled)
        try {
            pending.forEach { profile ->
                checkCancelled(cancelled)
                val backup = backupFor(profile.runtimeFile)
                if (!Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                val target = File(directory, profile.runtimeFile)
                if (LocalModelInventory.validArchive(target, profile)) {
                    // The replacement rename completed before termination; keep the verified target.
                    cleanupAfterPublication(backup)
                } else if (LocalModelInventory.validArchive(backup, profile)) {
                    // Termination happened before publication (or the target is corrupt).
                    // The verified backup stays intact until the obstructing target is removed.
                    LocalModelFileCleanup.deleteTree(target)
                    Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                // Keep invalid backups for a future repair; they never count as ready.
            }
        } finally { lock.unlock() }
    }

    private fun backupFor(runtimeFile: String) = File(directory, ".model-backup-$runtimeFile")

    private fun cleanupAfterPublication(file: File) {
        // A failed cleanup must retain the usable target; recovery can retry the cleanup later.
        try { LocalModelFileCleanup.deleteTree(file) }
        catch (_: java.io.IOException) { }
        catch (_: SecurityException) { }
    }

    private fun extract(profile: LocalModelProfile, archive: File, model: File, cancelled: () -> Boolean) {
        val allowedDirectories = mutableSetOf(profile.runtimeFile)
        profile.archiveFiles.keys.forEach { path ->
            check(safePath(path)) { "Invalid path in the model manifest." }
            var directoryPath = "${profile.runtimeFile}/$path".substringBeforeLast('/')
            while (true) {
                allowedDirectories.add(directoryPath)
                if ('/' !in directoryPath) break
                directoryPath = directoryPath.substringBeforeLast('/')
            }
        }
        val seen = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkCancelled(cancelled)
                val entry = entries.nextElement()
                val name = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                check(safePath(name) && seen.add(name)) { "The ZIP contains an unsafe or duplicate path." }
                check(seen.size <= allowedDirectories.size + profile.archiveFiles.size) {
                    "The ZIP contains too many entries."
                }
                if (entry.isDirectory) {
                    check(name in allowedDirectories && entry.size == 0L) { "Unexpected directory in the model ZIP." }
                    continue
                }
                check(name.startsWith("${profile.runtimeFile}/")) { "The ZIP has an unexpected model directory." }
                val relative = name.removePrefix("${profile.runtimeFile}/")
                val expected = profile.archiveFiles[relative] ?: error("Unexpected file in the model ZIP: $relative")
                check(entry.size == expected.size) { "A file in the ZIP is larger or smaller than its verified manifest." }
                val target = File(model, relative)
                val parent = checkNotNull(target.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "Cannot create model directory." }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                zip.getInputStream(entry).use { source ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkCancelled(cancelled)
                            val n = source.read(buffer)
                            if (n < 0) break
                            count += n
                            check(count <= expected.size) { "A ZIP file exceeds its verified size." }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                check(count == expected.size && digest.digest().hex() == expected.sha) {
                    "A file in the model ZIP does not match its verified manifest."
                }
                files.add(relative)
            }
        }
        check(files == profile.archiveFiles.keys) { "The model ZIP is missing required files." }
        checkCancelled(cancelled)
    }

    private fun publish(profile: LocalModelProfile, staged: File, target: File, cancelled: () -> Boolean) {
        val lock = LocalModelDirectoryLocks.forDirectory(directory).writeLock()
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) checkCancelled(cancelled)
        try {
            checkCancelled(cancelled)
            if (!staged.isDirectory) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
                return
            }
            // Another queued installer may have retained a backup after this
            // transfer began. Resolve it again while holding the publication lock.
            recover(listOf(profile), cancelled)
            val backup = backupFor(target.name)
            var keepBackup = false
            try {
                // Any usable pending backup was recovered before import. A remaining invalid
                // slot can be removed without following links before this transaction begins.
                LocalModelFileCleanup.deleteTree(backup)
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
                    Files.move(target.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                try {
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (failure: Throwable) {
                    if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (restoreFailure: Throwable) {
                            keepBackup = true
                            failure.addSuppressed(restoreFailure)
                        }
                    }
                    throw failure
                }
            } finally {
                // If storage itself fails during rollback, retain the recoverable old model.
                if (!keepBackup) cleanupAfterPublication(backup)
            }
        } finally { lock.unlock() }
    }

    private fun safePath(path: String): Boolean = path.isNotEmpty() &&
        '\\' !in path && ':' !in path && '\u0000' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("Model setup cancelled.")
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    fun download(profile: LocalModelProfile, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        var url = URL(profile.url)
        repeat(6) {
            if (cancelled()) throw CancellationException("Model download cancelled.")
            check(url.protocol == "https") { "Model downloads require a secure connection." }
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            try {
                when (val code = connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: error("The model server returned an invalid redirect.")
                        url = URL(url, location)
                    }
                    200 -> {
                        connection.inputStream.use { install(profile, it, cancelled, progress) }
                        return
                    }
                    else -> error("The model server returned HTTP $code. Try again later or import a downloaded file.")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("The model server redirected too many times. Try importing a downloaded file.")
    }

    companion object {
        /** Canonical temporary paths of the transfers running in this process. */
        private val activeTransfers = ConcurrentHashMap.newKeySet<String>()
    }
}
