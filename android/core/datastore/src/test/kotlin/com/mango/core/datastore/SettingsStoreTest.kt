package com.mango.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Тесты пользовательских настроек.
 *
 * Каждый тест получает свой файл DataStore: общее хранилище сделало бы тесты
 * зависимыми друг от друга — именно на этом тест и поймал исходную
 * реализацию, которая создавала DataStore внутри класса через делегат
 * с кэшированием по имени.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * Открывает хранилище, выполняет блок и корректно закрывает его.
     *
     * DataStore запрещает два активных экземпляра на один файл, поэтому
     * «перезапуск приложения» в тесте обязан начинаться с закрытия
     * предыдущего экземпляра — иначе падает с IllegalStateException.
     */
    private suspend fun <T> withSettings(
        file: File,
        block: suspend (SettingsStore, DataStore<Preferences>) -> T,
    ): T {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return try {
            block(SettingsStore(dataStore), dataStore)
        } finally {
            scope.cancel()
        }
    }

    private fun file(name: String) = temporaryFolder.root.resolve("$name.preferences_pb")

    @Test
    fun `defaults are safe for privacy`() = runTest {
        withSettings(file("defaults")) { store, _ ->
            val settings = store.settings.first()

            assertEquals(ThemeMode.SYSTEM, settings.themeMode)
            assertTrue(settings.dynamicColorEnabled)
            assertTrue(settings.messageNotificationsEnabled)
            assertTrue(settings.taskNotificationsEnabled)
            assertFalse(
                "Текст сообщения на экране блокировки должен включаться осознанно",
                settings.showMessagePreviewInNotification,
            )
            assertTrue(
                "Медиа по умолчанию только по Wi-Fi — экономия трафика пользователя",
                settings.autoDownloadMediaOnWifiOnly,
            )
            assertTrue(settings.sendReadReceipts)
            assertTrue(settings.sendTypingIndicator)
        }
    }

    @Test
    fun `theme mode survives restart of the application`() = runTest {
        val target = file("theme")

        withSettings(target) { store, _ -> store.setThemeMode(ThemeMode.DARK) }

        // Первый экземпляр закрыт — это и есть перезапуск приложения.
        withSettings(target) { store, _ ->
            assertEquals(ThemeMode.DARK, store.settings.first().themeMode)
        }
    }

    @Test
    fun `privacy toggles do not affect notification settings`() = runTest {
        withSettings(file("privacy")) { store, _ ->
            store.setSendReadReceipts(false)
            store.setSendTypingIndicator(false)

            val settings = store.settings.first()
            assertFalse(settings.sendReadReceipts)
            assertFalse(settings.sendTypingIndicator)
            assertTrue(
                "Остальные настройки не должны пострадать",
                settings.messageNotificationsEnabled,
            )
            assertTrue(settings.taskNotificationsEnabled)
        }
    }

    @Test
    fun `message preview can be enabled explicitly`() = runTest {
        val target = file("preview")

        withSettings(target) { store, _ ->
            store.setShowMessagePreviewInNotification(true)
        }
        withSettings(target) { store, _ ->
            assertTrue(store.settings.first().showMessagePreviewInNotification)
        }
    }

    @Test
    fun `notification toggles are independent`() = runTest {
        withSettings(file("notifications")) { store, _ ->
            store.setMessageNotificationsEnabled(false)

            val settings = store.settings.first()
            assertFalse(settings.messageNotificationsEnabled)
            assertTrue(
                "Отключение уведомлений о сообщениях не должно глушить задачи",
                settings.taskNotificationsEnabled,
            )
        }
    }

    @Test
    fun `unknown theme value falls back to system`() = runTest {
        withSettings(file("broken")) { store, dataStore ->
            // Имитируем значение, записанное другой версией приложения.
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("theme_mode")] = "NEON"
            }

            assertEquals(
                "Неизвестное значение не должно ронять приложение",
                ThemeMode.SYSTEM,
                store.settings.first().themeMode,
            )
        }
    }

    @Test
    fun `all toggles round trip across a restart`() = runTest {
        val target = file("all")

        withSettings(target) { store, _ ->
            store.setThemeMode(ThemeMode.LIGHT)
            store.setDynamicColorEnabled(false)
            store.setMessageNotificationsEnabled(false)
            store.setTaskNotificationsEnabled(false)
            store.setShowMessagePreviewInNotification(true)
            store.setSendReadReceipts(false)
            store.setSendTypingIndicator(false)
            store.setAutoDownloadMediaOnWifiOnly(false)
        }

        withSettings(target) { store, _ ->
            assertEquals(
                UserSettings(
                    themeMode = ThemeMode.LIGHT,
                    dynamicColorEnabled = false,
                    messageNotificationsEnabled = false,
                    taskNotificationsEnabled = false,
                    showMessagePreviewInNotification = true,
                    sendReadReceipts = false,
                    sendTypingIndicator = false,
                    autoDownloadMediaOnWifiOnly = false,
                ),
                store.settings.first(),
            )
        }
    }
}
