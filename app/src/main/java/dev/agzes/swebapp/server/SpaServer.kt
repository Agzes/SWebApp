package dev.agzes.swebapp.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

fun String.escapeHtml(): String {
    return this.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

object HotReloadState {
    val forceReload = MutableStateFlow(0)
}

class SpaServer(private val appContext: Context) {
    private var server: ApplicationEngine? = null
    private var hotReloadJob: kotlinx.coroutines.Job? = null
    private var serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val MAX_BUFFERED_SIZE = 8L * 1024 * 1024
    }

    fun start(
        port: Int,
        localhostOnly: Boolean,
        spaMode: Boolean,
        hotReload: Boolean,
        folderUri: String,
        themeMode: Boolean,
        restrictWebFeatures: Boolean
    ) {
        val host = if (localhostOnly) "127.0.0.1" else "0.0.0.0"
        if (!serverScope.isActive) {
            serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        server = embeddedServer(Netty, port = port, host = host) {
            install(CORS) {
                allowHeader(io.ktor.http.HttpHeaders.ContentType)
                allowHost("localhost:$port")
                allowHost("127.0.0.1:$port")
            }

            routing {
                if (hotReload) {
                    get("/__hot_reload") {
                        try {
                            val currentVersion = HotReloadState.forceReload.value
                            val result = withTimeoutOrNull(30_000L) {
                                HotReloadState.forceReload.first { it != currentVersion }
                            }
                            if (result != null) call.respondText("reload")
                            else call.respondText("timeout")
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError)
                        }
                    }
                }

                get("/{...}") {
                    val path = call.request.path().removePrefix("/")
                    val requestNonce = if (hotReload) java.util.UUID.randomUUID().toString().replace("-", "") else ""
                    call.response.headers.append("X-Content-Type-Options", "nosniff")
                    call.response.headers.append("X-Frame-Options", "SAMEORIGIN")
                    call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin")
                    val scriptSrc = if (hotReload) "'self' 'unsafe-inline' 'nonce-$requestNonce'"
                    else "'self' 'unsafe-inline'"
                    call.response.headers.append(
                        "Content-Security-Policy",
                        "default-src 'self'; script-src $scriptSrc; style-src 'self' 'unsafe-inline'; connect-src 'self'"
                    )
                    if (restrictWebFeatures) {
                        call.response.headers.append(
                            "Permissions-Policy",
                            "camera=(), microphone=(), geolocation=()"
                        )
                    }
                    val documentTree = DocumentFile.fromTreeUri(appContext, Uri.parse(folderUri))

                    if (documentTree == null || !documentTree.canRead()) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            "Failed to access folder or missing permissions"
                        )
                        return@get
                    }

                    var targetFile = findFile(documentTree, path)
                    var isDirectory = false

                    if (targetFile != null && targetFile.isDirectory) {
                        isDirectory = true
                        val indexFile = findFile(targetFile, "index.html")
                        if (indexFile != null && indexFile.isFile) {
                            targetFile = indexFile
                            isDirectory = false
                        }
                    }

                    if (targetFile == null && !path.endsWith(".html")) {
                        val htmlFile = findFile(documentTree, "$path.html")
                        if (htmlFile != null && htmlFile.isFile) {
                            targetFile = htmlFile
                        }
                    }

                    if (spaMode && (targetFile == null || !targetFile.isFile)) {
                        val rootIndex = findFile(documentTree, "index.html")
                        if (rootIndex != null && rootIndex.isFile) {
                            targetFile = rootIndex
                            isDirectory = false
                        }
                    }

                    val dirFile = targetFile
                    if (dirFile != null && isDirectory) {
                        ServerLogger.log("GET /$path -> Directory Listing")
                        val html = withContext(Dispatchers.IO) { generateDirectoryHtml(path, dirFile, themeMode) }
                        call.respondText(html, ContentType.Text.Html)
                        return@get
                    }

                    if (targetFile != null && targetFile.isFile) {
                        ServerLogger.log("GET /$path -> OK")
                        val extension = targetFile.name?.substringAfterLast('.', "") ?: ""
                        val contentType = when (extension.lowercase()) {
                            "html", "htm" -> ContentType.Text.Html
                            "css" -> ContentType.Text.CSS
                            "js" -> ContentType.Application.JavaScript
                            "json" -> ContentType.Application.Json
                            "png" -> ContentType.Image.PNG
                            "jpg", "jpeg" -> ContentType.Image.JPEG
                            "svg" -> ContentType.Image.SVG
                            "wasm" -> ContentType.Application.Wasm
                            "ico" -> ContentType("image", "x-icon")
                            else -> ContentType.Application.OctetStream
                        }

                        val isHtml = extension.equals("html", true) || extension.equals("htm", true)
                        val fileSize = targetFile.length()

                        if (hotReload && isHtml) {
                            val bytes = withContext(Dispatchers.IO) {
                                try {
                                    appContext.contentResolver.openInputStream(targetFile!!.uri)?.use { stream ->
                                        stream.readBytes()
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (bytes != null) {
                                val script = "<script nonce=\"$requestNonce\">\n" +
                                        "    function pollHotReload() {\n" +
                                        "        fetch('/__hot_reload')\n" +
                                        "        .then(r=>r.text())\n" +
                                        "        .then(t=>{if(t==='reload')location.reload();else pollHotReload();})\n" +
                                        "        .catch(()=>setTimeout(pollHotReload, 2000));\n" +
                                        "    }\n" +
                                        "    pollHotReload();\n" +
                                        "</script>"
                                val htmlString = String(bytes)
                                val finalHtml = if (htmlString.contains("</body>", ignoreCase = true)) {
                                    htmlString.replace("</body>", "$script\n</body>", ignoreCase = true)
                                } else {
                                    htmlString + script
                                }
                                call.respondText(finalHtml, ContentType.Text.Html)
                            } else {
                                ServerLogger.log("GET /$path -> 500 Read Error")
                                call.respond(HttpStatusCode.InternalServerError, "Failed to read file")
                            }
                        } else if (fileSize > MAX_BUFFERED_SIZE) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val inputStream = appContext.contentResolver.openInputStream(targetFile!!.uri)
                                    if (inputStream != null) {
                                        call.respondOutputStream(contentType) {
                                            inputStream.use { it.copyTo(this) }
                                        }
                                    } else {
                                        ServerLogger.log("GET /$path -> 500 Read Error")
                                        call.respond(HttpStatusCode.InternalServerError, "Failed to read file")
                                    }
                                } catch (e: Exception) {
                                    ServerLogger.log("GET /$path -> 500 Read Error")
                                    call.respond(HttpStatusCode.InternalServerError, "Failed to read file")
                                }
                            }
                        } else {
                            val bytes = withContext(Dispatchers.IO) {
                                try {
                                    appContext.contentResolver.openInputStream(targetFile!!.uri)?.use { stream ->
                                        stream.readBytes()
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (bytes != null) {
                                call.respondBytes(bytes, contentType)
                            } else {
                                ServerLogger.log("GET /$path -> 500 Read Error")
                                call.respond(HttpStatusCode.InternalServerError, "Failed to read file")
                            }
                        }
                    } else {
                        ServerLogger.log("GET /$path -> 404 Not Found")
                        call.response.headers.append("Cache-Control", "no-store")
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)

        if (hotReload) {
            hotReloadJob = serverScope.launch {
                val documentTree = DocumentFile.fromTreeUri(appContext, Uri.parse(folderUri))
                var lastModified = documentTree?.findFile("index.html")?.lastModified() ?: 0L
                while (server != null) {
                    val currentModified = documentTree?.findFile("index.html")?.lastModified() ?: 0L
                    if (currentModified != lastModified) {
                        lastModified = currentModified
                        HotReloadState.forceReload.value++
                    }
                    delay(2000)
                }
            }
        }
    }

    fun stop() {
        hotReloadJob?.cancel()
        hotReloadJob = null
        server?.stop(1000, 2000)
        server = null
        serverScope.cancel()
        serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private suspend fun findFile(root: DocumentFile, path: String): DocumentFile? = withContext(Dispatchers.IO) {
        if (path.isEmpty() || path == "/") return@withContext root

        val decodedPath = try {
            java.net.URLDecoder.decode(path, "UTF-8")
        } catch (e: Exception) {
            return@withContext null
        }
        if (decodedPath.contains("..") || decodedPath.contains("\\") || decodedPath.contains("\u0000")) {
            return@withContext null
        }

        var current = root
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." && it != ".." }
        for (part in parts) {
            if (part.contains("..") || part.contains("\\") || part.contains("\u0000")) {
                return@withContext null
            }
            current = current.findFile(part) ?: return@withContext null
        }
        return@withContext current
    }

    private fun generateDirectoryHtml(currentPath: String, dir: DocumentFile, isDarkTheme: Boolean): String {
        val folderIcon =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" height=\"24\" viewBox=\"0 -960 960 960\" width=\"24\" fill=\"currentColor\"><path d=\"M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h240l80 80h320q33 0 56.5 23.5T880-640v400q0-33-23.5 56.5T800-160H160Zm0-80h640v-400H367l-80-80H160v480Zm0 0v-480 480Z\"/></svg>"
        val fileIcon =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" height=\"24\" viewBox=\"0 -960 960 960\" width=\"24\" fill=\"currentColor\"><path d=\"M240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h320l240 240v480q0 33-23.5 56.5T720-80H240Zm280-520v-200H240v640h480v-440H520ZM240-800v640-640Z\"/></svg>"
        val backIcon =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" height=\"24\" viewBox=\"0 -960 960 960\" width=\"24\" fill=\"currentColor\"><path d=\"m313-440 224 224-57 56-320-320 320-320 57 56-224 224h487v80H313Z\"/></svg>"

        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html lang=\"en\">")
        sb.appendLine("<head>")
        sb.appendLine("<meta charset=\"UTF-8\">")
        sb.appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        sb.appendLine("<title>Index of /${currentPath.ifEmpty { "Root" }.escapeHtml()}</title>")
        sb.appendLine("<style>")
        sb.appendLine(":root {")
        sb.appendLine("  --md-sys-color-background: ${if (isDarkTheme) "#1A1C1E" else "#FDFBFF"};")
        sb.appendLine("  --md-sys-color-on-background: ${if (isDarkTheme) "#E2E2E6" else "#1A1C1E"};")
        sb.appendLine("  --md-sys-color-surface: ${if (isDarkTheme) "#1A1C1E" else "#FDFBFF"};")
        sb.appendLine("  --md-sys-color-surface-container: ${if (isDarkTheme) "#1E2022" else "#F1F0F4"};")
        sb.appendLine("  --md-sys-color-on-surface: ${if (isDarkTheme) "#E2E2E6" else "#1A1C1E"};")
        sb.appendLine("  --md-sys-color-on-surface-variant: ${if (isDarkTheme) "#C3C7CF" else "#43474E"};")
        sb.appendLine("  --md-sys-color-primary: ${if (isDarkTheme) "#9ECAFF" else "#0061A4"};")
        sb.appendLine("  --md-sys-color-secondary-container: ${if (isDarkTheme) "#00497D" else "#D1E4FF"};")
        sb.appendLine("  --md-sys-color-on-secondary-container: ${if (isDarkTheme) "#D1E4FF" else "#001D36"};")
        sb.appendLine("}")
        sb.appendLine("body {")
        sb.appendLine("  font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;")
        sb.appendLine("  background-color: var(--md-sys-color-background);")
        sb.appendLine("  color: var(--md-sys-color-on-background);")
        sb.appendLine("  margin: 0; padding: 0;")
        sb.appendLine("  display: flex; justify-content: center;")
        sb.appendLine("}")
        sb.appendLine(".container {")
        sb.appendLine("  width: 100%; max-width: 800px; padding: 24px 16px;")
        sb.appendLine("}")
        sb.appendLine(".header { margin-bottom: 24px; padding: 0 16px; }")
        sb.appendLine(".title {")
        sb.appendLine("  font-size: 32px; font-weight: 400; line-height: 40px;")
        sb.appendLine("  margin: 0; word-break: break-all;")
        sb.appendLine("}")
        sb.appendLine(".path { color: var(--md-sys-color-primary); }")
        sb.appendLine(".list {")
        sb.appendLine("  background-color: var(--md-sys-color-surface-container);")
        sb.appendLine("  border-radius: 16px; overflow: hidden;")
        sb.appendLine("  display: flex; flex-direction: column;")
        sb.appendLine("}")
        sb.appendLine(".list-item {")
        sb.appendLine("  display: flex; align-items: center; padding: 16px 24px;")
        sb.appendLine("  text-decoration: none; color: var(--md-sys-color-on-surface);")
        sb.appendLine("  transition: all 0.2s ease;")
        sb.appendLine("}")
        sb.appendLine(".list-item.interactive:hover, .list-item.interactive:focus {")
        sb.appendLine("  background-color: var(--md-sys-color-secondary-container);")
        sb.appendLine("  color: var(--md-sys-color-on-secondary-container);")
        sb.appendLine("  outline: none;")
        sb.appendLine("}")
        sb.appendLine(".list-item.interactive:active { opacity: 0.8; }")
        sb.appendLine(".icon {")
        sb.appendLine("  display: inline-flex; justify-content: center; align-items: center;")
        sb.appendLine("  width: 40px; height: 40px; border-radius: 50%;")
        sb.appendLine("  background-color: transparent;")
        sb.appendLine("  margin-right: 16px; flex-shrink: 0;")
        sb.appendLine("  color: var(--md-sys-color-primary);")
        sb.appendLine("}")
        sb.appendLine(".list-item.interactive:hover .icon, .list-item.interactive:focus .icon {")
        sb.appendLine("  color: var(--md-sys-color-on-secondary-container);")
        sb.appendLine("}")
        sb.appendLine(".name {")
        sb.appendLine("  font-size: 16px; font-weight: 500; line-height: 24px;")
        sb.appendLine("  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;")
        sb.appendLine("}")
        sb.appendLine(".divider {")
        sb.appendLine("  height: 1px; background-color: var(--md-sys-color-on-surface-variant);")
        sb.appendLine("  opacity: 0.2; margin: 0;")
        sb.appendLine("}")
        sb.appendLine(".empty { color: var(--md-sys-color-on-surface-variant); padding: 24px; text-align: center; }")
        sb.appendLine("</style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("<div class=\"container\">")
        sb.appendLine("  <div class=\"header\">")
        sb.appendLine(
            "    <h1 class=\"title\">Index of <span class=\"path\">/${
                currentPath.ifEmpty { "Root" }.escapeHtml()
            }</span></h1>"
        )
        sb.appendLine("  </div>")
        sb.appendLine("  <div class=\"list\">")

        var first = true

        if (currentPath.isNotEmpty()) {
            val upPath = if (currentPath.contains("/")) currentPath.substringBeforeLast("/") else ""
            sb.appendLine("    <a class=\"list-item interactive\" href=\"/$upPath\">")
            sb.appendLine("      <div class=\"icon\">$backIcon</div>")
            sb.appendLine("      <div class=\"name\">..</div>")
            sb.appendLine("    </a>")
            first = false
        }

        val sortedFiles = dir.listFiles().take(1000).sortedBy { !it.isDirectory }
        sortedFiles.forEach { file ->
            val name = file.name ?: return@forEach
            if (!first) sb.appendLine("    <div class=\"divider\"></div>")
            first = false

            val linkPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
            val icon = if (file.isDirectory) folderIcon else fileIcon

            sb.appendLine("    <a class=\"list-item interactive\" href=\"/${linkPath.escapeHtml()}\">")
            sb.appendLine("      <div class=\"icon\">$icon</div>")
            sb.appendLine("      <div class=\"name\">${name.escapeHtml()}</div>")
            sb.appendLine("    </a>")
        }

        if (first) {
            sb.appendLine("    <div class=\"list-item empty\">")
            sb.appendLine("      <div class=\"name\">Folder is empty</div>")
            sb.appendLine("    </div>")
        }

        sb.appendLine("  </div>")
        sb.appendLine("</div>")
        sb.appendLine("</body>")
        sb.appendLine("</html>")
        return sb.toString()
    }
}
