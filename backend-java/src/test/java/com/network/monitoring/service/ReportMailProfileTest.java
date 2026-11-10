package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ReportMailProfileTest {
    @Test void validatesAddressesAttachmentsAndExplicitCaptureMode() {
        var profile = new ReportMailProfile(null, List.of("USER@example.com"), null, false, true).validated();
        assertEquals("CAPTURE", profile.mode()); assertEquals(List.of("user@example.com"), profile.recipients()); assertEquals(List.of("pdf"), profile.attachments());
        for (String bad : List.of("User <a@example.com>", "a@example.com\r\nBcc:b@example.com", "a@example.com,b@example.com", "a@localhost", "a b@example.com")) assertThrows(IllegalArgumentException.class, () -> new ReportMailProfile("CAPTURE", List.of(bad), null, false, true).validated());
        assertThrows(IllegalArgumentException.class, () -> new ReportMailProfile("CAPTURE", List.of("A@example.com", "a@example.com"), null, false, true).validated());
        assertThrows(IllegalArgumentException.class, () -> new ReportMailProfile("CAPTURE", List.of("a@example.com"), List.of("exe"), false, true).validated());
    }
    @Test void defaultTransportCannotSendSmtpAndActivationRequiresTlsAndRealSender() {
        var settings = new ReportMailSettings(); settings.validate(); assertFalse(settings.isOutboundEnabled());
        assertThrows(IllegalStateException.class, () -> new ReportMailTransport(settings).deliver("SMTP", Path.of("not-read.eml"), () -> fail("Must never start SMTP")));
        settings.setOutboundEnabled(true); assertThrows(IllegalArgumentException.class, settings::validate);
        settings.setHost("smtp.example.com"); settings.setFrom("reports@example.com"); settings.setStarttls(false); assertThrows(IllegalArgumentException.class, settings::validate);
    }
}
