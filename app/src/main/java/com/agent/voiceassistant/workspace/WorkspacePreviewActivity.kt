package com.agent.voiceassistant.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agent.voiceassistant.databinding.ActivityWorkspacePreviewBinding
import com.agent.voiceassistant.editor.TextEditorActivity
import com.agent.voiceassistant.R
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.io.FileInputStream
import java.util.Locale

class WorkspacePreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkspacePreviewBinding
    private lateinit var repository: WorkspaceRepository
    private lateinit var path: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspacePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WorkspaceRepository(this)
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        binding.workspacePreviewToolbar.setNavigationOnClickListener { finish() }
        binding.workspacePreviewToolbar.title = path.substringAfterLast('/')
        binding.workspacePreviewToolbar.inflateMenu(R.menu.menu_workspace_preview)
        binding.workspacePreviewToolbar.menu.findItem(R.id.action_edit_workspace_file).isVisible = repository.canEdit(path)
        binding.workspacePreviewToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_edit_workspace_file) {
                startActivity(TextEditorActivity.workspaceIntent(this, path))
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { showPreview(path) }
            .onFailure {
                Toast.makeText(this, it.message ?: "预览失败", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun showPreview(path: String) {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension == "html" || extension == "htm") {
            binding.workspaceTextScroll.visibility = View.GONE
            binding.workspaceWebPreview.visibility = View.VISIBLE
            configureWebView(binding.workspaceWebPreview)
            val url = "$WORKSPACE_ORIGIN/${Uri.encode(path, "/")}"
            binding.workspaceWebPreview.loadUrl(url)
            return
        }

        binding.workspaceWebPreview.stopLoading()
        binding.workspaceWebPreview.visibility = View.GONE
        binding.workspaceTextScroll.visibility = View.VISIBLE

        val text = repository.readPreview(path)
        if (extension == "md" || extension == "markdown") {
            Markwon.builder(this).usePlugin(TablePlugin.create(this)).build()
                .setMarkdown(binding.tvWorkspacePreview, text)
        } else {
            binding.tvWorkspacePreview.typeface = android.graphics.Typeface.MONOSPACE
            binding.tvWorkspacePreview.text = text
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.domStorageEnabled = false
        webView.settings.databaseEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (uri.scheme != "https" || uri.host != WORKSPACE_HOST) return null
                val relative = Uri.decode(uri.path.orEmpty().removePrefix("/"))
                return runCatching {
                    val file = repository.resolveWebPath(relative)
                    require(file.isFile) { "资源不存在" }
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                        ?: "application/octet-stream"
                    WebResourceResponse(mime, if (mime.startsWith("text/")) "utf-8" else null, FileInputStream(file))
                }.getOrNull()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (uri.host == WORKSPACE_HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
        }
    }

    override fun onDestroy() {
        binding.workspaceWebPreview.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "workspace_path"
        private const val WORKSPACE_HOST = "appassets.androidplatform.net"
        private const val WORKSPACE_ORIGIN = "https://$WORKSPACE_HOST"
    }
}
