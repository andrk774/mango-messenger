package com.mango.backend.auth

import com.mango.backend.common.PasswordConfig
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Хеширование паролей алгоритмом Argon2id.
 *
 * Почему Argon2id, а не bcrypt или PBKDF2: он требует настраиваемого объёма
 * памяти, а память — единственный ресурс, который плохо масштабируется на
 * GPU и ASIC. Перебор bcrypt на видеокарте дешевле на порядки.
 * Вариант «id» сочетает стойкость к атакам по сторонним каналам (2i)
 * и к перебору с выделением памяти под требование (2d).
 *
 * Формат хранения — PHC-строка:
 * `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>`
 *
 * Параметры хранятся ВНУТРИ строки, а не берутся из конфигурации при
 * проверке. Иначе увеличение стоимости хеширования разом сделало бы
 * недействительными все существующие пароли.
 */
class PasswordHasher(
    private val config: PasswordConfig,
    private val random: SecureRandom = SecureRandom(),
) {

    fun hash(password: CharArray): String {
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val parameters = buildParameters(
            salt = salt,
            memoryKib = config.memoryKib,
            iterations = config.iterations,
            parallelism = config.parallelism,
        )
        val hash = derive(password, parameters)

        return buildString {
            append("\$argon2id\$v=")
            append(Argon2Parameters.ARGON2_VERSION_13)
            append("\$m=").append(config.memoryKib)
            append(",t=").append(config.iterations)
            append(",p=").append(config.parallelism)
            append('$').append(ENCODER.encodeToString(salt))
            append('$').append(ENCODER.encodeToString(hash))
        }
    }

    fun hash(password: String): String = hash(password.toCharArray())

    /**
     * Проверка пароля.
     *
     * Повреждённая или нераспознанная строка хеша трактуется как
     * несовпадение, а не как исключение: иначе испорченная запись в БД
     * превращалась бы в 500-ю ошибку, по которой можно отличить
     * существующий аккаунт от несуществующего.
     */
    fun verify(password: CharArray, encoded: String): Boolean {
        val parsed = parse(encoded) ?: return false
        val computed = derive(password, parsed.parameters)
        // Сравнение за постоянное время: обычное equals раскрывает
        // по времени, сколько первых байт совпало.
        return MessageDigest.isEqual(computed, parsed.hash)
    }

    fun verify(password: String, encoded: String): Boolean =
        verify(password.toCharArray(), encoded)

    /**
     * Нужно ли перехешировать пароль при следующем успешном входе,
     * потому что текущие параметры стоимости выросли.
     */
    fun needsRehash(encoded: String): Boolean {
        val parsed = parse(encoded) ?: return true
        return parsed.memoryKib < config.memoryKib ||
            parsed.iterations < config.iterations ||
            parsed.parallelism != config.parallelism
    }

    private fun derive(password: CharArray, parameters: Argon2Parameters): ByteArray {
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val output = ByteArray(HASH_LENGTH)
        generator.generateBytes(password, output)
        return output
    }

    private fun buildParameters(
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
    ): Argon2Parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withMemoryAsKB(memoryKib)
        .withIterations(iterations)
        .withParallelism(parallelism)
        .withSalt(salt)
        .build()

    private fun parse(encoded: String): ParsedHash? {
        // $argon2id$v=19$m=65536,t=3,p=1$salt$hash
        val parts = encoded.split('$')
        if (parts.size != 6) return null
        if (parts[1] != "argon2id") return null

        val version = parts[2].removePrefix("v=").toIntOrNull() ?: return null

        val costs = parts[3].split(',').mapNotNull { entry ->
            val (key, value) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            value.toIntOrNull()?.let { key to it }
        }.toMap()

        val memoryKib = costs["m"] ?: return null
        val iterations = costs["t"] ?: return null
        val parallelism = costs["p"] ?: return null

        val salt = runCatching { DECODER.decode(parts[4]) }.getOrNull() ?: return null
        val hash = runCatching { DECODER.decode(parts[5]) }.getOrNull() ?: return null

        return ParsedHash(
            parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(version)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build(),
            hash = hash,
            memoryKib = memoryKib,
            iterations = iterations,
            parallelism = parallelism,
        )
    }

    private class ParsedHash(
        val parameters: Argon2Parameters,
        val hash: ByteArray,
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    )

    private companion object {
        const val SALT_LENGTH = 16
        const val HASH_LENGTH = 32
        val ENCODER: Base64.Encoder = Base64.getEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getDecoder()
    }
}
