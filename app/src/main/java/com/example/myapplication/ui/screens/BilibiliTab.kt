package com.example.myapplication.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.MediaItemCard
import com.example.myapplication.viewmodel.MainViewModel

fun extractBilibiliUrl(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""

    val backtickUrl = Regex("""`([^`]*(?:bilibili\.com|b23\.tv)[^`]*)`""").find(trimmed)
    if (backtickUrl != null) return backtickUrl.groupValues[1].trim()

    val urlInText = Regex("""https?://[^\s<>　：）】]+""").find(trimmed)
    if (urlInText != null) {
        val url = urlInText.groupValues[0].trimEnd(']', ')', '}', ',', '.', '。', '，')
        if (url.contains("bilibili.com") || url.contains("b23.tv")) return url
    }

    val bvMatch = Regex("""BV[a-zA-Z0-9]{6,}""").find(trimmed)
    if (bvMatch != null) return "https://www.bilibili.com/video/${bvMatch.groupValues[0]}"

    return trimmed
}

@Composable
fun BilibiliTab(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val state = uiState.bilibiliState
    val context = LocalContext.current

    val isUrlValid = state.inputUrl.trim().let { url ->
        url.isEmpty() || url.contains("bilibili.com") || url.contains("b23.tv") || url.contains("BV")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.inputUrl,
            onValueChange = { raw ->
                val extracted = extractBilibiliUrl(raw)
                viewModel.updateBilibiliUrl(extracted)
            },
            label = { Text("B站视频链接") },
            placeholder = { Text("粘贴视频链接或 BV 号…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                Row {
                    if (state.inputUrl.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateBilibiliUrl("") }) {
                            Icon(Icons.Default.Clear, "清除")
                        }
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = clipboard?.primaryClip
                        val text = clip?.getItemAt(0)?.text?.toString() ?: ""
                        val extracted = extractBilibiliUrl(text)
                        if (extracted.isNotEmpty() && (extracted.contains("bilibili.com") || extracted.contains("b23.tv") || extracted.contains("BV"))) {
                            viewModel.updateBilibiliUrl(extracted)
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, "粘贴")
                    }
                }
            },
            singleLine = true,
            isError = !isUrlValid,
            supportingText = if (!isUrlValid) {
                { Text("请输入有效的B站视频链接") }
            } else null
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "支持：视频 (DASH 720P/1080P) · 纯音频 (M4A) · 封面图",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.fetchBilibiliMedia() },
                modifier = Modifier.weight(1f),
                enabled = !state.isLoading && state.inputUrl.trim().isNotEmpty() && isUrlValid,
                shape = RoundedCornerShape(10.dp)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("解析中…")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("解析视频")
                }
            }

            if (state.mediaItems.isNotEmpty()) {
                Button(
                    onClick = { viewModel.downloadAll("bilibili") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("全部下载")
                }
            }
        }

        state.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.mediaItems.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "找到 ${state.mediaItems.size} 个资源",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { viewModel.clearMedia("bilibili") }) {
                    Text("清除")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.mediaItems) { item ->
                    MediaItemCard(
                        item = item,
                        progress = state.downloadProgress[item.id] ?: 0f,
                        onDownload = { viewModel.downloadMedia(item, "bilibili") },
                        onCancel = { viewModel.cancelDownload(item, "bilibili") },
                        onPause = { viewModel.pauseDownload(item, "bilibili") },
                        onResume = { viewModel.resumeDownload(item, "bilibili") }
                    )
                }
            }
        } else if (!state.isLoading && state.errorMessage == null && state.inputUrl.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.SmartDisplay,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "粘贴B站视频链接即可下载",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "支持 bilibili.com / b23.tv / BV 号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
