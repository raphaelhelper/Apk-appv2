package com.qui.wordpopup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var folderText: TextView
    private lateinit var intervalEdit: EditText

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(DATA_TREE_URI, uri.toString())
                .apply()
            updateFolderStatus()
        } catch (e: Exception) {
            folderText.text = "❌ Không lưu được quyền thư mục: ${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        folderText = findViewById(R.id.folderText)
        intervalEdit = findViewById(R.id.intervalEdit)

        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.folderButton).setOnClickListener {
            folderPicker.launch(null)
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            val minutes = intervalEdit.text.toString().toLongOrNull()?.coerceIn(1, 1440) ?: 10L
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("interval_min", minutes).apply()

            val treeUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(DATA_TREE_URI, null)
            if (treeUri == null) {
                statusText.text = "⚠️ Hãy chọn thư mục chứa các file .txt trước."
                return@setOnClickListener
            }

            if (Settings.canDrawOverlays(this)) {
                val intent = Intent(this, PopupService::class.java)
                ContextCompat.startForegroundService(this, intent)
                statusText.text = "✅ Đang chạy: popup mỗi $minutes phút."
            } else {
                statusText.text = "⚠️ Chưa cấp quyền popup nổi."
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, PopupService::class.java))
            statusText.text = "Đã dừng."
        }

        updateFolderStatus()
    }

    override fun onResume() {
        super.onResume()
        updateFolderStatus()
        if (::statusText.isInitialized && Settings.canDrawOverlays(this)) {
            statusText.text = "✅ Đã có quyền popup nổi."
        } else if (::statusText.isInitialized) {
            statusText.text = "⚠️ Chưa có quyền popup nổi."
        }
    }

    private fun updateFolderStatus() {
        if (!::folderText.isInitialized) return
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(DATA_TREE_URI, null)
        if (raw == null) {
            folderText.text = "📁 Chưa chọn thư mục dữ liệu."
            return
        }

        val uri = Uri.parse(raw)
        val tree = DocumentFile.fromTreeUri(this, uri)
        folderText.text = if (tree != null && tree.canRead()) {
            "📁 Đã cấp quyền thư mục: ${tree.name ?: "(thư mục đã chọn)"}"
        } else {
            "⚠️ Quyền thư mục không còn hợp lệ. Hãy chọn lại."
        }
    }

    companion object {
        const val PREFS = "settings"
        const val DATA_TREE_URI = "data_tree_uri"
    }
}
