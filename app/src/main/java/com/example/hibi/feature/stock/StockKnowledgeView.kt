package com.example.hibi.feature.stock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hibi.data.StockKnowledge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private val KNOWLEDGE_CATEGORIES = listOf(
    "チャート・テクニカル",
    "ファンダメンタル",
    "リスク管理",
    "マクロ・指標",
    "その他",
)

@Composable
fun StockKnowledgeView(
    knowledgeList: List<StockKnowledge>,
    onSave: (
        editingId: String?,
        title: String,
        category: String,
        tags: List<String>,
        content: String,
        images: List<String>,
        isPinned: Boolean,
    ) -> Unit,
    onTogglePin: (StockKnowledge) -> Unit,
    onDelete: (StockKnowledge) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("すべて") }
    var zoomImageDataUrl by remember { mutableStateOf<String?>(null) }
    var editingItem by remember { mutableStateOf<StockKnowledge?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deletingItem by remember { mutableStateOf<StockKnowledge?>(null) }

    val categories = remember(knowledgeList) {
        val set = linkedSetOf("すべて")
        set.addAll(KNOWLEDGE_CATEGORIES)
        knowledgeList.forEach { if (it.category.isNotBlank()) set.add(it.category) }
        set.toList()
    }

    val filtered = remember(knowledgeList, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        knowledgeList.filter { item ->
            val catOk = selectedCategory == "すべて" || item.category == selectedCategory
            val qOk = q.isEmpty() ||
                item.title.lowercase().contains(q) ||
                item.content.lowercase().contains(q) ||
                item.category.lowercase().contains(q) ||
                item.tags.any { it.lowercase().contains(q) }
            catOk && qOk
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("検索（例: セリクラ, RSI, 出来高）", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "クリア")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = cat == selectedCategory,
                                onClick = { selectedCategory = cat },
                                label = { Text(cat, fontSize = 12.sp) },
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📚", fontSize = 36.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (knowledgeList.isEmpty()) "まだ投資ナレッジがありません" else "該当するナレッジがありません",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "右下の「＋」または Web ポータルから図解やメモを追加できます",
                                fontSize = 12.sp,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    KnowledgeCard(
                        item = item,
                        onTagClick = { tag -> searchQuery = tag },
                        onImageClick = { dataUrl -> zoomImageDataUrl = dataUrl },
                        onTogglePin = { onTogglePin(item) },
                        onEdit = { editingItem = item },
                        onDelete = { deletingItem = item },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "ナレッジを追加")
        }
    }

    zoomImageDataUrl?.let { src ->
        ZoomableImageDialog(
            dataUrl = src,
            onDismiss = { zoomImageDataUrl = null },
        )
    }

    if (showAddDialog || editingItem != null) {
        KnowledgeEditDialog(
            initial = editingItem,
            onDismiss = {
                showAddDialog = false
                editingItem = null
            },
            onConfirm = { title, category, tags, content, images, isPinned ->
                onSave(editingItem?.id, title, category, tags, content, images, isPinned)
                showAddDialog = false
                editingItem = null
            },
        )
    }

    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("ナレッジを削除") },
            text = { Text("「${item.title}」を削除してもよろしいですか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(item)
                        deletingItem = null
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun KnowledgeCard(
    item: StockKnowledge,
    onTagClick: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = if (item.isPinned) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.isPinned) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    text = "📌 固定",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = item.category,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row {
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (item.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "固定切り替え",
                            tint = if (item.isPinned) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "編集",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "削除",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (item.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFFEFF6FF),
                            modifier = Modifier.clickable { onTagClick(tag) },
                        ) {
                            Text(
                                text = "#$tag",
                                fontSize = 11.sp,
                                color = Color(0xFF2563EB),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            item.images.forEach { dataUrl ->
                Spacer(Modifier.height(10.dp))
                val bitmap = remember(dataUrl) { decodeDataUrlBitmap(dataUrl) }
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .clickable { onImageClick(dataUrl) },
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = item.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                        )
                    }
                    Text(
                        text = "🔍 画像をタップしてピンチ拡大表示",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (item.content.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        MarkdownText(markdown = item.content)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImageDialog(
    dataUrl: String,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(dataUrl) { decodeDataUrlBitmap(dataUrl) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale > 1f) offset + pan else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "拡大画像",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "閉じる", tint = Color.White)
            }

            Text(
                text = "ピンチで拡大・ドラッグで移動 / ダブルタップでリセット",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun KnowledgeEditDialog(
    initial: StockKnowledge?,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        category: String,
        tags: List<String>,
        content: String,
        images: List<String>,
        isPinned: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category ?: KNOWLEDGE_CATEGORIES.first()) }
    var tagsText by remember { mutableStateOf(initial?.tags?.joinToString(", ").orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var images by remember { mutableStateOf(initial?.images.orEmpty()) }
    var isPinned by remember { mutableStateOf(initial?.isPinned ?: false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val encoded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { compressUriToDataUrl(context, it) }
                }
                val combined = images + encoded
                val totalChars = combined.sumOf { it.length }
                if (totalChars <= 880_000) {
                    images = combined
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "ナレッジを編集" else "新しいナレッジ") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("タイトル *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("カテゴリ", fontSize = 12.sp, color = Color.Gray)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KNOWLEDGE_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = cat == category,
                            onClick = { category = cat },
                            label = { Text(cat, fontSize = 11.sp) },
                        )
                    }
                }

                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("タグ（カンマ区切り）") },
                    placeholder = { Text("例: セリクラ, 出来高") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedButton(
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("図解・チャート画像を追加 (${images.size}枚)")
                }

                if (images.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        images.forEachIndexed { idx, dataUrl ->
                            val bmp = remember(dataUrl) { decodeDataUrlBitmap(dataUrl) }
                            if (bmp != null) {
                                Box(modifier = Modifier.size(72.dp)) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                    IconButton(
                                        onClick = { images = images.filterIndexed { i, _ -> i != idx } },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(20.dp)
                                            .background(Color.Black.copy(alpha = 0.7f), CircleShape),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "削除",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("解説・メモ（Markdown可）") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isPinned = !isPinned },
                ) {
                    Checkbox(checked = isPinned, onCheckedChange = { isPinned = it })
                    Text("上部に固定表示する", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tags = tagsText
                        .split(',', '、', ' ')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onConfirm(title, category, tags, content, images, isPinned)
                },
                enabled = title.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

private fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? = runCatching {
    val base64Part = dataUrl.substringAfter("base64,", missingDelimiterValue = dataUrl)
    val bytes = Base64.decode(base64Part, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

private fun compressUriToDataUrl(
    context: Context,
    uri: Uri,
    maxWidth: Int = 1400,
    maxBytes: Int = 420_000,
): String? = runCatching {
    val rawBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)
    } ?: return null

    val scaled = if (rawBitmap.width > maxWidth) {
        val ratio = maxWidth.toFloat() / rawBitmap.width
        val targetH = (rawBitmap.height * ratio).roundToInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(rawBitmap, maxWidth, targetH, true)
    } else {
        rawBitmap
    }

    var quality = 85
    var bytes: ByteArray
    do {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bytes = out.toByteArray()
        quality -= 10
    } while (bytes.size > maxBytes && quality >= 40)

    if (scaled !== rawBitmap) scaled.recycle()
    rawBitmap.recycle()

    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    "data:image/jpeg;base64,$b64"
}.getOrNull()
