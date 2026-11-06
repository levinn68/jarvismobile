package com.jarvis.mobile.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * SAF-only file access. The user must explicitly select a directory in Android's system picker.
 * No unrestricted filesystem traversal or hidden access is attempted.
 */
class FileController(private val context: Context) {
    companion object {
        const val PREFS = "jarvis_device_secure"
        const val TREE_URI = "selected_tree_uri"
        const val MAX_READ_BYTES = 60 * 1024 // 60 KB content cap so JSON ACK envelope stays <= 64 KB
        private val ALLOWED_TEXT_MIMES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-yaml",
            "application/toml",
            "application/sql"
        )
        private val ALLOWED_TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "xml", "yaml", "yml",
            "log", "ini", "cfg", "conf", "toml", "properties", "html", "htm",
            "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java",
            "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "cs", "sql", "sh", "gradle"
        )
    }

    fun hasSelectedFolder(): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE_URI, null) != null
    fun saveSelectedFolder(uri: Uri) {
        // JARVIS currently supports read-only file commands; never retain write access unnecessarily.
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TREE_URI, uri.toString()).apply()
    }

    fun execute(action: String, params: JSONObject): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE_URI, null)
            ?: return JSONObject().put("ok", false).put("requiresUserSelection", true)
                .put("error", "Select a folder using Android's system file picker first")
        val tree = Uri.parse(raw)
        return try {
            when (action) {
                "files.list" -> list(tree, "")
                "files.search" -> list(tree, params.optString("query"))
                "files.read" -> readDocument(tree, params.optString("documentId"))
                else -> JSONObject().put("ok", false).put("error", "Unsupported file action")
            }
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "SAF access failed") }
    }

    private fun readDocument(tree: Uri, documentId: String): JSONObject {
        if (documentId.isBlank() || documentId.length > 1024)
            return JSONObject().put("ok", false).put("error", "A valid SAF documentId is required")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = documentId.substringAfterLast('.', "").lowercase()
        val isAllowedText = mime.startsWith("text/") ||
            mime in ALLOWED_TEXT_MIMES ||
            ((mime.isBlank() || mime == "application/octet-stream") && ext in ALLOWED_TEXT_EXTENSIONS)
        if (!isAllowedText)
            return JSONObject().put("ok", false).put("error", "Only text documents can be read by this connector")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_READ_BYTES) throw IllegalStateException("File exceeds 64 KB read limit (max 60 KB content)")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Unable to open selected document")
        if (bytes.any { it == 0.toByte() }) {
            return JSONObject().put("ok", false).put("error", "Binary files are not permitted; text documents only")
        }
        return JSONObject().put("ok", true).put("mimeType", mime.ifBlank { "text/plain" })
            .put("content", String(bytes, Charsets.UTF_8)).put("bytes", bytes.size)
    }

    fun folderPickerIntent(): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    private fun list(tree: Uri, query: String): JSONObject {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val results = JSONArray()
        context.contentResolver.query(children, projection, null, null,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext() && results.length() < 200) {
                val name = cursor.getString(nameCol) ?: continue
                if (query.isNotBlank() && !name.contains(query, ignoreCase = true)) continue
                results.put(JSONObject().put("name", name)
                    .put("documentId", cursor.getString(idCol))
                    .put("mimeType", cursor.getString(mimeCol))
                    .put("size", if (cursor.isNull(sizeCol)) JSONObject.NULL else cursor.getLong(sizeCol))
                    .put("lastModified", if (cursor.isNull(modifiedCol)) JSONObject.NULL else cursor.getLong(modifiedCol)))
            }
        }
        return JSONObject().put("ok", true).put("items", results).put("count", results.length())
    }
}