package com.mango.backend.auth

import com.mango.backend.common.PasswordConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {

    /**
     * Стоимость намеренно снижена: эти тесты проверяют логику, а не
     * подбор параметров. С боевыми 64 МБ каждый вызов занимал бы
     * десятые доли секунды, и набор тестов стал бы неприятно медленным.
     */
    private val hasher = PasswordHasher(
        PasswordConfig(memoryKib = 1024, iterations = 1, parallelism = 1),
    )

    @Test
    fun `верный пароль проходит проверку`() {
        val encoded = hasher.hash("Правильный-Пароль-123")
        assertTrue(hasher.verify("Правильный-Пароль-123", encoded))
    }

    @Test
    fun `неверный пароль не проходит`() {
        val encoded = hasher.hash("Правильный-Пароль-123")
        assertFalse(hasher.verify("Правильный-Пароль-124", encoded))
        assertFalse(hasher.verify("", encoded))
        assertFalse(hasher.verify("ПРАВИЛЬНЫЙ-ПАРОЛЬ-123", encoded))
    }

    @Test
    fun `одинаковые пароли дают разные хеши`() {
        val first = hasher.hash("одинаковый")
        val second = hasher.hash("одинаковый")

        // Случайная соль обязательна: без неё одинаковые пароли видны
        // в дампе базы, и взлом одного вскрывает всех остальных.
        assertNotEquals(first, second)
        assertTrue(hasher.verify("одинаковый", first))
        assertTrue(hasher.verify("одинаковый", second))
    }

    @Test
    fun `формат хранения соответствует PHC`() {
        val encoded = hasher.hash("пароль")
        val parts = encoded.split('$')

        assertEquals(6, parts.size, "Ожидается \$argon2id\$v=\$params\$salt\$hash, получено: $encoded")
        assertEquals("", parts[0])
        assertEquals("argon2id", parts[1])
        assertEquals("v=19", parts[2])
        assertTrue(parts[3].contains("m=1024"), parts[3])
        assertTrue(parts[3].contains("t=1"), parts[3])
        assertTrue(parts[3].contains("p=1"), parts[3])
    }

    @Test
    fun `пароль не восстанавливается из хеша`() {
        val password = "СекретнаяФраза-42"
        val encoded = hasher.hash(password)

        assertFalse(encoded.contains(password))
        assertFalse(encoded.contains("СекретнаяФраза"))
    }

    @Test
    fun `повреждённый хеш трактуется как несовпадение, а не как ошибка`() {
        // Это важно: исключение здесь превратилось бы в 500, по которому
        // можно отличить существующую учётную запись от отсутствующей.
        assertFalse(hasher.verify("пароль", "мусор"))
        assertFalse(hasher.verify("пароль", ""))
        assertFalse(hasher.verify("пароль", "\$argon2id\$v=19\$m=1024,t=1,p=1\$короткая"))
        assertFalse(hasher.verify("пароль", "\$bcrypt\$v=19\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA"))
        assertFalse(hasher.verify("пароль", "\$argon2id\$v=19\$m=плохо,t=1,p=1\$c2FsdA\$aGFzaA"))
    }

    @Test
    fun `проверка идёт по параметрам из самого хеша, а не из конфигурации`() {
        val weak = PasswordHasher(PasswordConfig(memoryKib = 1024, iterations = 1, parallelism = 1))
        val encoded = weak.hash("пароль")

        // Рост стоимости не должен обесценивать уже сохранённые пароли.
        val stronger = PasswordHasher(PasswordConfig(memoryKib = 4096, iterations = 3, parallelism = 1))
        assertTrue(stronger.verify("пароль", encoded))
    }

    @Test
    fun `повышение стоимости требует перехеширования`() {
        val weak = PasswordHasher(PasswordConfig(memoryKib = 1024, iterations = 1, parallelism = 1))
        val encoded = weak.hash("пароль")

        assertFalse(weak.needsRehash(encoded))

        val stronger = PasswordHasher(PasswordConfig(memoryKib = 4096, iterations = 3, parallelism = 1))
        assertTrue(stronger.needsRehash(encoded))
    }

    @Test
    fun `нераспознанный хеш считается требующим перехеширования`() {
        assertTrue(hasher.needsRehash("мусор"))
    }

    @Test
    fun `длинный пароль с юникодом обрабатывается корректно`() {
        val password = "пароль-с-эмодзи-🔐-и-中文-" + "x".repeat(100)
        val encoded = hasher.hash(password)

        assertTrue(hasher.verify(password, encoded))
        assertFalse(hasher.verify(password.dropLast(1), encoded))
    }
}
