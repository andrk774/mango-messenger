package com.mango.backend.auth

import com.mango.backend.common.MailConfig
import com.mango.backend.common.Redact
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Отправка писем по SMTP.
 *
 * Локально адресатом выступает Mailpit: он принимает всё и показывает
 * в веб-интерфейсе, поэтому подтверждение адреса проверяется целиком,
 * без настоящего провайдера и без риска написать живому человеку.
 *
 * Отправка выполняется на отдельном диспетчере: Jakarta Mail блокирует
 * поток на время сетевого обмена, и выполнение в потоке обработки
 * запроса задерживало бы ответ пользователю на время работы SMTP.
 */
class SmtpMailSender(
    private val config: MailConfig,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MailSender {

    private val log = LoggerFactory.getLogger(SmtpMailSender::class.java)

    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", (config.username != null).toString())
            put("mail.smtp.starttls.enable", (config.security == com.mango.backend.common.MailSecurity.STARTTLS).toString())
            put("mail.smtp.starttls.required", (config.security == com.mango.backend.common.MailSecurity.STARTTLS).toString())
            put("mail.smtp.ssl.enable", (config.security == com.mango.backend.common.MailSecurity.TLS).toString())
            put("mail.smtp.ssl.checkserveridentity", "true")
            // Таймауты обязательны: без них зависший SMTP-сервер удерживает
            // поток бесконечно, и пул потоков исчерпывается.
            put("mail.smtp.connectiontimeout", "5000")
            put("mail.smtp.timeout", "5000")
            put("mail.smtp.writetimeout", "5000")
        },
    )

    /** Checks transport/TLS/authentication without sending mail. */
    suspend fun checkConnection() = withContext(dispatcher) {
        session.getTransport("smtp").use { transport ->
            transport.connect(config.host, config.port, config.username, config.password)
        }
    }

    override suspend fun send(to: String, subject: String, body: String) {
        withContext(dispatcher) {
            runCatching {
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(config.from))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    this.subject = subject
                    setText(body, Charsets.UTF_8.name())
                }
                if (config.username != null) {
                    Transport.send(message, config.username, config.password)
                } else {
                    Transport.send(message)
                }
            }.onSuccess {
                // В журнал — только факт отправки: тело письма содержит
                // одноразовый токен.
                log.info("письмо «{}» отправлено на {}", subject, Redact.email(to))
            }.onFailure { error ->
                // Недоступность почты НЕ должна валить регистрацию: учётная
                // запись уже создана, и повторную отправку можно запросить.
                // Молча игнорировать тоже нельзя — это попадает в журнал
                // как предупреждение.
                log.warn(
                    "не удалось отправить письмо «{}» на {}: {}",
                    subject,
                    Redact.email(to),
                    Redact.text(error.message),
                )
            }
        }
    }
}
