package com.mango.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ChatBackground { DEFAULT, BLUE, MINT, LILAC, SAND }

data class UserSettings(
    val chatBackground: ChatBackground = ChatBackground.DEFAULT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColorEnabled: Boolean = false,
    val messageNotificationsEnabled: Boolean = true,
    val taskNotificationsEnabled: Boolean = true,
    /**
     * Показывать текст сообщения в уведомлении.
     *
     * Включено по умолчанию. Явный выбор пользователя сохраняется.
     * Экран блокировки получает отдельную версию без содержимого.
     */
    val showMessagePreviewInNotification: Boolean = true,
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicator: Boolean = true,
    val autoDownloadMediaOnWifiOnly: Boolean = true,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsStore.STORE_NAME,
)

/**
 * Пользовательские настройки.
 *
 * DataStore, а не SharedPreferences: асинхронный доступ без блокировки
 * главного потока и транзакционная запись.
 *
 * DataStore передаётся в конструктор, а не создаётся внутри: делегат
 * `preferencesDataStore` кэширует экземпляр в статическом поле по имени,
 * из-за чего тесты в одном JVM видят состояние друг друга. Инжекция
 * даёт каждому тесту изолированный экземпляр.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<UserSettings> = dataStore.data
        // Повреждённый файл настроек не должен ронять приложение:
        // откатываемся на значения по умолчанию.
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { preferences ->
            UserSettings(
                chatBackground = preferences[Keys.CHAT_BACKGROUND]?.let {runCatching {ChatBackground.valueOf(it)}.getOrNull()} ?: ChatBackground.DEFAULT,
                themeMode = preferences[Keys.THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                dynamicColorEnabled = preferences[Keys.DYNAMIC_COLOR] ?: false,
                messageNotificationsEnabled = preferences[Keys.MESSAGE_NOTIFICATIONS] ?: true,
                taskNotificationsEnabled = preferences[Keys.TASK_NOTIFICATIONS] ?: true,
                showMessagePreviewInNotification = preferences[Keys.MESSAGE_PREVIEW] ?: true,
                sendReadReceipts = preferences[Keys.READ_RECEIPTS] ?: true,
                sendTypingIndicator = preferences[Keys.TYPING_INDICATOR] ?: true,
                autoDownloadMediaOnWifiOnly = preferences[Keys.WIFI_ONLY_MEDIA] ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setChatBackground(background: ChatBackground) = edit {it[Keys.CHAT_BACKGROUND]=background.name}

    suspend fun setDynamicColorEnabled(enabled: Boolean) =
        edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setMessageNotificationsEnabled(enabled: Boolean) =
        edit { it[Keys.MESSAGE_NOTIFICATIONS] = enabled }

    suspend fun setTaskNotificationsEnabled(enabled: Boolean) =
        edit { it[Keys.TASK_NOTIFICATIONS] = enabled }

    suspend fun setShowMessagePreviewInNotification(enabled: Boolean) =
        edit { it[Keys.MESSAGE_PREVIEW] = enabled }

    suspend fun setSendReadReceipts(enabled: Boolean) =
        edit { it[Keys.READ_RECEIPTS] = enabled }

    suspend fun setSendTypingIndicator(enabled: Boolean) =
        edit { it[Keys.TYPING_INDICATOR] = enabled }

    suspend fun setAutoDownloadMediaOnWifiOnly(enabled: Boolean) =
        edit { it[Keys.WIFI_ONLY_MEDIA] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    internal companion object {
        const val STORE_NAME = "mango_settings"
    }

    private object Keys {
        val CHAT_BACKGROUND = stringPreferencesKey("chat_background")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val MESSAGE_NOTIFICATIONS = booleanPreferencesKey("message_notifications")
        val TASK_NOTIFICATIONS = booleanPreferencesKey("task_notifications")
        val MESSAGE_PREVIEW = booleanPreferencesKey("message_preview_in_notification")
        val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        val TYPING_INDICATOR = booleanPreferencesKey("typing_indicator")
        val WIFI_ONLY_MEDIA = booleanPreferencesKey("wifi_only_media")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideSettingsStore(dataStore: DataStore<Preferences>): SettingsStore =
        SettingsStore(dataStore)
}
