package com.network.monitoring.service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ReportMailOutboxService {
    private final JdbcTemplate jdbc;
    private final KpiSnapshotStore transactions;
    private final CommonReportService reports;
    private final ReportMailSettings settings;
    private final ReportMailTransport transport;
    private final ObjectProvider<ReportMailOutboxService> self;
    private final Path root;
    @Value("${app.jobs.recover-on-startup:true}") private boolean recover = true;
    public ReportMailOutboxService(JdbcTemplate jdbc, KpiSnapshotStore transactions, CommonReportService reports,
                                    ReportMailSettings settings, ReportMailTransport transport, ObjectProvider<ReportMailOutboxService> self) {
        this.jdbc = jdbc; this.transactions = transactions; this.reports = reports; this.settings = settings; this.transport = transport; this.self = self;
        root = Path.of(settings.getSpoolPath()).toAbsolutePath().normalize();
    }
    @PostConstruct public void initialize() {
        settings.validate();
        try { for (String table : List.of("kpi_mail_profiles", "kpi_mail_outbox", "kpi_mail_attempts", "kpi_mail_events")) jdbc.queryForList("SELECT * FROM " + table + " LIMIT 0"); }
        catch (org.springframework.dao.DataAccessException ex) { throw new IllegalStateException("Apply backend-java/db/migrations/005_report_delivery_outbox.sql before starting.", ex); }
        if (recover) {
            jdbc.update("UPDATE kpi_mail_attempts a JOIN kpi_mail_outbox o ON o.id=a.deliveryId AND o.attempts=a.attemptNo SET a.status=IF(o.status='SENDING','UNKNOWN','FAILED'),a.message='Backend restarted during delivery.',a.finishedAt=NOW() WHERE o.status IN ('PREPARING','SENDING') AND a.finishedAt IS NULL");
            jdbc.update("UPDATE kpi_mail_outbox SET status=IF(status='SENDING','UNKNOWN','FAILED'),message='Backend restarted. Reconcile UNKNOWN with provider before retrying.',updatedAt=NOW() WHERE status IN ('QUEUED','PREPARING','SENDING')");
        }
    }
    public Map<String, Object> configure(long fileId, ReportMailProfile raw, Long actor) {
        var profile = raw.validated(); String json = write(profile);
        String hash = RecommendationCacheKey.build(CommonReportRenderer.map(profile), "mail-profile-v1", Map.of());
        transactions.locked(fileId, () -> {
            jdbc.update("INSERT INTO kpi_mail_profiles (fileId,profileHash,configJson,updatedBy,updatedAt) VALUES (?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE profileHash=VALUES(profileHash),configJson=VALUES(configJson),updatedBy=VALUES(updatedBy),updatedAt=NOW()", fileId, hash, json, actor); return null;
        });
        return profile(fileId);
    }
    public Map<String, Object> profile(long fileId) {
        var rows = jdbc.queryForList("SELECT profileHash,configJson,updatedBy,updatedAt FROM kpi_mail_profiles WHERE fileId=?", fileId);
        if (rows.isEmpty()) return Map.of("fileId", fileId, "status", "CONFIGURATION_REQUIRED", "outboundEnabled", settings.isOutboundEnabled());
        var result = new LinkedHashMap<>(rows.get(0)); Object config = result.remove("configJson"); result.put("profile", read(config.toString())); result.put("fileId", fileId); result.put("outboundEnabled", settings.isOutboundEnabled()); return result;
    }
    private record Submission(long id, boolean created) { }
    public Map<String, Object> request(long reportId, Long actor) throws IOException {
        return request(reportId, actor, null);
    }
    public Map<String, Object> request(long reportId, Long actor, String expectedProfileHash) throws IOException {
        var report = reports.get(reportId); long fileId = ((Number) report.get("fileId")).longValue();
        String status = String.valueOf(report.get("status"));
        if (!List.of("READY", "PARTIAL").contains(status)) throw new IllegalArgumentException("A completed report is required before delivery.");
        List<Submission> submissions = transactions.locked(fileId, () -> {
            var rows = jdbc.queryForList("SELECT profileHash,configJson FROM kpi_mail_profiles WHERE fileId=?", fileId);
            if (rows.isEmpty()) throw new IllegalArgumentException("Configure the report mail profile first.");
            if (expectedProfileHash != null && !expectedProfileHash.equals(rows.get(0).get("profileHash"))) throw new IllegalArgumentException("Email settings changed. Refresh and review the recipients before requesting delivery.");
            var profile = CommonReportModules.JSON.convertValue(read(rows.get(0).get("configJson").toString()), ReportMailProfile.class).validated();
            if (!profile.enabled()) throw new IllegalArgumentException("The report mail profile is disabled.");
            if (status.equals("PARTIAL") && !profile.allowPartial()) throw new IllegalArgumentException("This report is PARTIAL. Review its coverage before explicitly allowing partial delivery.");
            if (profile.mode().equals("SMTP") && !settings.isOutboundEnabled()) throw new IllegalArgumentException("Outbound SMTP is disabled. Use CAPTURE for local verification.");
            Map<String, Object> attachments = new TreeMap<>();
            long bytes = 0;
            try {
                for (String format : profile.attachments()) { Path file = reports.download(reportId, format); bytes += Files.size(file); attachments.put(format, RecommendationCacheKey.fileDigest(file)); }
            } catch (IOException ex) { throw new IllegalArgumentException("Report attachment integrity validation failed.", ex); }
            if (bytes > settings.getMaxMessageBytes() * 0.70) throw new IllegalArgumentException("Attachments exceed the email size budget. Select PDF only or lower report detail volume.");
            List<Submission> submitted = new ArrayList<>();
            for (String recipient : profile.recipients()) {
                String key = RecommendationCacheKey.build(Map.of("reportId", reportId, "recipient", recipient, "mode", profile.mode()), "report-mail-v1", Map.of());
                String messageId = "<report-" + key + "@" + ReportMailProfile.address(settings.getFrom()).split("@", 2)[1] + ">";
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("from", ReportMailProfile.address(settings.getFrom())); envelope.put("recipient", recipient); envelope.put("attachments", attachments);
                envelope.put("profileHash", rows.get(0).get("profileHash")); envelope.put("reportStatus", status); envelope.put("smtpIdentity", smtpIdentity());
                envelope.put("subject", "Network performance report #" + reportId + (status.equals("PARTIAL") ? " [PARTIAL]" : ""));
                envelope.put("body", "Please find the common network performance report attached.\nReport ID: " + reportId + "\nCoverage: " + status + "\nReview module coverage and comparison windows in the report.\nSMTP acceptance is not confirmation of inbox delivery.\n");
                var keys = new GeneratedKeyHolder();
                try {
                    jdbc.update(connection -> {
                        var statement = connection.prepareStatement("INSERT INTO kpi_mail_outbox (deliveryKey,reportId,fileId,recipient,mode,envelopeJson,messageId,status,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,'QUEUED',NOW(),NOW())", Statement.RETURN_GENERATED_KEYS);
                        statement.setString(1, key); statement.setLong(2, reportId); statement.setLong(3, fileId); statement.setString(4, recipient); statement.setString(5, profile.mode()); statement.setString(6, write(envelope)); statement.setString(7, messageId); return statement;
                    }, keys);
                    long id = Objects.requireNonNull(keys.getKey()).longValue(); event(id, "REQUESTED", actor, "Captured immutable delivery envelope."); submitted.add(new Submission(id, true));
                } catch (DuplicateKeyException ex) { submitted.add(new Submission(jdbc.queryForObject("SELECT id FROM kpi_mail_outbox WHERE deliveryKey=?", Long.class, key), false)); }
            }
            return submitted;
        });
        boolean limited = false;
        for (var submission : submissions) if (submission.created()) { try { dispatch(submission.id()); } catch (JobCapacityException ex) { limited = true; } }
        return Map.of("capacityLimited", limited, "deliveries", submissions.stream().map(value -> get(value.id())).toList());
    }
    private String smtpIdentity() { return RecommendationCacheKey.build(Map.of("host", settings.getHost(), "port", settings.getPort(), "tls", settings.isStarttls(), "ssl", settings.isSsl(), "username", settings.getUsername()), "smtp-endpoint-v1", Map.of()); }
    private void dispatch(long id) {
        try { self.getObject().process(id); }
        catch (RejectedExecutionException ex) { jdbc.update("UPDATE kpi_mail_outbox SET status='FAILED',message='Mail queue full. No send was attempted.',updatedAt=NOW() WHERE id=? AND status='QUEUED'", id); throw new JobCapacityException(id, "Mail queue is full. Retry this delivery.", ex); }
        catch (RuntimeException ex) { jdbc.update("UPDATE kpi_mail_outbox SET status='FAILED',message='Mail could not be scheduled.',updatedAt=NOW() WHERE id=? AND status='QUEUED'", id); throw ex; }
    }
    public Map<String, Object> retry(long id, Long actor) {
        long fileId = fileId(id);
        transactions.locked(fileId, () -> {
            if (jdbc.update("UPDATE kpi_mail_outbox SET status='QUEUED',message=NULL,updatedAt=NOW() WHERE id=? AND status='FAILED' AND attempts<?", id, settings.getMaxAttempts()) != 1) throw new IllegalArgumentException("Only FAILED deliveries below the attempt limit can be retried. Reconcile UNKNOWN first.");
            event(id, "RETRY_REQUESTED", actor, "Explicit retry after a known failure."); return null;
        }); dispatch(id); return get(id);
    }
    public Map<String, Object> cancel(long id, Long actor) {
        transactions.locked(fileId(id), () -> {
            if (jdbc.update("UPDATE kpi_mail_outbox SET status='CANCELLED',message='Cancelled before sending.',updatedAt=NOW() WHERE id=? AND status IN ('QUEUED','FAILED')", id) != 1) throw new IllegalArgumentException("Only QUEUED or FAILED deliveries can be cancelled.");
            event(id, "CANCELLED", actor, "Cancelled before send."); return null;
        }); return get(id);
    }
    public Map<String, Object> reconcile(long id, String outcome, String evidence, Long actor) {
        if (outcome == null || !List.of("ACCEPTED", "CAPTURED", "NOT_ACCEPTED").contains(outcome) || evidence == null || evidence.isBlank() || evidence.length() > 1000) throw new IllegalArgumentException("Supply ACCEPTED/CAPTURED/NOT_ACCEPTED and reconciliation evidence (maximum 1000 characters).");
        transactions.locked(fileId(id), () -> {
            String mode = row(id).get("mode").toString();
            if ((outcome.equals("ACCEPTED") && !mode.equals("SMTP")) || (outcome.equals("CAPTURED") && !mode.equals("CAPTURE"))) throw new IllegalArgumentException("Reconciled outcome must match the delivery transport.");
            if (outcome.equals("CAPTURED")) { var value = row(id); try { verify(root.resolve("delivery-" + id).resolve("message.eml"), String.valueOf(value.get("mimeChecksum"))); } catch (IOException ex) { throw new IllegalArgumentException("Captured MIME integrity must be verified first.", ex); } }
            if (jdbc.update("UPDATE kpi_mail_outbox SET status=?,message='Outcome manually reconciled; see audit events.',acceptedAt=IF(?='ACCEPTED',NOW(),NULL),capturedAt=IF(?='CAPTURED',NOW(),NULL),updatedAt=NOW() WHERE id=? AND status='UNKNOWN'", outcome.equals("NOT_ACCEPTED") ? "FAILED" : outcome, outcome, outcome, id) != 1) throw new IllegalArgumentException("Only UNKNOWN deliveries require reconciliation.");
            event(id, "RECONCILED_" + outcome, actor, evidence); return null;
        }); return get(id);
    }
    @Async("reportMailExecutor") public void process(long id) {
        long fileId = fileId(id);
        Integer attempt = transactions.locked(fileId, () -> {
            if (jdbc.update("UPDATE kpi_mail_outbox SET status='PREPARING',attempts=attempts+1,updatedAt=NOW() WHERE id=? AND status='QUEUED' AND attempts<?", id, settings.getMaxAttempts()) == 0) return null;
            int next = ((Number) row(id).get("attempts")).intValue();
            jdbc.update("INSERT INTO kpi_mail_attempts (deliveryId,attemptNo,status,startedAt) VALUES (?,?,'PREPARING',NOW())", id, next); return next;
        });
        if (attempt == null) return;
        try {
            var delivery = row(id); var envelope = read(delivery.get("envelopeJson").toString()); String mode = delivery.get("mode").toString();
            if (mode.equals("SMTP") && (!settings.isOutboundEnabled() || !smtpIdentity().equals(envelope.get("smtpIdentity")))) throw new IllegalStateException("SMTP is disabled or its endpoint identity changed.");
            Path mime = prepare(id, delivery, envelope);
            var receipt = transport.deliver(mode, mime, () -> transactions.locked(fileId, () -> {
                if (jdbc.update("UPDATE kpi_mail_outbox SET status='SENDING',updatedAt=NOW() WHERE id=? AND status='PREPARING' AND attempts=?", id, attempt) != 1) throw new IllegalStateException("Delivery no longer owns this attempt.");
                jdbc.update("UPDATE kpi_mail_attempts SET status='SENDING' WHERE deliveryId=? AND attemptNo=?", id, attempt); return null;
            }));
            if (!List.of("CAPTURED", "ACCEPTED").contains(receipt.status())) throw new IllegalStateException("Invalid mail transport receipt.");
            finish(id, fileId, attempt, receipt.status(), receipt.response());
        } catch (Exception ex) {
            // A failure persisting acceptance must be treated like a lost SMTP response, never a safe resend.
            String state = row(id).get("status").toString();
            if (List.of("PREPARING", "SENDING").contains(state)) finish(id, fileId, attempt, state.equals("SENDING") ? "UNKNOWN" : "FAILED",
                    state.equals("SENDING") ? "Outcome uncertain. Reconcile with provider before retrying." : unsentFailure(ex));
        }
    }
    private static String unsentFailure(Exception ex) {
        if (ex instanceof jakarta.mail.MessagingException) return "SMTP connection/authentication failed before submission. Review SMTP configuration; retry is safe.";
        if (ex instanceof IOException) return "Attachment/MIME integrity, size or filesystem validation failed before submission. Repair the artifact before retrying.";
        return "Preparation, SMTP activation or endpoint configuration failed before submission. No message was submitted.";
    }
    private void finish(long id, long fileId, int attempt, String status, String message) {
        transactions.locked(fileId, () -> {
            if (jdbc.update("UPDATE kpi_mail_outbox SET status=?,message=?,providerResponse=?,acceptedAt=IF(?='ACCEPTED',NOW(),acceptedAt),capturedAt=IF(?='CAPTURED',NOW(),capturedAt),updatedAt=NOW() WHERE id=? AND attempts=? AND status IN ('PREPARING','SENDING')", status, limit(message), status.equals("ACCEPTED") ? limit(message) : null, status, status, id, attempt) != 1) throw new IllegalStateException("Delivery no longer owns this result.");
            jdbc.update("UPDATE kpi_mail_attempts SET status=?,message=?,finishedAt=NOW() WHERE deliveryId=? AND attemptNo=?", status, limit(message), id, attempt); return null;
        });
    }
    private Path prepare(long id, Map<String, Object> row, Map<String, Object> envelope) throws Exception {
        Path directory = root.resolve("delivery-" + id); Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(root.toRealPath())) throw new IOException("Invalid MIME directory.");
        Path target = directory.resolve("message.eml");
        if (row.get("mimeChecksum") != null) { verify(target, row.get("mimeChecksum").toString()); return target; }
        // No persisted MIME checksum means PREPARING never reached SENDING. An interrupted
        // atomic file publication can be regenerated from the same immutable envelope.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom(envelope.get("from").toString()); helper.setTo(envelope.get("recipient").toString()); helper.setSubject(envelope.get("subject").toString()); helper.setText(envelope.get("body").toString(), false);
        long reportId = ((Number) row.get("reportId")).longValue();
        for (var attachment : CommonReportRenderer.map(envelope.get("attachments")).entrySet()) {
            Path file = reports.download(reportId, attachment.getKey()); verify(file, attachment.getValue().toString());
            helper.addAttachment("network-report-" + reportId + "." + attachment.getKey(), file.toFile());
        }
        mime.setSentDate(new Date()); mime.saveChanges(); mime.setHeader("Message-ID", row.get("messageId").toString());
        Path temp = Files.createTempFile(directory, "mime-", ".tmp");
        try {
            try (var output = new LimitedOutput(Files.newOutputStream(temp), settings.getMaxMessageBytes())) { mime.writeTo(output); }
            String checksum = RecommendationCacheKey.fileDigest(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            jdbc.update("UPDATE kpi_mail_outbox SET mimeChecksum=? WHERE id=? AND status='PREPARING'", checksum, id);
        } finally { Files.deleteIfExists(temp); }
        return target;
    }
    public Path captured(long id) throws IOException {
        var row = row(id); if (!"CAPTURED".equals(row.get("status"))) throw new IllegalArgumentException("Only captured test mail is downloadable.");
        Path target = root.resolve("delivery-" + id).resolve("message.eml"); verify(target, String.valueOf(row.get("mimeChecksum"))); return target;
    }
    private void verify(Path file, String checksum) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Objects.equals(checksum, RecommendationCacheKey.fileDigest(file))) throw new IOException("Mail attachment or MIME integrity validation failed.");
        if (file.startsWith(root) && !file.toRealPath().startsWith(root.toRealPath())) throw new IOException("Invalid MIME location.");
    }
    public long fileId(long id) { return ((Number) row(id).get("fileId")).longValue(); }
    public java.time.Instant completedAt(long id) {
        var epoch = jdbc.queryForObject("SELECT UNIX_TIMESTAMP(IF(status='ACCEPTED',acceptedAt,capturedAt)) FROM kpi_mail_outbox WHERE id=? AND status IN ('ACCEPTED','CAPTURED')", java.math.BigDecimal.class, id);
        if (epoch == null) throw new IllegalStateException("Delivery completion time is unavailable.");
        return java.time.Instant.ofEpochSecond(epoch.longValue());
    }
    private Map<String, Object> row(long id) { var rows = jdbc.queryForList("SELECT * FROM kpi_mail_outbox WHERE id=?", id); if (rows.isEmpty()) throw new IllegalArgumentException("Delivery was not found."); return rows.get(0); }
    public Map<String, Object> get(long id) {
        Map<String, Object> result = view(row(id));
        result.put("attemptHistory", jdbc.queryForList("SELECT attemptNo,status,message,startedAt,finishedAt FROM kpi_mail_attempts WHERE deliveryId=? ORDER BY attemptNo", id));
        result.put("events", jdbc.queryForList("SELECT event,actorId,evidence,createdAt FROM kpi_mail_events WHERE deliveryId=? ORDER BY id", id)); return result;
    }
    public List<Map<String, Object>> list(long fileId, int limit) { return jdbc.queryForList("SELECT * FROM kpi_mail_outbox WHERE fileId=? ORDER BY id DESC LIMIT ?", fileId, Math.max(1, Math.min(limit, 100))).stream().map(this::view).toList(); }
    private Map<String, Object> view(Map<String, Object> row) { Map<String, Object> result = new LinkedHashMap<>(row); result.remove("envelopeJson"); result.remove("deliveryKey"); result.remove("mimeChecksum"); result.put("inboxDeliveryConfirmed", false); return result; }
    private void event(long id, String event, Long actor, String evidence) { jdbc.update("INSERT INTO kpi_mail_events (deliveryId,event,actorId,evidence,createdAt) VALUES (?,?,?,?,NOW())", id, event, actor, evidence); }
    private static String limit(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 1000)); }
    private static String write(Object value) { try { return CommonReportModules.JSON.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("Invalid mail configuration.", ex); } }
    private static Map<String, Object> read(String value) { try { return CommonReportModules.JSON.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { }); } catch (Exception ex) { throw new IllegalStateException("Invalid stored mail configuration.", ex); } }
    private static class LimitedOutput extends FilterOutputStream {
        private long count; private final long limit;
        LimitedOutput(OutputStream output, long limit) { super(output); this.limit = limit; }
        public void write(int value) throws IOException { if (++count > limit) throw new IOException("Encoded email exceeds size limit."); out.write(value); }
        public void write(byte[] bytes, int offset, int length) throws IOException { count += length; if (count > limit) throw new IOException("Encoded email exceeds size limit."); out.write(bytes, offset, length); }
    }
}
