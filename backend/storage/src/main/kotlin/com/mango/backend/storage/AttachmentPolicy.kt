package com.mango.backend.storage

/**
 * Какие файлы принимаются и как проверяется, что файл — тот, за кого себя выдаёт.
 *
 * Список разрешённых типов — белый: всё, чего в нём нет, отклоняется.
 * В частности, отсутствуют SVG и HTML — они исполняют скрипты при
 * открытии в браузере.
 *
 * Проверка содержимого — по сигнатуре (первым байтам файла). Это не
 * антивирус: она ловит переименованный исполняемый файл или HTML,
 * выданный за картинку, но не вредоносный PDF. Ограничение честно
 * описано в ADR-0019.
 */
object AttachmentPolicy {

    enum class Kind { IMAGE, VIDEO, AUDIO, DOCUMENT }

    class Rule(
        val mimeType: String,
        val kind: Kind,
        /** Особый предел размера; `null` — общий из конфигурации. */
        val maxBytes: Long?,
        /** Можно ли показывать прямо в приложении, а не только скачивать. */
        val inline: Boolean,
        val matches: (ByteArray) -> Boolean,
    )

    private const val IMAGE_MAX = 20L * 1024 * 1024

    private val rules: Map<String, Rule> = listOf(
        Rule("image/jpeg", Kind.IMAGE, IMAGE_MAX, inline = true) { it.startsWith(0xFF, 0xD8, 0xFF) },
        Rule("image/png", Kind.IMAGE, IMAGE_MAX, inline = true) {
            it.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        },
        Rule("image/gif", Kind.IMAGE, IMAGE_MAX, inline = true) {
            it.startsWithAscii("GIF87a") || it.startsWithAscii("GIF89a")
        },
        Rule("image/webp", Kind.IMAGE, IMAGE_MAX, inline = true) {
            it.startsWithAscii("RIFF") && it.asciiAt(8, "WEBP")
        },
        Rule("image/heic", Kind.IMAGE, IMAGE_MAX, inline = false) {
            it.isoBrand() in setOf("heic", "heix", "mif1", "msf1", "hevc")
        },

        Rule("video/mp4", Kind.VIDEO, null, inline = false) { it.isoBrand() != null },
        Rule("video/webm", Kind.VIDEO, null, inline = false) { it.startsWith(0x1A, 0x45, 0xDF, 0xA3) },

        Rule("audio/ogg", Kind.AUDIO, null, inline = false) { it.startsWithAscii("OggS") },
        Rule("audio/mpeg", Kind.AUDIO, null, inline = false) {
            it.startsWithAscii("ID3") || (it.size >= 2 && it.u(0) == 0xFF && (it.u(1) and 0xE0) == 0xE0)
        },
        Rule("audio/mp4", Kind.AUDIO, null, inline = false) { it.isoBrand() != null },
        Rule("audio/aac", Kind.AUDIO, null, inline = false) {
            it.size >= 2 && it.u(0) == 0xFF && (it.u(1) and 0xF6) == 0xF0
        },
        Rule("audio/webm", Kind.AUDIO, null, inline = false) { it.startsWith(0x1A, 0x45, 0xDF, 0xA3) },

        Rule("application/pdf", Kind.DOCUMENT, null, inline = false) { it.startsWithAscii("%PDF-") },
        Rule("application/zip", Kind.DOCUMENT, null, inline = false, matches = ::isZip),
        // Документы Office — это zip-архивы; содержимое внутри не разбирается.
        Rule(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Kind.DOCUMENT, null, inline = false, matches = ::isZip,
        ),
        Rule(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            Kind.DOCUMENT, null, inline = false, matches = ::isZip,
        ),
        Rule(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            Kind.DOCUMENT, null, inline = false, matches = ::isZip,
        ),
        Rule("text/plain", Kind.DOCUMENT, null, inline = false, matches = ::looksLikeText),
    ).associateBy { it.mimeType }

    /** Правило для типа или `null`, если тип не разрешён. Параметры (`; charset=`) отбрасываются. */
    fun ruleFor(mimeType: String): Rule? = rules[normalize(mimeType)]

    fun normalize(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

    val allowedTypes: Set<String> get() = rules.keys

    // ───────────────────────── сигнатуры ─────────────────────────

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { u(it) == bytes[it] }

    private fun ByteArray.asciiAt(offset: Int, text: String): Boolean =
        size >= offset + text.length && text.indices.all { u(offset + it) == text[it].code }

    private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)

    /** Марка контейнера ISO BMFF (MP4, M4A, HEIC): `....ftypXXXX`. */
    private fun ByteArray.isoBrand(): String? =
        if (asciiAt(4, "ftyp") && size >= 12) String(copyOfRange(8, 12), Charsets.US_ASCII).trim() else null

    private fun isZip(head: ByteArray): Boolean = head.startsWith(0x50, 0x4B, 0x03, 0x04)

    /**
     * Текст: нет нулевых байтов и начало — корректный UTF-8.
     *
     * Последние байты могут оказаться обрезанной серединой символа:
     * проверяется лишь начало файла, поэтому неполный хвост допустим.
     */
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        if (head.any { it.toInt() == 0 }) return false
        val decoder = Charsets.UTF_8.newDecoder()
        val trimmed = (0..minOf(3, head.size - 1)).asSequence()
            .map { head.copyOf(head.size - it) }
            .firstOrNull { candidate ->
                runCatching { decoder.reset().decode(java.nio.ByteBuffer.wrap(candidate)) }.isSuccess
            }
        return trimmed != null && head.size - trimmed.size <= 3
    }
}
