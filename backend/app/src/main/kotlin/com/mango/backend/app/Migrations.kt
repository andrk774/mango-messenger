package com.mango.backend.app

import com.mango.backend.common.DatabaseConfig
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

/**
 * Применение миграций схемы.
 *
 * Выполняется при старте приложения, до открытия пула и до приёма трафика.
 * Flyway берёт блокировку на своей служебной таблице, поэтому одновременный
 * старт нескольких экземпляров безопасен: миграции применит ровно один.
 */
object Migrations {

    private val log = LoggerFactory.getLogger(Migrations::class.java)

    fun run(config: DatabaseConfig): Int {
        val flyway = Flyway.configure()
            .dataSource(config.jdbcUrl, config.username, config.password)
            .locations("classpath:db/migration")
            // Откат к более ранней версии запрещён: это означало бы, что
            // запущен старый образ поверх новой схемы.
            .ignoreMigrationPatterns("")
            // Проверка контрольных сумм: изменение уже применённого файла
            // миграции — ошибка, а не повод «подправить на ходу».
            .validateOnMigrate(true)
            // На пустой БД baseline не нужен; на существующей без истории
            // Flyway должен остановиться, а не делать вид, что всё хорошо.
            .baselineOnMigrate(false)
            .load()

        val result = flyway.migrate()
        if (result.migrationsExecuted > 0) {
            log.info(
                "применено миграций: {}, схема на версии {}",
                result.migrationsExecuted,
                result.targetSchemaVersion,
            )
        } else {
            log.info("схема актуальна, версия {}", flyway.info().current()?.version)
        }
        return result.migrationsExecuted
    }
}
