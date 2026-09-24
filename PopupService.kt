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

private data class VocabWord(
    val word: String,
    val meaning: String,
    val example: String?
)

class PopupService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var popupView: View? = null
    private var popupWindowManager: WindowManager? = null
    private var wordsIndex = 0
    private var words: List<VocabWord> = emptyList()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        words = loadWordsFromSelectedFolder()
        handler.post(showTask)
    }

    private val showTask = object : Runnable {
        override fun run() {
            if (Settings.canDrawOverlays(this@PopupService)) {
                // Nếu popup trước đó vẫn còn mở thì không chồng thêm popup mới.
                if (popupView == null) showNextWord()
            }
            val minutes = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getLong("interval_min", 10L)
                .coerceIn(1, 1440)
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(minutes))
        }
    }

    private fun showNextWord() {
        if (words.isEmpty()) return

        val item = words[wordsIndex % words.size]
        wordsIndex++

        val view = LayoutInflater.from(this).inflate(R.layout.popup_word, null)
        val title = view.findViewById<TextView>(R.id.wordText)
        val sub = view.findViewById<TextView>(R.id.meaningText)
        val example = view.findViewById<TextView>(R.id.exampleText)
        val exampleButton = view.findViewById<TextView>(R.id.exampleButton)
        val close = view.findViewById<TextView>(R.id.closeButton)

        title.text = item.word
        sub.text = item.meaning
        example.text = item.example?.takeIf { it.isNotBlank() } ?: "Không có ví dụ trong file."
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

        val files = mutableListOf<DocumentFile>()
        collectTxtFiles(root, files)
        files.sortBy { it.uri.toString() }

        val result = mutableListOf<VocabWord>()
        for (file in files) {
            try {
                contentResolver.openInputStream(file.uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                        result += parseText(reader.readText())
                    }
                }
            } catch (_: Exception) {
                // Bỏ qua file lỗi, tiếp tục đọc các file còn lại.
            }
        }
        return result
    }

    private fun collectTxtFiles(directory: DocumentFile, out: MutableList<DocumentFile>) {
        for (child in directory.listFiles()) {
            if (child.isDirectory) {
                collectTxtFiles(child, out)
            } else if (child.isFile && child.name?.lowercase()?.endsWith(".txt") == true) {
                out += child
            }
        }
    }

    private fun parseText(text: String): List<VocabWord> {
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

            while (i < lines.size && !lines[i].trim().matches(Regex("\\d+\\."))) {
                val s = lines[i].trim()
                when {
                    s.startsWith("Example:", ignoreCase = true) -> {
                        example = s.substringAfter(":").trim()
                    }
                    s.startsWith("Dịch:", ignoreCase = true) -> {
                        // Có thể thêm bản dịch ví dụ về sau; hiện popup chỉ cần câu tiếng Anh.
                    }
                }
                i++
            }

            entries += VocabWord(word, meaning, example)
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
