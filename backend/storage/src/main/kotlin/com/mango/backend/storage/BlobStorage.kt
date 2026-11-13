package com.mango.backend.storage

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Результат записи: сколько байт пришло, их хеш и начало файла для проверки типа. */
class StoredBlob(
    val sizeBytes: Long,
    val sha256Hex: String,
    val head: ByteArray,
)

/** Поток оказался длиннее допустимого. Ничего не сохранено. */
class BlobTooLargeException(val limit: Long) : RuntimeException("Файл больше $limit байт")

/**
 * Хранилище содержимого вложений.
 *
 * Интерфейс намеренно минимален: запись потока с ограничением размера,
 * чтение, удаление. Этого достаточно и для каталога на диске, и для
 * S3-совместимого хранилища (ADR-0019) — переход меняет реализацию,
 * но не сервис и не клиентов.
 *
 * Все методы блокирующие и вызываются на диспетчере ввода-вывода.
 */
interface BlobStorage {

    /**
     * Сохраняет поток под ключом.
     *
     * Запись атомарна: читатель видит либо прежнее содержимое, либо
     * новое целиком, но не половину файла. Если поток длиннее
     * [maxBytes], ничего не сохраняется и бросается [BlobTooLargeException].
     */
    fun write(key: String, input: InputStream, maxBytes: Long): StoredBlob

    /** Поток содержимого или `null`, если объекта нет. Закрывает вызывающий. */
    fun open(key: String): InputStream?

    fun delete(key: String)
}

/**
 * Хранилище в каталоге на диске.
 *
 * Подходит для одного экземпляра сервера и для разработки. При
 * нескольких экземплярах каталог должен быть общим томом, а лучше —
 * заменён на S3-совместимую реализацию.
 */
class FileSystemBlobStorage(private val root: Path) : BlobStorage {

    init {
        Files.createDirectories(root)
    }

    override fun write(key: String, input: InputStream, maxBytes: Long): StoredBlob {
        val target = resolve(key)
        Files.createDirectories(target.parent)

        // Временный файл рядом с целевым: перемещение в пределах одного
        // каталога атомарно, и наполовину записанный файл никогда не
        // окажется под рабочим именем.
        val temp = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(HEAD_SIZE)
        var headLength = 0
        var total = 0L

        try {
            Files.newOutputStream(temp).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    // Проверка на каждом блоке, а не после чтения: иначе
                    // клиент, объявивший килобайт, мог бы залить гигабайт
                    // и занять диск до того, как размер был бы проверен.
                    if (total > maxBytes) throw BlobTooLargeException(maxBytes)
                    if (headLength < HEAD_SIZE) {
                        val take = minOf(read, HEAD_SIZE - headLength)
                        System.arraycopy(buffer, 0, head, headLength, take)
                        headLength += take
                    }
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }

        return StoredBlob(
            sizeBytes = total,
            sha256Hex = HexFormat.of().formatHex(digest.digest()),
            head = head.copyOf(headLength),
        )
    }

    override fun open(key: String): InputStream? {
        val path = resolve(key)
        return if (Files.isRegularFile(path)) Files.newInputStream(path) else null
    }

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    /**
     * Ключ → путь внутри корня.
     *
     * Ключи формирует сервер, но проверка всё равно строгая: ошибка
     * в формировании ключа не должна превращаться в запись за пределы
     * каталога хранилища.
     */
    private fun resolve(key: String): Path {
        require(KEY_PATTERN.matches(key) && ".." !in key) { "Недопустимый ключ хранилища" }
        val path = root.resolve(key).normalize()
        require(path.startsWith(root.normalize())) { "Ключ выходит за пределы хранилища" }
        return path
    }

    private companion object {
        const val HEAD_SIZE = 64
        const val BUFFER_SIZE = 64 * 1024
        val KEY_PATTERN = Regex("^[a-z0-9]+(/[a-z0-9-]+)+$")
    }
}
