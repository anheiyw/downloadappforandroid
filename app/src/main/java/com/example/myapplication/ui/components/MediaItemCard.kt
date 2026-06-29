package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 类型图标
                val (icon, iconTint) = when (item.type) {
                    MediaType.VIDEO -> Icons.Default.Movie to MaterialTheme.colorScheme.primary
                    MediaType.AUDIO -> Icons.Default.Headphones to MaterialTheme.colorScheme.secondary
                    MediaType.IMAGE -> Icons.Default.Image to MaterialTheme.colorScheme.tertiary
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (item.type) {
                                MediaType.VIDEO -> MaterialTheme.colorScheme.primaryContainer
                                MediaType.AUDIO -> MaterialTheme.colorScheme.secondaryContainer
                                MediaType.IMAGE -> MaterialTheme.colorScheme.tertiaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 类型标签
                    val (label, color) = when (item.type) {
                        MediaType.VIDEO -> "视频" to MaterialTheme.colorScheme.primary
                        MediaType.AUDIO -> "音频" to MaterialTheme.colorScheme.secondary
                        MediaType.IMAGE -> "图片" to MaterialTheme.colorScheme.tertiary
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.Medium
                    )
                }

                when (item.status) {
                    DownloadStatus.PENDING -> {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.Download, "下载", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, "暂停", tint = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, "取消", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, "继续", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, "取消", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    DownloadStatus.CANCELLED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.Refresh, "重试", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "已完成",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    DownloadStatus.FAILED -> {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.Refresh, "重试", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PAUSED) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (item.status == DownloadStatus.PAUSED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (item.status == DownloadStatus.PAUSED) "已暂停 · ${(progress * 100).toInt()}%" else "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.status == DownloadStatus.CANCELLED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已取消",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (item.status == DownloadStatus.FAILED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "下载失败 · 点击重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
