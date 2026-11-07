package com.example.ytmp3

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var urlInput: EditText
    private lateinit var qualityGroup: RadioGroup
    private lateinit var downloadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var openButton: Button
    private lateinit var shareButton: Button
    private lateinit var updateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var versionText: TextView

    @Volatile private var ready = false
    @Volatile private var busy = false
    @Volatile private var processId: String? = null
    private var lastUri: Uri? = null
    private var autoStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        qualityGroup = findViewById(R.id.qualityGroup)
        downloadButton = findViewById(R.id.downloadButton)
        cancelButton = findViewById(R.id.cancelButton)
        openButton = findViewById(R.id.openButton)
        shareButton = findViewById(R.id.shareButton)
        updateButton = findViewById(R.id.updateButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        versionText = findViewById(R.id.versionText)

        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        downloadButton.setOnClickListener { startDownload() }
        cancelButton.setOnClickListener { cancelDownload() }
        openButton.setOnClickListener { openLast() }
        shareButton.setOnClickListener { shareLast() }
        updateButton.setOnClickListener { updateYtDlp(manual = true) }

        setBusy(true)
        handleShareIntent(intent)
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- setup -------------------------------------------------------------

    private fun initEngine() {
        worker.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                ui {
                    status("พร้อมใช้งาน")
                    showVersion()
                    setBusy(false)
                    if (autoStart) { autoStart = false; startDownload() }
                }
            } catch (e: Exception) {
                ui { status("เริ่มระบบไม่สำเร็จ: ${e.message}") }
                return@execute
            }
            // YouTube changes often; keep yt-dlp current automatically (once a day).
            val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
            val last = prefs.getLong("lastUpdate", 0L)
            if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) {
                updateYtDlp(manual = false)
            }
        }
    }

    private fun updateYtDlp(manual: Boolean) {
        if (!ready || (manual && busy)) return
        if (manual) {
            setBusy(true)
            status("กำลังอัปเดต yt-dlp…")
        }
        worker.execute {
            try {
                val result = YoutubeDL.getInstance()
                    .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._STABLE)
                getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                    .putLong("lastUpdate", System.currentTimeMillis()).apply()
                ui {
                    showVersion()
                    if (manual) {
                        status(
                            if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "เป็นเวอร์ชันล่าสุดแล้ว"
                            else "อัปเดตเรียบร้อย"
                        )
                    }
                }
            } catch (e: Exception) {
                if (manual) ui { status("อัปเดตไม่สำเร็จ: ${e.message}") }
            } finally {
                if (manual) ui { setBusy(false) }
            }
        }
    }

    private fun showVersion() {
        val v = YoutubeDL.getInstance().versionName(applicationContext)
            ?: YoutubeDL.getInstance().version(applicationContext)
        versionText.text = "yt-dlp: ${v ?: "-"}"
    }

    // ---- input -------------------------------------------------------------

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(text) ?: return
        urlInput.setText(url)
        if (ready && !busy) startDownload() else autoStart = true
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = text?.let { extractUrl(it) }
        if (url != null) urlInput.setText(url) else toast("ไม่พบลิงก์ในคลิปบอร์ด")
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value

    // ---- download ----------------------------------------------------------

    private fun startDownload() {
        if (!ready || busy) return
        val url = extractUrl(urlInput.text.toString().trim())
        if (url == null) {
            toast("กรุณาใส่ลิงก์ YouTube")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }

        val quality = when (qualityGroup.checkedRadioButtonId) {
            R.id.q320 -> "320K"
            R.id.q128 -> "128K"
            else -> "192K"
        }
        val id = UUID.randomUUID().toString()
        processId = id
        lastUri = null
        openButton.isEnabled = false
        shareButton.isEnabled = false
        setBusy(true)
        cancelButton.isEnabled = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        status("กำลังเชื่อมต่อ…")
        logText.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        worker.execute {
            val outDir = File(cacheDir, "download").apply { deleteRecursively(); mkdirs() }
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", quality)
                    addOption("--embed-metadata")
                    addOption("--no-mtime")
                    addOption("-o", "${outDir.absolutePath}/%(title).120B.%(ext)s")
                }
                val response = YoutubeDL.getInstance().execute(request, id) { progress, eta, line ->
                    ui { onProgress(progress, eta, line) }
                }
                val mp3 = outDir.listFiles()
                    ?.filter { it.extension.equals("mp3", ignoreCase = true) }
                    ?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException(response.err.ifBlank { "ไม่พบไฟล์ MP3" })

                ui { status("กำลังบันทึก…"); progressBar.isIndeterminate = true }
                val saved = saveToMusic(mp3)
                ui {
                    lastUri = saved
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    status("เสร็จแล้ว ✔ ${mp3.name}\nบันทึกที่ Music/$FOLDER")
                    openButton.isEnabled = saved != null
                    shareButton.isEnabled = saved != null
                }
            } catch (e: YoutubeDL.CanceledException) {
                ui { status("ยกเลิกแล้ว"); progressBar.visibility = View.GONE }
            } catch (e: Exception) {
                val msg = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e.toString()
                ui {
                    status("ผิดพลาด: $msg\nลองกด “อัปเดตตัวดาวน์โหลด” แล้วลองใหม่")
                    progressBar.visibility = View.GONE
                }
            } finally {
                outDir.deleteRecursively()
                processId = null
                ui {
                    setBusy(false)
                    cancelButton.isEnabled = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun onProgress(progress: Float, eta: Long, line: String) {
        if (progress >= 0f) {
            progressBar.isIndeterminate = false
            progressBar.progress = progress.toInt()
        }
        when {
            line.contains("[ExtractAudio]") -> {
                progressBar.isIndeterminate = true
                status("กำลังแปลงเป็น MP3…")
            }
            progress > 0f -> status(
                "กำลังดาวน์โหลด ${progress.toInt()}%" + if (eta > 0) " · เหลือ ${eta}s" else ""
            )
        }
        if (line.isNotBlank()) logText.text = line
    }

    private fun cancelDownload() {
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        cancelButton.isEnabled = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDownload()
        else toast("ต้องอนุญาตการเข้าถึงพื้นที่จัดเก็บเพื่อบันทึกไฟล์")
    }

    // ---- saving ------------------------------------------------------------

    /** Copies the MP3 into the shared Music/YTMP3 folder and returns a content URI for it. */
    private fun saveToMusic(file: File): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("บันทึกไฟล์ไม่ได้")
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        dir.mkdirs()
        var dest = File(dir, file.name)
        var n = 1
        while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($n).mp3").also { n++ }
        file.copyTo(dest)

        // Ask the media scanner for a content:// URI (file:// URIs can't be shared on API 24+).
        val lock = Object()
        var scanned: Uri? = null
        var done = false
        MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf("audio/mpeg")) { _, uri ->
            synchronized(lock) { scanned = uri; done = true; lock.notifyAll() }
        }
        synchronized(lock) { if (!done) lock.wait(5000) }
        return scanned
    }

    private fun openLast() {
        val uri = lastUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("ไม่พบแอปเล่นเพลง")
        }
    }

    private fun shareLast() {
        val uri = lastUri ?: return
        val intent = Intent(Intent.ACTION_SEND).setType("audio/mpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "แชร์ MP3"))
    }

    // ---- helpers -----------------------------------------------------------

    private fun setBusy(value: Boolean) {
        busy = value
        downloadButton.isEnabled = !value
        updateButton.isEnabled = !value
    }

    private fun status(text: String) {
        statusText.text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun ui(block: () -> Unit) {
        main.post { if (!isDestroyed) block() }
    }

    companion object {
        private const val REQ_STORAGE = 1
        private const val FOLDER = "YTMP3"
    }
}
