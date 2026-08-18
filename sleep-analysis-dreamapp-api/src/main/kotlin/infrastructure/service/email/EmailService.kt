package team.dreamapp.com.infrastructure.service.email

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

object EmailService {
    fun sendVerificationCode(recipient: String, code: String) {
        val username = requiredEnv("SMTP_USERNAME")
        val password = requiredEnv("SMTP_APP_PASSWORD").replace(" ", "")
        val from = System.getenv("SMTP_FROM")?.trim().takeUnless { it.isNullOrBlank() } ?: username
        val properties = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            put("mail.smtp.writetimeout", "10000")
        }
        val session = Session.getInstance(properties, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(from, "DreamApp"))
            setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
            subject = "Tu código de verificación de DreamApp"
            setText(
                "Tu código de verificación es: $code\n\n" +
                    "Caduca en 10 minutos. Si no solicitaste esta cuenta, ignora este correo.\n\nDreamApp",
                Charsets.UTF_8.name()
            )
        }
        Transport.send(message)
    }

    private fun requiredEnv(name: String): String = System.getenv(name)?.trim()
        ?.takeIf { it.isNotBlank() } ?: error("$name is not configured")
}
