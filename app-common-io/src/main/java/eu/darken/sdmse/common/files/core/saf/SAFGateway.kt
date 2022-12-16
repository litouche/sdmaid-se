package eu.darken.sdmse.common.files.core.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.sdmse.common.coroutine.AppScope
import eu.darken.sdmse.common.coroutine.DispatcherProvider
import eu.darken.sdmse.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.sdmse.common.debug.logging.Logging.Priority.WARN
import eu.darken.sdmse.common.debug.logging.asLog
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.files.core.*
import eu.darken.sdmse.common.sharedresource.SharedResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.*
import timber.log.Timber
import java.io.IOException
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SAFGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
) : APathGateway<SAFPath, SAFPathLookup> {

    override val sharedResource = SharedResource.createKeepAlive(
        "${TAG}:SharedResource",
        appScope + dispatcherProvider.IO
    )

    private suspend fun <T> runIO(
        block: suspend CoroutineScope.() -> T
    ): T = withContext(dispatcherProvider.IO) { block() }

    /**
     * SAFPaths have a normalized treeUri, e.g.:
     * content://com.android.externalstorage.documents/tree/primary
     * SAFDocFiles need require a treeUri that actually gives us access though, i.e. the closet SAF permission we have.
     */
    private fun findDocFile(file: SAFPath): SAFDocFile {
        val match = file.matchPermission(contentResolver.persistedUriPermissions)

        if (match == null) {
            log(TAG, VERBOSE) { "No UriPermission match for $file" }
            throw MissingUriPermissionException(file)
        }

        val targetTreeUri = SAFDocFile.buildTreeUri(
            match.permission.uri,
            match.missingSegments,
        )
        return SAFDocFile.fromTreeUri(context, contentResolver, targetTreeUri)
    }

    @Throws(IOException::class)
    override suspend fun createFile(path: SAFPath): Boolean = runIO {
        val docFile = findDocFile(path)
        if (docFile.exists) {
            if (docFile.isFile) return@runIO false
            else throw WriteException(path, message = "Path exists, but is not a file.")
        }
        return@runIO try {
            createDocumentFile(FILE_TYPE_DEFAULT, path.treeRoot, path.segments)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "createFile(path=%s) failed", path)
            throw WriteException(path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun createDir(path: SAFPath): Boolean = runIO {
        val docFile = findDocFile(path)
        if (docFile.exists) {
            if (docFile.isDirectory) return@runIO false
            else throw WriteException(path, message = "Path exists, but is not a directory.")
        }
        return@runIO try {
            createDocumentFile(DIR_TYPE, path.treeRoot, path.segments)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "createDir(path=%s) failed", path)
            throw WriteException(path, cause = e)
        }
    }

    private fun createDocumentFile(mimeType: String, treeUri: Uri, segments: List<String>): SAFDocFile {
        val root = SAFDocFile.fromTreeUri(context, contentResolver, treeUri)

        var currentRoot: SAFDocFile = root
        for ((index, segName) in segments.withIndex()) {
            if (index < segments.size - 1) {
                val curFile = currentRoot.findFile(segName)
                currentRoot = if (curFile == null) {
                    Timber.tag(TAG).d("$segName doesn't exist in ${currentRoot.uri}, creating.")
                    currentRoot.createDirectory(segName)
                } else {
                    Timber.tag(TAG).d("$segName exists in ${currentRoot.uri}.")
                    curFile
                }
            } else {
                val existing = currentRoot.findFile(segName)
                check(existing == null) { "File already exists: ${existing?.uri}" }

                currentRoot = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    currentRoot.createDirectory(segName)
                } else {
                    currentRoot.createFile(mimeType, segName)
                }
                require(segName == currentRoot.name) { "Unexpected name change: Wanted $segName, but got ${currentRoot.name}" }
            }
        }
        Timber.tag(TAG)
            .v("createDocumentFile(mimeType=$mimeType, treeUri=$treeUri, crumbs=${segments.toList()}): ${currentRoot.uri}")
        return currentRoot
    }

    @Throws(IOException::class)
    override suspend fun listFiles(path: SAFPath): List<SAFPath> = runIO {
        try {
            findDocFile(path)
                .listFiles()
                .map {
                    val name = it.name ?: it.uri.pathSegments.last().split('/').last()
                    path.child(name)
                }
        } catch (e: Exception) {
            Timber.tag(TAG).w("listFiles(%s) failed.", path)
            throw ReadException(path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun exists(path: SAFPath): Boolean = runIO {
        try {
            findDocFile(path).exists
        } catch (e: Exception) {
            throw ReadException(path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun delete(path: SAFPath): Boolean = runIO {
        try {
            findDocFile(path).delete()
        } catch (e: Exception) {
            throw WriteException(path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun canWrite(path: SAFPath): Boolean = runIO {
        try {
            findDocFile(path).writable
        } catch (e: MissingUriPermissionException) {
            false
        } catch (e: Exception) {
            throw ReadException(path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun canRead(path: SAFPath): Boolean = runIO {
        try {
            findDocFile(path).readable
        } catch (e: MissingUriPermissionException) {
            false
        } catch (e: Exception) {
            throw ReadException(path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun lookup(path: SAFPath): SAFPathLookup = runIO {
        try {
            val docFile = findDocFile(path)
            if (!docFile.readable) throw IllegalStateException("readable=false")

            val fileType: FileType = when {
                docFile.isDirectory -> FileType.DIRECTORY
                else -> FileType.FILE
            }
            val fstat = docFile.fstat()

            SAFPathLookup(
                lookedUp = path,
                fileType = fileType,
                modifiedAt = docFile.lastModified,
                ownership = fstat?.let { Ownership(it.st_uid.toLong(), it.st_gid.toLong()) },
                permissions = fstat?.let { Permissions(it.st_mode) },
                size = docFile.length,
                target = null
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w("lookup(%s) failed.", path)
            throw ReadException(path, cause = e)
        }
    }

    override suspend fun lookupFiles(path: SAFPath): List<SAFPathLookup> = runIO {
        try {
            findDocFile(path)
                .listFiles()
                .map {
                    val name = it.name ?: it.uri.pathSegments.last().split('/').last()
                    path.child(name)
                }
                .map { lookup(it) }
        } catch (e: Exception) {
            log(TAG, WARN) { "lookupFiles($path) failed." }
            throw ReadException(path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun read(path: SAFPath): Source = runIO {
        try {
            val docFile = findDocFile(path)
            if (!docFile.readable) throw IllegalStateException("readable=false")

            val pfd = docFile.openPFD(contentResolver, FileMode.READ)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).source().buffer()
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to read from $path: ${e.asLog()}" }
            throw  ReadException(path = path, cause = e)
        }
    }

    @Throws(IOException::class)
    override suspend fun write(path: SAFPath): Sink = runIO {
        try {
            val docFile = findDocFile(path)
            if (!docFile.writable) throw IllegalStateException("writable=false")

            val pfd = docFile.openPFD(contentResolver, FileMode.WRITE)
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).sink().buffer()
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to write to $path: ${e.asLog()}" }
            throw  WriteException(path = path, cause = e)
        }
    }

    override suspend fun setModifiedAt(path: SAFPath, modifiedAt: Date): Boolean = runIO {
        try {
            val docFile = findDocFile(path)

            docFile.setLastModified(modifiedAt)
        } catch (e: Exception) {
            throw WriteException(path, cause = e)
        }
    }

    override suspend fun setPermissions(path: SAFPath, permissions: Permissions): Boolean = runIO {
        try {
            val docFile = findDocFile(path)

            docFile.setPermissions(permissions)
        } catch (e: Exception) {
            throw WriteException(path, cause = e)
        }
    }

    override suspend fun setOwnership(path: SAFPath, ownership: Ownership): Boolean = runIO {
        try {
            val docFile = findDocFile(path)

            docFile.setOwnership(ownership)
        } catch (e: Exception) {
            throw WriteException(path, cause = e)
        }
    }

    override suspend fun createSymlink(linkPath: SAFPath, targetPath: SAFPath): Boolean {
        throw UnsupportedOperationException("SAF doesn't support symlinks. createSymlink(linkPath=$linkPath, targetPath=$targetPath)")
    }

    companion object {
        val TAG = logTag("Gateway", "SAF")

        const val RW_FLAGSINT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val DIR_TYPE: String = DocumentsContract.Document.MIME_TYPE_DIR
        private const val FILE_TYPE_DEFAULT: String = "application/octet-stream"

        fun isTreeUri(uri: Uri): Boolean {
            val paths = uri.pathSegments
            return paths.size >= 2 && "tree" == paths[0]
        }
    }
}