package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingGfmParserTest {
    @Test
    fun headingIsParsedAsHeadingFromFirstStreamingSnapshot() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "## 标题",
            isComplete = false,
        )

        assertEquals("## 标题", snapshot.renderedSource)
        val heading = snapshot.document.blocks.single() as MarkdownHeading
        assertEquals(2, heading.level)
        assertEquals("标题", heading.text.text)
    }

    @Test
    fun tableHeaderIsBufferedUntilDelimiterConfirmsTheBlock() {
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| 指标 | 数值 |",
                isComplete = false,
            ),
        )
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| 指标 | 数值 |\n| --",
                isComplete = false,
            ),
        )

        val confirmed = "| 指标 | 数值 |\n| --- | --- |"
        val snapshot = StreamingGfmParserSession().parse(
            source = confirmed,
            isComplete = false,
        )

        assertEquals(confirmed, snapshot.renderedSource)
        val table = snapshot.document.blocks.single() as MarkdownTable
        assertEquals(listOf("指标", "数值"), table.header.map { cell -> cell.text.text })
    }

    @Test
    fun ordinaryPipeTextIsReleasedWhenNextLineCannotBeATableDelimiter() {
        val source = "请选择 A | B\n这不是分隔行"

        assertEquals(
            source,
            StreamingGfmProjection.project(source = source, isComplete = false),
        )
    }

    @Test
    fun incompleteStrongDelimiterUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "说明 **压力",
            isComplete = false,
        )

        assertEquals("说明 **压力**", snapshot.renderedSource)
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.hasSpanCovering("压力") { it.fontWeight == FontWeight.SemiBold })
    }

    @Test
    fun incompleteInlineCodeUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "执行 `adb shell",
            isComplete = false,
        )

        assertEquals("执行 `adb shell`", snapshot.renderedSource)
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.hasSpanCovering("adb shell") { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun incompleteLinkIsNotPublishedAsRawMarkdown() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "参考 [官方文档](https://example.com/do",
            isComplete = false,
        )

        assertEquals("参考 ", snapshot.renderedSource)
        assertFalse(snapshot.renderedSource.contains('['))
    }

    @Test
    fun openFenceRendersAsCodeWithoutChangingStoredSource() {
        val source = "```kotlin\nval answer = 42"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = false,
        )

        assertEquals(source, snapshot.originalSource)
        assertTrue(snapshot.renderedSource.endsWith("\n```"))
        val code = snapshot.document.blocks.single() as MarkdownCode
        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.text.text)
    }

    @Test
    fun completionParsesExactOriginalSourceWithoutVirtualCharacters() {
        val source = "未闭合 **标记"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        assertEquals(source, snapshot.renderedSource)
        assertTrue(snapshot.isComplete)
    }

    @Test
    fun stableHandoffKeepsTheCompletedDocumentAndFormattedBlocks() {
        val parser = StreamingGfmParserSession()
        parser.parse("## 结果\n\n```kotlin\nval answer = 42", isComplete = false)
        val content = "## 结果\n\n```kotlin\nval answer = 42\n```\n\n**完成**"
        val snapshot = parser.parse(content, isComplete = true)
        val stableDocument = snapshot.completedDocumentFor(content)

        assertNotNull(stableDocument)
        assertSame(snapshot.document, stableDocument)
        val blocks = stableDocument!!.blocks
        assertTrue(blocks.any { it is MarkdownHeading && it.level == 2 })
        assertTrue(blocks.any { it is MarkdownCode && it.language == "kotlin" })
        val completion = blocks.filterIsInstance<MarkdownParagraph>().single()
        assertTrue(completion.text.hasSpanCovering("完成") { it.fontWeight == FontWeight.SemiBold })
    }

    @Test
    fun unfinishedOrOutdatedSnapshotCannotBeUsedForStableHandoff() {
        val parser = StreamingGfmParserSession()
        val content = "回复内容"

        assertNull(parser.parse(content, isComplete = false).completedDocumentFor(content))
        val completed = parser.parse(content, isComplete = true)
        assertNull(completed.completedDocumentFor("回复内容和新补充"))
        assertNull(completed.completedDocumentFor("修正后的内容"))
    }

    @Test
    fun completedSnapshotIncludesReferenceLinksBeforeStableHandoff() {
        val content = "查看 [文档][eta]\n\n[eta]: https://example.com/docs"
        val snapshot = StreamingGfmParserSession().parse(content, isComplete = true)

        // 链接定义不产生块；完成快照里引用已解析为可点的 Url 注解。
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertEquals("查看 文档", paragraph.text.text)
        assertEquals(listOf("https://example.com/docs"), paragraph.text.linkUrls())
    }

    @Test
    fun correctedSnapshotDoesNotRetainOrMutatePreviousLinkDefinitions() {
        val parser = StreamingGfmParserSession()
        val original = parser.parse("[文档][eta]\n\n[eta]: https://example.com/docs", isComplete = true)
        val replacement = parser.parse("[文档][eta]", isComplete = true)

        // 没有对应定义的引用按原文保留，不携带链接注解。
        val replacementParagraph = replacement.document.blocks.single() as MarkdownParagraph
        assertEquals("[文档][eta]", replacementParagraph.text.text)
        assertTrue(replacementParagraph.text.linkUrls().isEmpty())

        val originalParagraph = original.document.blocks.single() as MarkdownParagraph
        assertEquals(listOf("https://example.com/docs"), originalParagraph.text.linkUrls())
    }

    @Test
    fun standaloneImageParsesIntoMarkdownImageBlock() {
        val source = "![Generated Image](file:///tmp/img-123.jpg)"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        val block = snapshot.document.blocks.single() as MarkdownImage
        assertEquals("Generated Image", block.alt)
        assertEquals("file:///tmp/img-123.jpg", block.destination)
        assertEquals(0, block.offset)
    }

    @Test
    fun paragraphWithTextFollowedByImageSplitsIntoParagraphAndImage() {
        val source = "说明文字 ![Generated Image](file:///tmp/img-123.jpg)"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        val blocks = snapshot.document.blocks
        assertEquals(2, blocks.size)
        val paragraph = blocks[0] as MarkdownParagraph
        assertEquals("说明文字", paragraph.text.text)
        val image = blocks[1] as MarkdownImage
        assertEquals("Generated Image", image.alt)
        assertEquals("file:///tmp/img-123.jpg", image.destination)
    }

    @Test
    fun imageFollowedByTextSplitsIntoImageAndParagraph() {
        val source = "![Generated Image](file:///tmp/img-123.jpg)\n后续文字"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        val blocks = snapshot.document.blocks
        assertEquals(2, blocks.size)
        val image = blocks[0] as MarkdownImage
        assertEquals("Generated Image", image.alt)
        assertEquals("file:///tmp/img-123.jpg", image.destination)
        val paragraph = blocks[1] as MarkdownParagraph
        assertEquals("后续文字", paragraph.text.text)
    }

    @Test
    fun dataUriAndAbsolutePathImagesAreRecognizedAsMarkdownImageBlocks() {
        val dataUri = "![Base64](data:image/png;base64,iVBORw0KGgo=)"
        val snapData = StreamingGfmParserSession().parse(dataUri, isComplete = true)
        val imgData = snapData.document.blocks.single() as MarkdownImage
        assertEquals("Base64", imgData.alt)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", imgData.destination)

        val absPath = "![Local](/data/user/0/img.jpg)"
        val snapAbs = StreamingGfmParserSession().parse(absPath, isComplete = true)
        val imgAbs = snapAbs.document.blocks.single() as MarkdownImage
        assertEquals("Local", imgAbs.alt)
        assertEquals("/data/user/0/img.jpg", imgAbs.destination)
    }

    @Test
    fun remoteHttpImageRemainsInlineParagraph() {
        val source = "![Remote Image](https://example.com/test.jpg)"
        val snapshot = StreamingGfmParserSession().parse(source, isComplete = true)
        val block = snapshot.document.blocks.single()
        assertTrue(block is MarkdownParagraph)
        assertEquals("Remote Image", (block as MarkdownParagraph).text.text)
    }

    @Test
    fun inlineImageWithBlankAltAndLocalDestinationUsesGeneratedImageFallback() {
        // 在表格单元格中，图片作为行内节点处理
        val source = "| 图示 |\n| --- |\n| ![](file:///tmp/pic.jpg) |"
        val snapshot = StreamingGfmParserSession().parse(source, isComplete = true)
        val table = snapshot.document.blocks.single() as MarkdownTable
        val cellText = table.rows.single().single().text.text
        assertEquals("Generated Image", cellText)
    }

    @Test
    fun boundedMarkdownPlaceholderTextCapsStringsExceedingLimit() {
        val shortText = "短文本"
        assertEquals(shortText, boundedMarkdownPlaceholderText(shortText))

        val exactText = "a".repeat(MAX_PLACEHOLDER_TEXT_CHARS)
        assertEquals(exactText, boundedMarkdownPlaceholderText(exactText))

        val longText = "a".repeat(MAX_PLACEHOLDER_TEXT_CHARS + 500)
        val bounded = boundedMarkdownPlaceholderText(longText)
        assertEquals(MAX_PLACEHOLDER_TEXT_CHARS, bounded.length)
        assertEquals(longText.take(MAX_PLACEHOLDER_TEXT_CHARS), bounded)
    }

    private fun AnnotatedString.hasSpanCovering(
        substring: String,
        predicate: (SpanStyle) -> Boolean,
    ): Boolean {
        val start = text.indexOf(substring)
        if (start < 0) return false
        val end = start + substring.length
        return spanStyles.any { range ->
            range.start <= start && range.end >= end && predicate(range.item)
        }
    }

    private fun AnnotatedString.linkUrls(): List<String> =
        getLinkAnnotations(0, length).mapNotNull { range -> (range.item as? LinkAnnotation.Url)?.url }
}
