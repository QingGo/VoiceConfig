package com.voiceconfig.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiceconfig.data.local.repository.ShoppingItemRecord

@Composable
fun ShoppingResearchPage(
    items: List<ShoppingItemRecord>,
    onClose: () -> Unit,
    onUpdateStatus: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onStartResearch: () -> Unit,
    onClearAll: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    var filter by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    val filtered = items
        .filter { if (filter == null) true else it.status == filter }
        .filter { search.isBlank() || it.title.contains(search, ignoreCase = true) || it.platform.contains(search, ignoreCase = true) }
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("购物研究", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "价格 / 评分 / 口碑对比清单",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (items.isNotEmpty()) {
                    TextButton(onClick = onExport) {
                        Text("导出")
                    }
                    TextButton(onClick = { showClearDialog = true }) {
                        Text("清空")
                    }
                }
                TextButton(onClick = onStartResearch) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("去研究")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(null to "全部", "WATCH" to "关注", "RECOMMENDED" to "推荐", "BOUGHT" to "已购").forEach { (value, label) ->
                    val selected = filter == value
                    if (selected) {
                        Button(
                            onClick = { filter = value },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        ) { Text(label) }
                    } else {
                        TextButton(
                            onClick = { filter = value },
                            modifier = Modifier.weight(1f),
                        ) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("搜索商品或平台") },
                singleLine = true,
            )

            if (filtered.isEmpty()) {
                VoiceEmptyState(
                    title = if (items.isEmpty()) "还没有购物研究记录" else "该分类暂无商品",
                    message = "让智能助手搜索商品、提取价格评分并保存到这里",
                    actionLabel = "开始一次研究",
                    onAction = onStartResearch,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.productId }) { item ->
                        ShoppingItemCard(item, onUpdateStatus, onDelete)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空购物研究") },
            text = { Text("将删除全部购物研究记录，且不可恢复。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearDialog = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

}

@Composable
private fun ShoppingItemCard(    item: ShoppingItemRecord,
    onUpdateStatus: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${item.platform} · ${item.status}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "¥${item.price}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item.originalPrice?.let {
                    Text("原价 ¥$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                item.rating?.let {
                    Text("评分 $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.reviewCount?.let {
                    Text("${it} 条评价", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.note.isNotBlank()) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.status != "RECOMMENDED") {
                    TextButton(onClick = { onUpdateStatus(item.productId, "RECOMMENDED") }) { Text("推荐") }
                }
                if (item.status != "BOUGHT") {
                    TextButton(onClick = { onUpdateStatus(item.productId, "BOUGHT") }) { Text("已购") }
                }
                if (item.status != "WATCH") {
                    TextButton(onClick = { onUpdateStatus(item.productId, "WATCH") }) { Text("关注") }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { onDelete(item.productId) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
