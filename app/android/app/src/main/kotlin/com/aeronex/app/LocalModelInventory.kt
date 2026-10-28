package com.aeronex.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** File contents are authoritative; legacy preference flags are not an inventory.
 * Call from a worker. Each file is hashed once per launch, then only if it changes.
 */
class LocalModelInventory(
    private val directory: File,
    private val profiles: Map<String, LocalModelProfile>
) {
    private data class Fingerprint(val size: Long, val modified: String, val key: String?, val regular: Boolean)
    private data class Checked(val fingerprint: Fingerprint, val state: String)
    private val checked = mutableMapOf<String, Checked>()

    fun states(): Map<String, String> {
        val directoryLock = LocalModelDirectoryLocks.forDirectory(directory)
        // Recover pending publication before acquiring a lease. A runtime may call
        // states while already holding a read lease: never attempt a lock upgrade.
        if (directoryLock.readHoldCount == 0) {
            try { VerifiedModelStore(directory).recover(profiles.values) }
            catch (_: IOException) { }
            catch (_: SecurityException) { }
        }
        val lock = directoryLock.readLock()
        lock.lock()
        try {
            // Acquire the shared directory lease before the cache monitor. Runtime
            // lease holders may re-enter states while a writer is queued.
            return synchronized(this) { profiles.mapValues { (id, profile) ->
                try {
                    if (!profile.file.endsWith(".zip")) {
                        checkFile(id, File(directory, profile.file), LocalModelFile(profile.size, profile.sha))
                    } else if (profile.archiveFiles.isEmpty()) {
                        "invalid"
                    } else {
                        val root = File(directory, profile.runtimeFile)
                        val rootAttrs = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS)
                        if (!rootAttrs.isDirectory || rootAttrs.isSymbolicLink || !expectedTree(root, profile)) "invalid"
                        else {
                            val states = profile.archiveFiles.map { (relative, required) ->
                                val file = File(root, relative)
                                var parent = file.parentFile
                                var parentsValid = true
                                while (parent != root && parent != null) {
                                    val attrs = Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java,
                                        LinkOption.NOFOLLOW_LINKS)
                                    if (!attrs.isDirectory || attrs.isSymbolicLink) parentsValid = false
                                    parent = parent.parentFile
                                }
                                if (parentsValid && parent == root) checkFile("$id/$relative", file, required)
                                else "invalid"
                            }
                            states.firstOrNull { it != "ready" } ?: "ready"
                        }
                    }
                } catch (_: NoSuchFileException) {
                    invalidate(id)
                    "missing"
                } catch (_: IOException) {
                    invalidate(id)
                    "unreadable"
                } catch (_: java.io.UncheckedIOException) {
                    invalidate(id)
                    "unreadable"
                } catch (_: SecurityException) {
                    invalidate(id)
                    "unreadable"
                }
            } }
        } finally { lock.unlock() }
    }

    @Synchronized fun invalidate(id: String) {
        checked.keys.removeAll { it == id || it.startsWith("$id/") }
    }

    internal companion object {
    /** Recovery validates either target or backup independently before a rename.
     * This extra hash pass runs only when an interrupted backup exists. */
    fun validArchive(root: File, profile: LocalModelProfile): Boolean = try {
        Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) && profile.archiveFiles.isNotEmpty() &&
            expectedTree(root, profile) && profile.archiveFiles.all { (relative, expected) ->
                val file = File(root, relative)
                val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                attrs.isRegularFile && attrs.size() == expected.size && file.inputStream().use { source ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    var count = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > expected.size) return@use false
                        digest.update(buffer, 0, n)
                    }
                    count == expected.size && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha
                }
            }
    } catch (_: IOException) { false }
      catch (_: java.io.UncheckedIOException) { false }
      catch (_: SecurityException) { false }

    /** Vosk may prefer an optional HCLG.fst over the pinned split graph. Reject
     * all unexpected files and links so it can only load verified components. */
    private fun expectedTree(root: File, profile: LocalModelProfile): Boolean {
        val directories = mutableSetOf<String>()
        profile.archiveFiles.keys.forEach { relative ->
            if (relative.isEmpty() || '\\' in relative || ':' in relative || '\u0000' in relative ||
                relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
            var parent = relative.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                directories.add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return Files.walk(root.toPath()).use { paths ->
            val entries = paths.iterator()
            while (entries.hasNext()) {
                val path = entries.next()
                if (path == root.toPath()) continue
                val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || (!attrs.isDirectory && !attrs.isRegularFile)) return@use false
                if (attrs.isDirectory && relative !in directories) return@use false
                if (attrs.isRegularFile && relative !in profile.archiveFiles) return@use false
            }
            true
        }
    }
    }

    private fun checkFile(id: String, file: File, expected: LocalModelFile): String {
        val before = fingerprint(file)
        val cached = checked[id]
        if (cached?.fingerprint == before) return cached.state
        val valid = before.regular && before.size == expected.size && file.inputStream().use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var size = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                size += count
                if (size > expected.size) return@use false
                digest.update(buffer, 0, count)
            }
            size == expected.size && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha
        }
        val state = if (valid) "ready" else "invalid"
        // Changes made outside the store while hashing are checked again.
        return if (before == fingerprint(file)) {
            checked[id] = Checked(before, state)
            state
        } else {
            checked.remove(id)
            "checking"
        }
    }

    private fun fingerprint(file: File): Fingerprint {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return Fingerprint(attrs.size(), attrs.lastModifiedTime().toString(), attrs.fileKey()?.toString(), attrs.isRegularFile)
    }
}
