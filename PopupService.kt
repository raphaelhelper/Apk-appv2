package com.qui.wordpopup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private data class VocabWord(
    val word: String,
    val meaning: String,
    val example: String?,
    val exampleMeaning: String?
)

class PopupService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var popupView: View? = null
    private var popupWindowManager: WindowManager? = null
    
    @Volatile
    private var words: List<VocabWord> = emptyList()
    @Volatile
    private var isLoading = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        
        // Đọc 10.000 từ vựng ở luồng ngầm ngay khi khởi tạo để tránh đơ app
        loadWordsInBackground()
        
        handler.post(showTask)
    }

    private fun loadWordsInBackground() {
        if (isLoading) return
        isLoading = true
        thread {
            words = loadWordsFromSelectedFolder()
            isLoading = false
        }
    }

    private val showTask = object : Runnable {
        override fun run() {
            if (Settings.canDrawOverlays(this@PopupService)) {
                if (popupView == null) showNextWord()
            }
            val minutes = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getLong("interval_min", 10L)
                .coerceIn(1, 1440)
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(minutes))
        }
    }

    private fun showNextWord() {
        if (words.isEmpty()) {
            // Nếu dữ liệu chưa đọc xong, thử kích hoạt đọc lại ở luồng ngầm
            if (!isLoading) loadWordsInBackground()
            return
        }

        // Lấy ngẫu nhiên 1 từ vựng từ danh sách
        val item = words.randomOrNull() ?: return

        val view = LayoutInflater.from(this).inflate(R.layout.popup_word, null)
        val title = view.findViewById<TextView>(R.id.wordText)
        val sub = view.findViewById<TextView>(R.id.meaningText)
        val example = view.findViewById<TextView>(R.id.exampleText)
        val exampleButton = view.findViewById<TextView>(R.id.exampleButton)
        val close = view.findViewById<TextView>(R.id.closeButton)

        title.text = item.word
        sub.text = item.meaning

        val exEn = item.example?.takeIf { it.isNotBlank() }
        val exVi = item.exampleMeaning?.takeIf { it.isNotBlank() }

        val fullExample = when {
            exEn != null && exVi != null -> "$exEn\n👉 $exVi"
            exEn != null -> exEn
            exVi != null -> "👉 $exVi"
            else -> "Không có ví dụ trong file."
        }

        example.text = fullExample
        example.visibility = View.GONE

        exampleButton.setOnClickListener {
            example.visibility = if (example.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            exampleButton.text = if (example.visibility == View.VISIBLE) "Ẩn VD" else "VD"
        }
        close.setOnClickListener { removePopup() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
            horizontalMargin = 0.04f
        }

        popupWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            popupWindowManager?.addView(view, params)
            popupView = view
        } catch (_: Exception) {
            popupView = null
        }
    }

    private fun removePopup() {
        popupView?.let { view ->
            try {
                popupWindowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        popupView = null
    }

    private fun loadWordsFromSelectedFolder(): List<VocabWord> {
        val raw = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getString(MainActivity.DATA_TREE_URI, null) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(this, Uri.parse(raw)) ?: return emptyList()
        if (!root.canRead()) return emptyList()

        val txtFiles = mutableListOf<DocumentFile>()
        var blacklistFile: DocumentFile? = null

        // Tách file blacklist.txt và các file từ vựng khác
        collectFiles(root, txtFiles, onBlacklistFound = { blacklistFile = it })

        // 1. Đọc danh sách từ bị chặn từ blacklist.txt (nếu có)
        val blacklistSet = HashSet<String>()
        blacklistFile?.let { file ->
            try {
                contentResolver.openInputStream(file.uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                        reader.forEachLine { line ->
                            val trimmed = line.trim().lowercase()
                            if (trimmed.isNotEmpty()) blacklistSet.add(trimmed)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. Đọc và lọc từ vựng từ các file còn lại
        val result = mutableListOf<VocabWord>()
        for (file in txtFiles) {
            try {
                contentResolver.openInputStream(file.uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                        result += parseText(reader.readText(), blacklistSet)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun collectFiles(
        directory: DocumentFile,
        txtFiles: MutableList<DocumentFile>,
        onBlacklistFound: (DocumentFile) -> Unit
    ) {
        for (child in directory.listFiles()) {
            if (child.isDirectory) {
                collectFiles(child, txtFiles, onBlacklistFound)
            } else if (child.isFile) {
                val fileName = child.name?.lowercase() ?: ""
                if (fileName == "blacklist.txt") {
                    onBlacklistFound(child)
                } else if (fileName.endsWith(".txt")) {
                    txtFiles.add(child)
                }
            }
        }
    }

    private fun parseText(text: String, blacklist: Set<String>): List<VocabWord> {
        val lines = text.replace("\r", "").lines()
        val entries = mutableListOf<VocabWord>()
        var i = 0

        while (i < lines.size) {
            if (!lines[i].trim().matches(Regex("\\d+\\."))) {
                i++
                continue
            }

            i++
            val fields = mutableListOf<String>()
            while (i < lines.size && fields.size < 2) {
                val s = lines[i].trim()
                if (s.isNotEmpty()) fields += s
                i++
            }
            if (fields.size < 2) continue

            val word = fields[0]
            val meaning = fields[1]
            var example: String? = null
            var exampleMeaning: String? = null

            while (i < lines.size && !lines[i].trim().matches(Regex("\\d+\\."))) {
                val s = lines[i].trim()
                when {
                    s.startsWith("Example:", ignoreCase = true) -> {
                        example = s.substringAfter(":").trim()
                    }
                    s.startsWith("Dịch:", ignoreCase = true) -> {
                        exampleMeaning = s.substringAfter(":").trim()
                    }
                }
                i++
            }

            // Chỉ thêm vào danh sách nếu TỪ KHÔNG NẰM TRONG BLACKLIST
            if (!blacklist.contains(word.trim().lowercase())) {
                entries += VocabWord(word, meaning, example, exampleMeaning)
            }
        }

        return entries
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Word Popup service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Word Popup đang chạy")
        .setContentText("Đang nhắc từ vựng định kỳ")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removePopup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "word_popup"
        private const val NOTIFICATION_ID = 1001
    }
}
