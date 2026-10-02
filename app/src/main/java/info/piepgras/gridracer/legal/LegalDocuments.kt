package info.piepgras.gridracer.legal

/**
 * Asset paths under app/src/main/assets/legal/, which is generated (not hand-edited) from the
 * Markdown sources in the top-level legal/ directory by scripts/render_legal_docs.py - see that
 * script for why the Markdown subset [parseLegalMarkdown] understands is kept in sync.
 * Both documents are embedded inline in the About screen (see AboutScreen.kt); permissions.md
 * remains the canonical hosted text but is presented in-app as the structured PermissionsScreen
 * instead of raw prose.
 */
const val PRIVACY_POLICY_ASSET = "legal/privacy-policy.md"
/** Asset path of the terms of service; see [PRIVACY_POLICY_ASSET]. */
const val TERMS_OF_SERVICE_ASSET = "legal/terms-of-service.md"

/**
 * One block of a legal document as [parseLegalMarkdown] reads it, for the About screen to render.
 */
sealed interface LegalBlock {
    /**
     * A `#` heading - the document title.
     *
     * @property text The heading, inline formatting stripped.
     */
    data class Heading1(val text: String) : LegalBlock
    /**
     * A `##` section heading.
     *
     * @property text The heading, inline formatting stripped.
     */
    data class Heading2(val text: String) : LegalBlock
    /**
     * A `###` subsection heading.
     *
     * @property text The heading, inline formatting stripped.
     */
    data class Heading3(val text: String) : LegalBlock
    /**
     * A paragraph, its source lines joined with spaces.
     *
     * @property text The paragraph, inline formatting stripped.
     */
    data class Paragraph(val text: String) : LegalBlock
    /**
     * A run of `- ` bullets.
     *
     * @property items One entry per bullet, continuation lines joined, inline formatting stripped.
     */
    data class BulletList(val items: List<String>) : LegalBlock
}

/** Strips inline Markdown formatting (`code`, **bold**, *italics*) from text. */
private fun stripInline(text: String): String =
    text
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")

/** Parses "#"/"##"/"###" headings, blank-line paragraphs, "- " bullets (with
 *  continuation lines), and strips inline `code`/`**bold**`/`*italic*` markers.
 *  Matches the subset that scripts/render_legal_docs.py renders to HTML. */
fun parseLegalMarkdown(markdown: String): List<LegalBlock> {
    val blocks = mutableListOf<LegalBlock>()
    val lines = markdown.lines()
    val paragraph = mutableListOf<String>()
    val bullets = mutableListOf<String>()
    var i = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += LegalBlock.Paragraph(stripInline(paragraph.joinToString(" ")))
            paragraph.clear()
        }
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            blocks += LegalBlock.BulletList(bullets.map { stripInline(it) })
            bullets.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("### ") -> {
                flushParagraph(); flushBullets()
                blocks += LegalBlock.Heading3(stripInline(trimmed.removePrefix("### ")))
            }
            trimmed.startsWith("## ") -> {
                flushParagraph(); flushBullets()
                blocks += LegalBlock.Heading2(stripInline(trimmed.removePrefix("## ")))
            }
            trimmed.startsWith("# ") -> {
                flushParagraph(); flushBullets()
                blocks += LegalBlock.Heading1(stripInline(trimmed.removePrefix("# ")))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                val first = trimmed.substring(2)
                val item = mutableListOf(first)
                i++
                while (i < lines.size && (lines[i].startsWith("  ") || lines[i].startsWith("\t")) && lines[i].isNotBlank()) {
                    item.add(lines[i].trim())
                    i++
                }
                bullets += item.joinToString(" ")
                continue
            }
            trimmed.isEmpty() -> {
                flushParagraph(); flushBullets()
            }
            else -> paragraph += trimmed
        }
        i++
    }
    flushParagraph()
    flushBullets()
    return blocks
}
