package com.network.monitoring.service;

import jakarta.mail.internet.InternetAddress;
import java.util.*;

public record ReportMailProfile(String mode, List<String> recipients, List<String> attachments, boolean allowPartial, boolean enabled) {
    public ReportMailProfile validated() {
        String transport = mode == null ? "CAPTURE" : mode;
        if (!List.of("CAPTURE", "SMTP").contains(transport)) throw new IllegalArgumentException("Mail mode must be CAPTURE or SMTP.");
        if (recipients == null || recipients.isEmpty() || recipients.size() > 20) throw new IllegalArgumentException("Configure 1 to 20 recipient addresses.");
        List<String> normalized = recipients.stream().map(ReportMailProfile::address).sorted().toList();
        if (normalized.stream().distinct().count() != normalized.size()) throw new IllegalArgumentException("Recipient addresses must be unique.");
        if (attachments != null && attachments.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Attachment format cannot be null.");
        List<String> files = attachments == null ? List.of("pdf") : attachments.stream().sorted().toList();
        if (files.isEmpty() || files.stream().anyMatch(value -> !List.of("pdf", "xlsx").contains(value)) || files.stream().distinct().count() != files.size()) throw new IllegalArgumentException("Choose unique pdf/xlsx attachments.");
        return new ReportMailProfile(transport, normalized, files, allowPartial, enabled);
    }
    static String address(String value) {
        if (value == null || value.length() > 254 || !value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}")) throw new IllegalArgumentException("Use a plain valid email address without display names or control characters.");
        try { new InternetAddress(value, true).validate(); }
        catch (jakarta.mail.internet.AddressException ex) { throw new IllegalArgumentException("Invalid email address."); }
        // Conservative deduplication: a case-only address change must not trigger another customer email.
        return value.toLowerCase(Locale.ROOT);
    }
}
