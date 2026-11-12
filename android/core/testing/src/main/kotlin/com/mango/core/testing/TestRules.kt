package com.mango.core.testing

import com.mango.core.common.DispatcherProvider
import com.mango.core.common.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.atomic.AtomicLong

/**
 * Правило подмены главного диспетчера.
 *
 * Без него любой ViewModel-тест падает на `Dispatchers.Main`: в JVM-тестах
 * главного Looper-а не существует. Правило также гарантирует сброс после
 * теста — иначе утечка состояния делает следующий тест нестабильным.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Диспетчеры для тестов: все три направлены на один тестовый диспетчер,
 * чтобы порядок выполнения был детерминированным.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : DispatcherProvider {
    override val main = dispatcher
    override val io = dispatcher
    override val default = dispatcher
}

/**
 * Предсказуемый генератор идентификаторов.
 *
 * Выдаёт возрастающие значения вида `test-id-0001`. Нужен там, где тест
 * проверяет сам идентификатор: со случайным UUID такое утверждение написать
 * нельзя, а подменять генератор по месту — значит дублировать код.
 */
class FakeIdGenerator(private val prefix: String = "test-id") : IdGenerator {
    private val counter = AtomicLong(0)

    override fun newId(): String =
        "%s-%04d".format(prefix, counter.incrementAndGet())

    /** Идентификатор, который будет выдан следующим вызовом. */
    fun peekNext(): String = "%s-%04d".format(prefix, counter.get() + 1)

    fun reset() = counter.set(0)
}
