package com.example.myapplication.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.DownloadStatus
import com.example.myapplication.data.MediaItem
import com.example.myapplication.data.MediaType

@Composable
fun MediaItemCard(
    item: MediaItem,
    progress: Float = 0f,
    onDownload: () -> Unit = {},
    onCancel: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (item.type) {
                            MediaType.VIDEO -> "Video"
                            MediaType.IMAGE -> "Image"
                            MediaType.AUDIO -> "Audio"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (item.status) {
                    DownloadStatus.PENDING -> {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.Download, "Download", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, "Pause", tint = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, "Resume", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    DownloadStatus.CANCELLED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.Refresh, "Retry", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        Text(
                            text = "Done",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    DownloadStatus.FAILED -> {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.Refresh, "Retry", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PAUSED) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (item.status == DownloadStatus.PAUSED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (item.status == DownloadStatus.PAUSED) "Paused - ${(progress * 100).toInt()}%" else "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.status == DownloadStatus.CANCELLED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Cancelled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (item.status == DownloadStatus.FAILED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Failed - tap retry",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
