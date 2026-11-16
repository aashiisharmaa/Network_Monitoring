package com.network.monitoring.platform.mail;

import jakarta.mail.internet.InternetAddress;
import java.util.Locale;

/** Plain-address validation shared by transport settings and report recipient profiles. */
public final class MailAddresses {
    private MailAddresses() {}

    public static String normalize(String value) {
        if (value == null || value.length() > 254
                || !value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}"))
            throw new IllegalArgumentException("Use a plain valid email address without display names or control characters.");
        try {
            new InternetAddress(value, true).validate();
        } catch (jakarta.mail.internet.AddressException ex) {
            throw new IllegalArgumentException("Invalid email address.");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
