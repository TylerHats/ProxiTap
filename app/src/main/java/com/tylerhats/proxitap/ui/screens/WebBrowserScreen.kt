package com.tylerhats.proxitap.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

private fun getBookmarks(context: Context): List<Bookmark> {
    val prefs = context.getSharedPreferences("ProxiTapWebBookmarks", Context.MODE_PRIVATE)
    val bookmarksJson = prefs.getString("bookmarks", null) ?: return listOf(
        Bookmark("TikTok", "https://www.tiktok.com"),
        Bookmark("YouTube", "https://m.youtube.com"),
        Bookmark("SoundCloud", "https://m.soundcloud.com")
    )
    return try {
        val arr = JSONArray(bookmarksJson)
        val list = mutableListOf<Bookmark>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(Bookmark(obj.getString("title"), obj.getString("url")))
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveBookmarks(context: Context, bookmarks: List<Bookmark>) {
    val prefs = context.getSharedPreferences("ProxiTapWebBookmarks", Context.MODE_PRIVATE)
    val arr = JSONArray()
    for (b in bookmarks) {
        val obj = JSONObject()
        obj.put("title", b.title)
        obj.put("url", b.url)
        arr.put(obj)
    }
    prefs.edit().putString("bookmarks", arr.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
    onCloseClick: () -> Unit
) {
    val context = LocalContext.current
    var bookmarks by remember { mutableStateOf(getBookmarks(context)) }
    var currentUrl by remember { mutableStateOf("pt://newtab") }
    var urlInput by remember { mutableStateOf("") }
    var isDesktopMode by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    
    val isBookmarked = remember(currentUrl, bookmarks) {
        bookmarks.any { it.url.trimEnd('/') == currentUrl.trimEnd('/') }
    }

    val loadUrlOrSearch = { input: String ->
        val trimmed = input.trim()
        if (trimmed.isNotEmpty()) {
            val url = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else if (trimmed.contains(".") && !trimmed.contains(" ")) {
                "https://$trimmed"
            } else {
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
            }
            webViewRef.value?.loadUrl(url)
            currentUrl = url
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
        // Browser Controls Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (webViewRef.value?.canGoBack() == true) {
                        webViewRef.value?.goBack()
                    } else {
                        currentUrl = "pt://newtab"
                        urlInput = ""
                    }
                }
            ) {
                Text("◀")
            }
            IconButton(
                onClick = { webViewRef.value?.goForward() },
                enabled = webViewRef.value?.canGoForward() == true
            ) {
                Text("▶")
            }
            IconButton(
                onClick = {
                    if (currentUrl != "pt://newtab") {
                        webViewRef.value?.reload()
                    }
                }
            ) {
                Text("🔄")
            }
            IconButton(
                onClick = {
                    currentUrl = "pt://newtab"
                    urlInput = ""
                    webViewRef.value?.loadUrl("about:blank")
                }
            ) {
                Text("🏠")
            }

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                placeholder = { Text("Search or type URL", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { loadUrlOrSearch(urlInput) }),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Bookmark Toggle
            if (currentUrl != "pt://newtab" && currentUrl != "about:blank") {
                IconButton(
                    onClick = {
                        val current = currentUrl
                        if (isBookmarked) {
                            bookmarks = bookmarks.filterNot { it.url.trimEnd('/') == current.trimEnd('/') }
                        } else {
                            val title = webViewRef.value?.title ?: "Web Page"
                            bookmarks = bookmarks + Bookmark(title, current)
                        }
                        saveBookmarks(context, bookmarks)
                    }
                ) {
                    Text(if (isBookmarked) "⭐" else "☆")
                }
            }

            // Desktop Mode Toggle
            if (currentUrl != "pt://newtab" && currentUrl != "about:blank") {
                IconButton(
                    onClick = {
                        isDesktopMode = !isDesktopMode
                    }
                ) {
                    Text(if (isDesktopMode) "💻" else "📱")
                }
            }

            IconButton(onClick = onCloseClick) {
                Text("❌")
            }
        }

        // Main Area
        Box(modifier = Modifier.weight(1f)) {
            // WebView Component is ALWAYS in composition to preserve state & receive loadUrl requests
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mediaPlaybackRequiresUserGesture = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        
                        // Enable persistent cookie storage
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        // Prevent app redirect prompts
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let {
                                    if (it != "about:blank") {
                                        currentUrl = it
                                        urlInput = it
                                    }
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let {
                                    if (it != "about:blank") {
                                        currentUrl = it
                                        urlInput = it
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                val urlLower = url.lowercase()
                                
                                // Block known deep link/app store / attribution domains
                                if (urlLower.contains("onelink.me") || 
                                    urlLower.contains("appsflyer.com") || 
                                    urlLower.contains("play.google.com/store") || 
                                    urlLower.contains("app.link") || 
                                    urlLower.contains("itunes.apple.com") || 
                                    urlLower.contains("adjust.com") || 
                                    urlLower.contains("smart.link")) {
                                    Log.d("WebBrowser", "Blocked app-redirect/store URL: $url")
                                    return true // handled (blocked)
                                }

                                // Block deep link schemes (intent://, market://, tiktok://, etc.)
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    Log.d("WebBrowser", "Blocked non-http deep link redirection to: $url")
                                    return true // handled (blocked)
                                }
                                // Force http/https links to load internally inside the web view
                                view?.loadUrl(url)
                                return true
                            }
                        }
                        
                        webChromeClient = WebChromeClient()
                    }
                },
                update = { webView ->
                    webViewRef.value = webView
                    
                    val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    
                    val targetUA = if (isDesktopMode) desktopUA else mobileUA
                    if (webView.settings.userAgentString != targetUA) {
                        webView.settings.userAgentString = targetUA
                        webView.settings.useWideViewPort = isDesktopMode
                        webView.settings.loadWithOverviewMode = isDesktopMode
                        webView.reload()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (currentUrl == "pt://newtab" || currentUrl == "about:blank") {
                // Bookmarks Grid overlay (covers the WebView until a page is loaded)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Bookmarks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(bookmarks) { bookmark ->
                            Card(
                                onClick = {
                                    urlInput = bookmark.url
                                    loadUrlOrSearch(bookmark.url)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(96.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        val icon = when {
                                            bookmark.title.contains("TikTok", ignoreCase = true) -> "🎵"
                                            bookmark.title.contains("YouTube", ignoreCase = true) -> "📺"
                                            bookmark.title.contains("SoundCloud", ignoreCase = true) -> "☁️"
                                            else -> "🌐"
                                        }
                                        Text(icon, style = MaterialTheme.typography.headlineSmall)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = bookmark.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    // Remove Bookmark Button
                                    IconButton(
                                        onClick = {
                                            bookmarks = bookmarks.filterNot { it == bookmark }
                                            saveBookmarks(context, bookmarks)
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(24.dp)
                                    ) {
                                        Text("×", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}
