package com.mango.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Провайдер диспетчеров.
 *
 * Прямое обращение к `Dispatchers.IO` в репозиториях делает их
 * непроверяемыми: тест не может подменить планировщик и вынужден
 * ждать реального времени. Поэтому диспетчеры инжектируются.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
