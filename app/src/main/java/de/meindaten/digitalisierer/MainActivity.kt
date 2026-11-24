package de.meindaten.digitalisierer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.MailTo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import org.json.JSONArray
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : FragmentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        if (res.resultCode != RESULT_OK) { cb.onReceiveValue(null); return@registerForActivityResult }
        val uris = mutableListOf<Uri>()
        res.data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        if (uris.isEmpty()) res.data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) {
            val f = cameraFile
            val u = cameraUri
            if (f != null && u != null && f.exists() && f.length() > 0) uris.add(u)
        }
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private var scanId: String? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val id = scanId ?: return@registerForActivityResult
        scanId = null
        if (res.resultCode != RESULT_OK) { scanDone(id, "CANCEL"); return@registerForActivityResult }
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages.orEmpty()
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                val arr = JSONArray()
                for (p in pages) runCatching {
                    contentResolver.openInputStream(p.imageUri)?.use { arr.put(Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
                }
                arr.toString()
            }
            scanDone(id, json)
        }
    }

    private fun scanDone(id: String, r: String) = runOnUiThread {
        web.evaluateJavascript("window.__scanDone(${JSONObject.quote(id)},${JSONObject.quote(r)})", null)
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keine Bildschirmfotos und keine Vorschau in der App-Übersicht (Datenschutz)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                openExternal(u.toString())
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return openChooser(params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.appBack&&window.appBack())?'1':'0'") { r ->
                    if (r?.contains("1") != true) finish()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        Updater.schedule(this)
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }

    private fun openChooser(multiple: Boolean): Boolean {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        val extras = mutableListOf<Intent>()
        try {
            val dir = File(cacheDir, "scans").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name.startsWith("foto_")) it.delete() }
            val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            cameraFile = f
            cameraUri = uri
            extras.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("foto", uri)
            })
        } catch (_: Exception) { cameraFile = null; cameraUri = null }
        val chooser = Intent.createChooser(get, "Brief fotografieren oder Datei wählen").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        }
        return try { picker.launch(chooser); true } catch (_: Exception) {
            fileCallback?.onReceiveValue(null); fileCallback = null; false
        }
    }

    private fun openExternal(url: String) {
        try {
            if (url.startsWith("mailto:", true)) {
                val m = MailTo.parse(url)
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                    m.to?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_EMAIL, it.split(',', ';').map(String::trim).toTypedArray()) }
                    m.cc?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_CC, it.split(',', ';').map(String::trim).toTypedArray()) }
                    m.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    m.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }
                startActivity(Intent.createChooser(i, "E-Mail senden mit"))
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } catch (_: ActivityNotFoundException) {
            toast("Keine passende App gefunden")
        } catch (_: Exception) {
            toast("Link konnte nicht geöffnet werden")
        }
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    private fun safeName(n: String) = n.replace(Regex("[^\\w.\\- äöüÄÖÜß]"), "_").take(120).ifBlank { "datei" }

    inner class Bridge {
        @JavascriptInterface
        fun version(): String = Updater.installedName(this@MainActivity)

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Text", text))
            }
        }

        /** Speichert eine Datei unter Downloads/Mein Daten-Digitalisierer. */
        @JavascriptInterface
        fun saveFile(name: String, mime: String, b64: String): String = try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName(name))
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mein Daten-Digitalisierer")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: error("Speicherort nicht verfügbar")
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            "ok"
        } catch (e: Exception) {
            "Speichern fehlgeschlagen: ${e.message}"
        }

        /** Öffnet das Teilen-Menü (z. B. Google Drive, E-Mail an die Kanzlei). */
        @JavascriptInterface
        fun shareFile(name: String, mime: String, b64: String) {
            try {
                val dir = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, safeName(name))
                f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(i, "Teilen mit")) }
            } catch (e: Exception) {
                toast("Teilen fehlgeschlagen")
            }
        }

        /** Texterkennung auf dem Gerät; Ergebnis geht an window.__ocrDone(id, text). */
        @JavascriptInterface
        fun ocr(id: String, b64: String, mime: String) {
            lifecycleScope.launch {
                val text = withContext(Dispatchers.Default) {
                    try { Ocr.run(this@MainActivity, Base64.decode(b64, Base64.DEFAULT), mime) }
                    catch (e: Exception) { "FEHLER: ${e.message}" }
                }
                web.evaluateJavascript(
                    "window.__ocrDone(${JSONObject.quote(id)},${JSONObject.quote(text)})", null
                )
            }
        }

        // ----- Mehrseitiger Scan -----
        @JavascriptInterface
        fun scanAvailable(): Boolean =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this@MainActivity) == ConnectionResult.SUCCESS

        @JavascriptInterface
        fun scan(id: String) {
            runOnUiThread {
                val opts = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(30)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                GmsDocumentScanning.getClient(opts).getStartScanIntent(this@MainActivity)
                    .addOnSuccessListener { sender ->
                        scanId = id
                        scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                    .addOnFailureListener { scanDone(id, "[]") }
            }
        }

        // ----- Fingerabdruck -----
        @JavascriptInterface
        fun bioAvailable(): Boolean = Bio.available(this@MainActivity)

        @JavascriptInterface
        fun bioEnabled(): Boolean = Bio.enabled(this@MainActivity) && Bio.available(this@MainActivity)

        @JavascriptInterface
        fun bioDisable() = Bio.disable(this@MainActivity)

        @JavascriptInterface
        fun bioEnable(id: String, rawB64: String) {
            val raw = Base64.decode(rawB64, Base64.DEFAULT)
            runOnUiThread { Bio.enable(this@MainActivity, raw) { r -> bioDone(id, r) } }
        }

        @JavascriptInterface
        fun bioUnlock(id: String) {
            runOnUiThread { Bio.unlock(this@MainActivity) { r -> bioDone(id, r) } }
        }

        private fun bioDone(id: String, r: String) = runOnUiThread {
            web.evaluateJavascript("window.__bioDone(${JSONObject.quote(id)},${JSONObject.quote(r)})", null)
        }

        @JavascriptInterface
        fun checkUpdate() {
            lifecycleScope.launch {
                when (val r = Updater.checkAndInstall(this@MainActivity)) {
                    is Updater.Result.UpToDate -> toast("Du hast schon die neueste Version")
                    is Updater.Result.Installing -> toast("Neue Version ${r.tag} wird installiert …")
                    is Updater.Result.Failed -> toast(r.message)
                }
            }
        }
    }
}
