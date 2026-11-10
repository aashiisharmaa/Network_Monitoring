package com.network.monitoring.service;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Sending starts only after connect succeeds and the durable SENDING transition commits. */
@Component
public class ReportMailTransport {
    public record Receipt(String status, String response) { }
    public static class UncertainDeliveryException extends IOException {
        public UncertainDeliveryException(Throwable cause) { super("SMTP outcome is uncertain. Reconcile with the provider before retrying.", cause); }
    }
    private final ReportMailSettings settings;
    public ReportMailTransport(ReportMailSettings settings) { this.settings = settings; }
    public Receipt deliver(String mode, Path mime, Runnable sending) throws Exception {
        if (mode.equals("CAPTURE")) { sending.run(); return new Receipt("CAPTURED", "Message captured locally; no SMTP connection or external email."); }
        if (!mode.equals("SMTP") || !settings.isOutboundEnabled()) throw new IllegalStateException("Outbound SMTP is disabled.");
        settings.validate();
        Properties props = new Properties();
        props.setProperty("mail.smtp.connectiontimeout", String.valueOf(settings.getTimeoutMillis()));
        props.setProperty("mail.smtp.timeout", String.valueOf(settings.getTimeoutMillis()));
        props.setProperty("mail.smtp.writetimeout", String.valueOf(settings.getTimeoutMillis()));
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(settings.isStarttls()));
        props.setProperty("mail.smtp.starttls.required", String.valueOf(settings.isStarttls()));
        props.setProperty("mail.smtp.ssl.enable", String.valueOf(settings.isSsl()));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        props.setProperty("mail.smtp.auth", String.valueOf(!settings.getUsername().isBlank()));
        props.setProperty("mail.smtp.sendpartial", "false"); props.setProperty("mail.smtp.quitwait", "false");
        Session session = Session.getInstance(props);
        MimeMessage message;
        try (var stream = Files.newInputStream(mime)) { message = new MimeMessage(session, stream); }
        SMTPTransport transport = (SMTPTransport) session.getTransport("smtp");
        try {
            transport.connect(settings.getHost(), settings.getPort(), settings.getUsername().isBlank() ? null : settings.getUsername(), settings.getPassword());
            sending.run();
            try { transport.sendMessage(message, message.getAllRecipients()); }
            catch (Exception ex) { throw new UncertainDeliveryException(ex); }
            String reply = transport.getLastServerResponse();
            return new Receipt("ACCEPTED", reply == null ? "SMTP accepted the message." : reply.substring(0, Math.min(1000, reply.length())));
        } finally { try { transport.close(); } catch (MessagingException ignored) { /* QUIT failure cannot turn an accepted DATA into a resend. */ } }
    }
}
