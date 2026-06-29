package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.myapplication.data.DownloadedMedia
import com.example.myapplication.data.MediaType
import com.example.myapplication.download.DownloadManager
import com.example.myapplication.network.SharedCookieJar
import com.example.myapplication.viewmodel.GalleryViewModel
import com.example.myapplication.viewmodel.ViewMode
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(viewModel: GalleryViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val downloadManager = remember { DownloadManager(context, SharedCookieJar()) }

    var showDeleteDialog by remember { mutableStateOf<DownloadedMedia?>(null) }
    var showDeleteMultipleDialog by remember { mutableStateOf(false) }

    fun safeOpenFile(media: DownloadedMedia) {
        try {
            context.startActivity(downloadManager.openFile(media.uri, media.type))
        } catch (e: Exception) {
            Toast.makeText(context, "File not found or已被删除", Toast.LENGTH_SHORT).show()
            viewModel.loadHistory()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadHistory()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top bar
        TopAppBar(
            title = {
                if (state.isSelectionMode) {
                    Text("已选择 ${state.selectedItems.size} 项")
                } else {
                    Text("我的下载 (${state.mediaList.size})")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (state.isSelectionMode)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.primaryContainer
            ),
            navigationIcon = {
                if (state.isSelectionMode) {
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = "取消选择")
                    }
                }
            },
            actions = {
                if (state.isSelectionMode) {
                    // 全选
                    IconButton(onClick = { viewModel.selectAll() }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "全选")
                    }
                    // 批量删除
                    IconButton(onClick = { showDeleteMultipleDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除选中",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    // 视图切换
                    IconButton(onClick = { viewModel.toggleViewMode() }) {
                        Icon(
                            if (state.viewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "切换视图"
                        )
                    }
                    // 刷新
                    IconButton(onClick = { viewModel.loadHistory() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            }
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.mediaList.isEmpty()) {
            EmptyState()
        } else {
            if (state.viewMode == ViewMode.GRID) {
                MediaGrid(
                    mediaList = state.mediaList,
                    selectedItems = state.selectedItems,
                    isSelectionMode = state.isSelectionMode,
                    onItemClick = { media ->
                        if (state.isSelectionMode) {
                            viewModel.toggleSelection(media.id)
                        } else {
                            safeOpenFile(media)
                        }
                    },
                    onItemLongClick = { media ->
                        viewModel.toggleSelection(media.id)
                    }
                )
            } else {
                MediaListByDate(
                    groupedMedia = state.groupedMedia,
                    selectedItems = state.selectedItems,
                    isSelectionMode = state.isSelectionMode,
                    onItemClick = { media ->
                        if (state.isSelectionMode) {
                            viewModel.toggleSelection(media.id)
                        } else {
                            safeOpenFile(media)
                        }
                    },
                    onItemLongClick = { media ->
                        viewModel.toggleSelection(media.id)
                    }
                )
            }
        }
    }

    // Preview dialog
    state.selectedMedia?.let { media ->
        MediaPreviewDialog(
            media = media,
            onDismiss = { viewModel.selectMedia(null) },
            onOpen = {
                safeOpenFile(media)
                viewModel.selectMedia(null)
            },
            onDelete = {
                showDeleteDialog = media
                viewModel.selectMedia(null)
            }
        )
    }

    // Delete single confirmation dialog
    showDeleteDialog?.let { media ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除文件") },
            text = { Text("确定要删除 \"${media.filename}\" 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMedia(media)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Delete multiple confirmation dialog
    if (showDeleteMultipleDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteMultipleDialog = false },
            title = { Text("批量删除") },
            text = { Text("确定要删除选中的 ${state.selectedItems.size} 个文件吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        showDeleteMultipleDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMultipleDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                "暂无下载记录",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "下载的媒体将显示在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun MediaGrid(
    mediaList: List<DownloadedMedia>,
    selectedItems: Set<Long>,
    isSelectionMode: Boolean,
    onItemClick: (DownloadedMedia) -> Unit,
    onItemLongClick: (DownloadedMedia) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(mediaList, key = { it.id }) { media ->
            MediaGridItem(
                media = media,
                isSelected = selectedItems.contains(media.id),
                isSelectionMode = isSelectionMode,
                onClick = { onItemClick(media) },
                onLongClick = { onItemLongClick(media) }
            )
        }
    }
}

@Composable
private fun MediaGridItem(
    media: DownloadedMedia,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        3.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(4.dp)
                    )
                } else Modifier
            )
    ) {
        // Thumbnail
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)
                .crossfade(true)
                .build(),
            contentDescription = media.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video/Audio indicator
        if (media.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .background(
                        Color.Black.copy(alpha = 0.5f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "视频",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        } else if (media.type == MediaType.AUDIO) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .background(
                        Color.Black.copy(alpha = 0.5f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Headphones,
                    contentDescription = "音频",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Selection checkbox
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.5f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已选择",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Gradient overlay at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(40.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
        )
    }
}

@Composable
private fun MediaListByDate(
    groupedMedia: Map<String, List<DownloadedMedia>>,
    selectedItems: Set<Long>,
    isSelectionMode: Boolean,
    onItemClick: (DownloadedMedia) -> Unit,
    onItemLongClick: (DownloadedMedia) -> Unit
) {
    val sortedDates = groupedMedia.keys.sorted().reversed()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        sortedDates.forEach { date ->
            val items = groupedMedia[date] ?: emptyList()

            // Date header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${items.size}个文件)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }

            // Media items for this date
            items(items, key = { it.id }) { media ->
                MediaListItem(
                    media = media,
                    isSelected = selectedItems.contains(media.id),
                    isSelectionMode = isSelectionMode,
                    onClick = { onItemClick(media) },
                    onLongClick = { onItemLongClick(media) }
                )
            }
        }
    }
}

@Composable
private fun MediaListItem(
    media: DownloadedMedia,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(12.dp)
                    )
                } else Modifier
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Thumbnail
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(media.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = media.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (media.type == MediaType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "视频",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else if (media.type == MediaType.AUDIO) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = "音频",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTime(media.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Type badge
            val (typeLabel, typeColor) = when (media.type) {
                MediaType.VIDEO -> "视频" to MaterialTheme.colorScheme.secondaryContainer
                MediaType.AUDIO -> "音频" to MaterialTheme.colorScheme.primaryContainer
                MediaType.IMAGE -> "图片" to MaterialTheme.colorScheme.tertiaryContainer
            }
            Surface(
                color = typeColor,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = typeLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
private fun MediaPreviewDialog(
    media: DownloadedMedia,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                // Preview image
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(media.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = media.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (media.type == MediaType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(64.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.5f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "播放",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }

                // Filename
                Text(
                    text = media.filename,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("删除")
                    }

                    Button(
                        onClick = onOpen,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开")
                    }
                }
            }
        }
    }
}
