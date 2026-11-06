package io.github.mangi.eta.ui.markdown

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 绘制一份已解析的 [MarkdownDocument]。
 *
 * 每个块以源码偏移作为组合 key；未变化的块是同一个实例，组合期直接跳过。传入
 * [reveal] 时文本块接入逐字显现：排版仍然一次完成，帧间推进只让绘制失效。
 */
@Composable
internal fun MarkdownContent(
    document: MarkdownDocument,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    reveal: SmoothTextRevealCoordinator? = null,
) {
    val scope = remember(style, reveal) { MarkdownRenderScope(style, reveal) }
    MarkdownBlockColumn(blocks = document.blocks, scope = scope, compact = false, modifier = modifier)
}

@Immutable
private class MarkdownRenderScope(
    val style: MarkdownStyle,
    val reveal: SmoothTextRevealCoordinator?,
) {
    fun muted() = MarkdownRenderScope(style.muted(), reveal)
}

/** 嵌套列表的层级只影响 marker 字形，用 CompositionLocal 传递，块组件不必逐层转发。 */
private val LocalListDepth = compositionLocalOf { 0 }

@Composable
private fun MarkdownBlockColumn(
    blocks: List<MarkdownBlock>,
    scope: MarkdownRenderScope,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            key(block.offset) {
                val previous = blocks.getOrNull(index - 1)
                val gap = if (compact) {
                    markdownCompactGap(previous, scope.style.tone)
                } else {
                    markdownBlockGap(previous, block, scope.style.tone)
                }
                if (gap.value > 0f) Spacer(Modifier.height(with(density) { gap.toDp() }))
                MarkdownBlockView(block, scope)
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, scope: MarkdownRenderScope) {
    when (block) {
        is MarkdownParagraph -> MarkdownText(block, scope.style.body, scope)
        is MarkdownHeading -> MarkdownText(
            block = block,
            style = scope.style.heading(block.level),
            scope = scope,
            modifier = Modifier.semantics { heading() },
        )
        is MarkdownCode -> MarkdownCodeBlock(block, scope)
        is MarkdownQuote -> MarkdownQuoteBlock(block, scope)
        is MarkdownAlert -> MarkdownAlertBlock(block, scope)
        is MarkdownList -> MarkdownListBlock(block, scope)
        is MarkdownTable -> MarkdownTableBlock(block, scope)
        is MarkdownTableCell -> MarkdownText(block, scope.style.table, scope)
        is MarkdownRule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(0.5.dp)
                .background(scope.style.dividerColor),
        )
        is MarkdownImage -> MarkdownImageBlock(block, scope)
    }
}

@Composable
private fun MarkdownText(
    block: MarkdownTextBlock,
    style: TextStyle,
    scope: MarkdownRenderScope,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    val reveal = scope.reveal
    // 空单元格没有可显现的文字，也不占用显现协调器的 key。
    if (reveal == null || block.text.isEmpty()) {
        Text(text = block.text, style = style, softWrap = softWrap, modifier = modifier)
        return
    }
    val revealState = rememberSmoothTextRevealState(block.revealKey, reveal)
    // 显现裁剪按字素边界逐帧变化，关闭像素对齐可避免裁剪边缘抖动。
    val animatedStyle = remember(style) { style.copy(textMotion = TextMotion.Animated) }
    Text(
        text = block.text,
        style = animatedStyle,
        softWrap = softWrap,
        modifier = modifier.smoothTextReveal(revealState),
        onTextLayout = { layout -> revealState.onTextLayout(block.text.text, layout) },
    )
}

// ── 代码块 ──────────────────────────────────────────────────────────────

@Composable
private fun MarkdownCodeBlock(block: MarkdownCode, scope: MarkdownRenderScope) {
    val style = scope.style
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = style.codeBackground, cornerRadius = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language ?: stringResource(R.string.markdown_code_block),
                style = MiuixTheme.textStyles.footnote2,
                color = style.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyButton(text = block.text.text, tint = style.secondaryColor)
        }
        // 代码不折行，长行横向滚动，保留缩进与对齐。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            MarkdownText(block, style.code, scope, softWrap = false)
        }
    }
}

@Composable
private fun CopyButton(text: String, tint: Color) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    IconButton(
        onClick = {
            @Suppress("DEPRECATION")
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        minWidth = 32.dp,
        minHeight = 32.dp,
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_code),
            modifier = Modifier.size(14.dp),
            tint = if (copied) MiuixTheme.colorScheme.primary else tint,
        )
    }
}

// ── 引用与提示 ──────────────────────────────────────────────────────────

@Composable
private fun MarkdownQuoteBlock(block: MarkdownQuote, scope: MarkdownRenderScope) {
    val barColor = scope.style.dividerColor
    val mutedScope = remember(scope) { scope.muted() }
    MarkdownBlockColumn(
        blocks = block.blocks,
        scope = mutedScope,
        compact = false,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = 3.dp.toPx()
                drawLine(
                    color = barColor,
                    start = Offset(width / 2, width / 2),
                    end = Offset(width / 2, size.height - width / 2),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
            .padding(start = 14.dp),
    )
}

@Composable
private fun MarkdownAlertBlock(block: MarkdownAlert, scope: MarkdownRenderScope) {
    val accent = block.kind.accentColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = accent.copy(alpha = 0.08f), cornerRadius = 12.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = block.kind.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(block.kind.label),
                style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
        if (block.blocks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MarkdownBlockColumn(blocks = block.blocks, scope = scope, compact = false)
        }
    }
}

private val MarkdownAlertKind.icon: ImageVector
    get() = when (this) {
        MarkdownAlertKind.Note -> Icons.Rounded.Info
        MarkdownAlertKind.Tip -> Icons.Rounded.Lightbulb
        MarkdownAlertKind.Important -> Icons.Rounded.Feedback
        MarkdownAlertKind.Warning -> Icons.Rounded.Warning
        MarkdownAlertKind.Caution -> Icons.Rounded.Report
    }

private val MarkdownAlertKind.label: Int
    get() = when (this) {
        MarkdownAlertKind.Note -> R.string.markdown_alert_note
        MarkdownAlertKind.Tip -> R.string.markdown_alert_tip
        MarkdownAlertKind.Important -> R.string.markdown_alert_important
        MarkdownAlertKind.Warning -> R.string.markdown_alert_warning
        MarkdownAlertKind.Caution -> R.string.markdown_alert_caution
    }

@Composable
private fun MarkdownAlertKind.accentColor(): Color = when (this) {
    MarkdownAlertKind.Note -> MiuixTheme.colorScheme.primary
    MarkdownAlertKind.Tip -> StatusSuccess
    MarkdownAlertKind.Important -> AlertImportantColor
    MarkdownAlertKind.Warning -> StatusWarning
    MarkdownAlertKind.Caution -> MiuixTheme.colorScheme.error
}

private val AlertImportantColor = Color(0xFF8B5CF6)

// ── 列表 ────────────────────────────────────────────────────────────────

@Composable
private fun MarkdownListBlock(block: MarkdownList, scope: MarkdownRenderScope) {
    val depth = LocalListDepth.current
    val answer = scope.style.tone == MarkdownTone.Answer
    val itemGap = when {
        block.loose -> if (answer) 10.dp else 8.dp
        else -> if (answer) 4.dp else 2.dp
    }
    // 序号按本列表最长的数字预留宽度，各项正文左边缘对齐。
    val markerWidth = if (block.ordered) {
        val digits = (block.items.lastOrNull()?.number ?: 1).toString().length
        (digits * 9 + 8).dp
    } else {
        14.dp
    }
    Column(Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { index, item ->
            key(item.offset) {
                if (index > 0) Spacer(Modifier.height(itemGap))
                Row(Modifier.fillMaxWidth()) {
                    ListMarker(item = item, ordered = block.ordered, depth = depth, width = markerWidth, scope = scope)
                    CompositionLocalProvider(LocalListDepth provides depth + 1) {
                        MarkdownBlockColumn(
                            blocks = item.blocks,
                            scope = scope,
                            compact = !block.loose,
                            modifier = Modifier
                                .weight(1f)
                                .alignByBaseline(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ListMarker(
    item: MarkdownListItem,
    ordered: Boolean,
    depth: Int,
    width: Dp,
    scope: MarkdownRenderScope,
) {
    val style = scope.style
    val reveal = scope.reveal
    // 在 graphicsLayer 内读取显现进度：块开始显现只让 marker 图层更新，不触发重组。
    val visibility = Modifier.graphicsLayer {
        alpha = if (listMarkerVisible(reveal != null, item.markerRevealKey, reveal?.started?.value)) 1f else 0f
    }
    val markerModifier = visibility
        .padding(end = 8.dp)
        .widthIn(min = width)
    when {
        item.checked != null -> {
            val lineHeight = with(LocalDensity.current) { style.body.lineHeight.toDp() }
            Box(markerModifier.height(lineHeight), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (item.checked) MiuixTheme.colorScheme.primary else style.markerColor,
                )
            }
        }
        ordered -> Text(
            text = "${item.number}.",
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.End,
            modifier = markerModifier.alignByBaseline(),
        )
        else -> Text(
            text = BULLETS[depth % BULLETS.size],
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.Center,
            modifier = markerModifier.alignByBaseline(),
        )
    }
}

/**
 * 流式正文里 marker 与该项第一段文字同时出现；没有文字的空项在显现期间保持隐藏，
 * 避免先冒出孤立的圆点。
 */
internal fun listMarkerVisible(
    revealActive: Boolean,
    markerKey: RevealBlockKey?,
    started: Set<RevealBlockKey>?,
): Boolean = !revealActive || (markerKey != null && started?.contains(markerKey) == true)

private val BULLETS = listOf("•", "◦", "▪")

// ── 表格 ────────────────────────────────────────────────────────────────

/**
 * 列宽按内容自适应：每列取最宽单元格的自然宽度并限制在合理区间；总宽不足时按比例
 * 撑满可用宽度，超出时整体横向滚动。网格线与表头底色在绘制阶段按测量结果画出。
 */
@Composable
private fun MarkdownTableBlock(block: MarkdownTable, scope: MarkdownRenderScope) {
    val style = scope.style
    val bodyStyles = remember(style, block.alignments) {
        block.alignments.map { align -> style.table.copy(textAlign = align.textAlign) }
    }
    val headerStyles = remember(bodyStyles) {
        bodyStyles.map { it.copy(fontWeight = FontWeight.SemiBold) }
    }
    var grid by remember { mutableStateOf(TableGrid.Empty) }
    val lineColor = style.dividerColor
    val headerColor = style.tableHeaderBackground
    val columns = block.header.size
    val rows = remember(block) { listOf(block.header) + block.rows }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = constraints.maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Layout(
                modifier = Modifier
                    .squircleBorder(width = 0.5.dp, color = lineColor, cornerRadius = 12.dp)
                    .squircleClip(cornerRadius = 12.dp)
                    .drawBehind {
                        val current = grid
                        val headerHeight = current.rowHeights.firstOrNull() ?: return@drawBehind
                        drawRect(headerColor, size = Size(size.width, headerHeight.toFloat()))
                        val stroke = 0.5.dp.toPx()
                        var y = 0f
                        current.rowHeights.dropLast(1).forEach { height ->
                            y += height
                            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                        }
                        var x = 0f
                        current.columnWidths.dropLast(1).forEach { width ->
                            x += width
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                        }
                    },
                content = {
                    rows.forEachIndexed { rowIndex, row ->
                        row.forEachIndexed { column, cell ->
                            MarkdownText(
                                block = cell,
                                style = if (rowIndex == 0) headerStyles[column] else bodyStyles[column],
                                scope = scope,
                                // 单元格以固定宽度测量，不需要 fill；fill 会干扰列宽的固有尺寸查询。
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                },
            ) { measurables, _ ->
                val rowCount = measurables.size / columns
                val minWidth = TableMinColumnWidth.roundToPx()
                val maxWidth = TableMaxColumnWidth.roundToPx()
                val widths = IntArray(columns) { column ->
                    (0 until rowCount).maxOf { row ->
                        measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity)
                    }.coerceIn(minWidth, maxWidth)
                }
                val natural = widths.sum()
                if (natural in 1 until availableWidth) {
                    val extra = availableWidth - natural
                    var assigned = 0
                    for (column in 0 until columns) {
                        val share = if (column == columns - 1) {
                            extra - assigned
                        } else {
                            (extra.toLong() * widths[column] / natural).toInt()
                        }
                        widths[column] += share
                        assigned += share
                    }
                }
                val placeables = measurables.mapIndexed { index, measurable ->
                    measurable.measure(Constraints.fixedWidth(widths[index % columns]))
                }
                val heights = IntArray(rowCount) { row ->
                    (0 until columns).maxOf { column -> placeables[row * columns + column].height }
                }
                val next = TableGrid(widths.toList(), heights.toList())
                if (next != grid) grid = next
                layout(widths.sum(), heights.sum()) {
                    var y = 0
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (column in 0 until columns) {
                            placeables[row * columns + column].place(x, y)
                            x += widths[column]
                        }
                        y += heights[row]
                    }
                }
            }
        }
    }
}

/** 测量结果供绘制阶段画网格；只在绘制中读取，写入不会引起重组。 */
@Immutable
private data class TableGrid(val columnWidths: List<Int>, val rowHeights: List<Int>) {
    companion object {
        val Empty = TableGrid(emptyList(), emptyList())
    }
}

private val MarkdownColumnAlign.textAlign: TextAlign
    get() = when (this) {
        MarkdownColumnAlign.Start -> TextAlign.Start
        MarkdownColumnAlign.Center -> TextAlign.Center
        MarkdownColumnAlign.End -> TextAlign.End
    }

private val TableMinColumnWidth = 72.dp
private val TableMaxColumnWidth = 260.dp

// ── 图片 ────────────────────────────────────────────────────────────────

@Immutable
private data class LoadedMarkdownImage(
    val bitmap: ImageBitmap,
    val mimeType: String,
)

private fun readMarkdownImageBytes(dest: String): ByteArray? = when {
    dest.startsWith("file:", ignoreCase = true) -> {
        runCatching {
            File(java.net.URI(dest)).takeIf(File::isFile)?.readBytes()
        }.getOrNull() ?: android.net.Uri.parse(dest).path?.let(::File)?.takeIf(File::isFile)?.readBytes()
    }
    dest.startsWith("/") -> {
        File(dest).takeIf(File::isFile)?.readBytes()
    }
    dest.startsWith("data:image/", ignoreCase = true) -> {
        val b64 = dest.substringAfter("base64,", "")
        if (b64.isNotBlank()) {
            runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
        } else {
            null
        }
    }
    else -> null
}

@Composable
private fun MarkdownImageBlock(block: MarkdownImage, scope: MarkdownRenderScope) {
    val loadedState = produceState<LoadedMarkdownImage?>(initialValue = null, key1 = block.destination) {
        value = withContext(Dispatchers.IO) {
            val dest = block.destination
            val bytes = readMarkdownImageBytes(dest) ?: return@withContext null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            val outWidth = boundsOpts.outWidth
            val outHeight = boundsOpts.outHeight
            if (outWidth <= 0 || outHeight <= 0) return@withContext null

            var sampleSize = 1
            val maxDim = maxOf(outWidth, outHeight)
            while (maxDim / sampleSize > 2048) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.asImageBitmap()
                ?: return@withContext null

            val mime = boundsOpts.outMimeType?.takeIf(String::isNotBlank) ?: when {
                dest.startsWith("data:image/", ignoreCase = true) -> {
                    dest.substringAfter("data:").substringBefore(";", "image/png").trim()
                }
                dest.endsWith(".png", ignoreCase = true) -> "image/png"
                dest.endsWith(".webp", ignoreCase = true) -> "image/webp"
                dest.endsWith(".gif", ignoreCase = true) -> "image/gif"
                else -> "image/jpeg"
            }
            LoadedMarkdownImage(bitmap = bitmap, mimeType = mime)
        }
    }

    val loaded = loadedState.value
    if (loaded == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(scope.style.codeBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = block.alt.ifBlank { "图片加载中…" },
                style = scope.style.body,
                color = scope.style.secondaryColor,
            )
        }
        return
    }

    var showFullscreen by remember { mutableStateOf(false) }
    val rawRatio = if (loaded.bitmap.height > 0) {
        loaded.bitmap.width.toFloat() / loaded.bitmap.height.toFloat()
    } else {
        1f
    }
    val aspectRatio = rawRatio.coerceIn(0.4f, 2.5f)

    Box(
        modifier = Modifier
            .heightIn(max = 360.dp)
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(16.dp))
            .clickable { showFullscreen = true },
    ) {
        Image(
            bitmap = loaded.bitmap,
            contentDescription = block.alt.ifBlank { null },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showFullscreen) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }

        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f)),
            ) {
                Image(
                    bitmap = loaded.bitmap,
                    contentDescription = block.alt.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 80.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { showFullscreen = false },
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EtaTextButton(
                        text = "关闭",
                        onClick = { showFullscreen = false },
                        modifier = Modifier.weight(1f),
                    )
                    EtaTextButton(
                        text = if (saving) "保存中…" else "保存到相册",
                        enabled = !saving,
                        onClick = {
                            saving = true
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    saveImageToGallery(context, block.destination, loaded.mimeType)
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

private suspend fun saveImageToGallery(context: Context, destination: String, mimeType: String) {
    try {
        val bytes = readMarkdownImageBytes(destination)
        if (bytes == null || bytes.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val resolver = context.contentResolver
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val filename = "IMG_${System.currentTimeMillis()}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Eta")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val etaDir = File(picturesDir, "Eta").apply { mkdirs() }
                val targetFile = File(etaDir, filename)
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "保存失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }
}
