package com.example.myapplication.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.MediaItemCard
import com.example.myapplication.viewmodel.MainViewModel

@Composable
fun InstagramTab(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val state = uiState.instagramState
    val context = LocalContext.current

    val isUrlValid = state.inputUrl.trim().let { url ->
        url.isEmpty() || url.contains("instagram.com")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = state.inputUrl,
            onValueChange = { viewModel.updateInstagramUrl(it) },
            label = { Text("Instagram URL") },
            placeholder = { Text("Paste Instagram post/reel link here...") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row {
                    if (state.inputUrl.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateInstagramUrl("") }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = clipboard?.primaryClip
                        val text = clip?.getItemAt(0)?.text?.toString() ?: ""
                        if (text.contains("instagram.com")) {
                            viewModel.updateInstagramUrl(text.trim())
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, "Paste from clipboard")
                    }
                }
            },
            singleLine = true,
            isError = !isUrlValid,
            supportingText = if (!isUrlValid) {
                { Text("Please enter a valid Instagram URL") }
            } else null
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Supports: Posts (images)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.fetchInstagramMedia() },
                modifier = Modifier.weight(1f),
                enabled = !state.isLoading && state.inputUrl.trim().isNotEmpty() && isUrlValid
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fetch Media")
                }
            }

            if (state.mediaItems.isNotEmpty()) {
                Button(
                    onClick = { viewModel.downloadAll("instagram") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Download All")
                }
            }
        }

        state.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
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

        Spacer(modifier = Modifier.height(16.dp))

        if (state.mediaItems.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Found ${state.mediaItems.size} item(s)",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = { viewModel.clearMedia("instagram") }) {
                    Text("Clear")
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
                        onDownload = { viewModel.downloadMedia(item, "instagram") },
                        onCancel = { viewModel.cancelDownload(item, "instagram") },
                        onPause = { viewModel.pauseDownload(item, "instagram") },
                        onResume = { viewModel.resumeDownload(item, "instagram") }
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
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Paste an Instagram link to download media",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
