package com.network.monitoring.service;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "KPI_TEST_DB_URL", matches = ".+")
class ReportMailOutboxMysqlTest {
    @TempDir Path directory;
    Connection connection; JdbcTemplate jdbc; CommonReportService reports; ReportMailSettings settings;
    ReportMailTransport transport; ReportMailOutboxService service, held; ObjectProvider<ReportMailOutboxService> provider; Path attachment;
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        connection = DriverManager.getConnection(System.getenv("KPI_TEST_DB_URL"), System.getenv("KPI_TEST_DB_USER"), System.getenv("KPI_TEST_DB_PASSWORD"));
        var ds = new SingleConnectionDataSource(connection, true); jdbc = spy(new JdbcTemplate(ds));
        for (String table : List.of("uploadhistory", "kpi_mail_profiles", "kpi_mail_outbox", "kpi_mail_attempts", "kpi_mail_events")) {
            String ddl = jdbc.queryForMap("SHOW CREATE TABLE `" + table + "`").get("Create Table").toString().replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE");
            ddl = ddl.lines().filter(line -> !line.stripLeading().startsWith("CONSTRAINT ")).collect(java.util.stream.Collectors.joining("\n")).replace(",\n)", "\n)"); jdbc.execute(ddl);
        }
        jdbc.update("INSERT INTO uploadhistory (id,remarks) VALUES (500,'[kpi] mail fixture')");
        reports = mock(CommonReportService.class); when(reports.get(anyLong())).thenReturn(Map.of("fileId", 500L, "status", "READY"));
        var sections = new LinkedHashMap<String, Object>(); sections.put("worstCells", CommonReportModules.outcome("PENDING", "", Map.of()));
        new CommonReportRenderer().render(directory.resolve("report"), CommonReportRendererTest.input(), sections, sink -> sink.accept(CommonReportRendererTest.comparison("Demo Cell A", 80, false)), 100);
        attachment = directory.resolve("report/report.pdf");
        when(reports.download(anyLong(), eq("pdf"))).thenReturn(attachment);
        settings = new ReportMailSettings(); settings.setSpoolPath(directory.resolve("mail").toString());
        transport = spy(new ReportMailTransport(settings));
        provider = mock(ObjectProvider.class); held = mock(ReportMailOutboxService.class); when(provider.getObject()).thenReturn(held);
        service = new ReportMailOutboxService(jdbc, new KpiSnapshotStore(jdbc, new DataSourceTransactionManager(ds)), reports, settings, transport, provider); service.initialize();
    }
    @AfterEach void close() throws Exception { if (connection != null) connection.close(); }
    void profile(String mode, List<String> recipients, boolean allowPartial) { service.configure(500L, new ReportMailProfile(mode, recipients, null, allowPartial, true), 1L); }
    @SuppressWarnings("unchecked") long submit(long reportId) throws Exception { var items = (List<Map<String, Object>>) service.request(reportId, 1L).get("deliveries"); return ((Number) items.get(0).get("id")).longValue(); }
    String status(long id) { return service.get(id).get("status").toString(); }

    @Test void perRecipientCapturePinsEnvelopeDeduplicatesAndContainsOriginalAttachment() throws Exception {
        assertEquals("CONFIGURATION_REQUIRED", service.profile(500).get("status"));
        profile("CAPTURE", List.of("alice@example.com", "bob@example.com"), false);
        long id = submit(1); assertEquals(id, submit(1)); verify(held, times(1)).process(id);
        assertEquals(2, service.list(500, 20).size());
        profile("CAPTURE", List.of("changed@example.com"), false); // queued delivery still uses the captured recipient
        service.process(id); service.process(id); assertEquals("CAPTURED", status(id));
        verify(transport, times(1)).deliver(eq("CAPTURE"), any(), any());
        var metadata = service.get(id); assertEquals("alice@example.com", metadata.get("recipient")); assertEquals(false, metadata.get("inboxDeliveryConfirmed"));
        Path mimeFile = service.captured(id);
        assertTrue(java.time.Duration.between(service.completedAt(id), java.time.Instant.now()).abs().toSeconds() < 30);
        try (var input = Files.newInputStream(mimeFile)) {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), input);
            assertEquals(metadata.get("messageId"), mime.getMessageID()); assertEquals("alice@example.com", mime.getAllRecipients()[0].toString());
            Multipart parts = (Multipart) mime.getContent(); assertTrue(parts.getCount() >= 2);
            BodyPart part = parts.getBodyPart(parts.getCount() - 1);
            assertEquals("network-report-1.pdf", part.getFileName()); assertArrayEquals(Files.readAllBytes(attachment), part.getInputStream().readAllBytes());
        }
        assertThrows(IllegalArgumentException.class, () -> service.retry(id, 1L));
        String preview = System.getenv("KPI_REPORT_PREVIEW_DIR");
        if (preview != null) Files.copy(mimeFile, Path.of(preview).resolve("sample-report-mail.eml"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(mimeFile, "tampered"); assertThrows(java.io.IOException.class, () -> service.captured(id));
    }
    @Test void safeFailureCanRetryWhileUnknownRequiresAuditedReconciliation() throws Exception {
        settings.setOutboundEnabled(true); settings.setHost("smtp.example.com"); settings.setFrom("reports@example.com");
        profile("SMTP", List.of("alice@example.com"), false);
        long id = submit(2);
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); throw new ReportMailTransport.UncertainDeliveryException(new java.io.IOException("lost DATA response")); }).when(transport).deliver(eq("SMTP"), any(), any());
        service.process(id); assertEquals("UNKNOWN", status(id)); assertThrows(IllegalArgumentException.class, () -> service.retry(id, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.reconcile(id, "NOT_ACCEPTED", "", 1L));
        service.reconcile(id, "NOT_ACCEPTED", "Provider searched the Message-ID and confirmed it was not accepted.", 1L);
        assertEquals("FAILED", status(id));
        String messageId = service.get(id).get("messageId").toString();
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return new ReportMailTransport.Receipt("ACCEPTED", "250 queued as test-42"); }).when(transport).deliver(eq("SMTP"), any(), any());
        when(provider.getObject()).thenReturn(service); service.retry(id, 1L);
        assertEquals("ACCEPTED", status(id)); assertEquals(messageId, service.get(id).get("messageId"));
        assertNotNull(service.get(id).get("acceptedAt")); assertEquals("250 queued as test-42", service.get(id).get("providerResponse"));
        assertEquals(2, ((List<?>) service.get(id).get("attemptHistory")).size());
        assertTrue(service.get(id).get("events").toString().contains("RECONCILED_NOT_ACCEPTED"));
        assertThrows(IllegalArgumentException.class, () -> service.retry(id, 1L));
    }
    @Test void startupDistinguishesKnownUnsentFromUncertainAndCancellationPreventsSend() throws Exception {
        profile("CAPTURE", List.of("alice@example.com"), false);
        long queued = submit(10), preparing = submit(11), sending = submit(12);
        for (long id : List.of(preparing, sending)) {
            jdbc.update("UPDATE kpi_mail_outbox SET status=?,attempts=1 WHERE id=?", id == sending ? "SENDING" : "PREPARING", id);
            jdbc.update("INSERT INTO kpi_mail_attempts (deliveryId,attemptNo,status,startedAt) VALUES (?,1,?,NOW())", id, id == sending ? "SENDING" : "PREPARING");
        }
        service.initialize(); assertEquals("FAILED", status(queued)); assertEquals("FAILED", status(preparing)); assertEquals("UNKNOWN", status(sending));
        assertThrows(IllegalArgumentException.class, () -> service.reconcile(sending, "ACCEPTED", "Capture cannot be SMTP accepted.", 1L));
        service.reconcile(sending, "NOT_ACCEPTED", "No captured file exists; interrupted before capture publication.", 1L); assertEquals("FAILED", status(sending));
        long cancelled = submit(13); service.cancel(cancelled, 1L); service.process(cancelled); assertEquals("CANCELLED", status(cancelled)); verifyNoInteractions(transport);
    }
    @Test void partialPolicyIntegrityQueueRejectionAndRetryLimitAreExplicit() throws Exception {
        profile("CAPTURE", List.of("alice@example.com"), false);
        when(reports.get(20)).thenReturn(Map.of("fileId", 500L, "status", "PARTIAL"));
        assertThrows(IllegalArgumentException.class, () -> submit(20));
        profile("CAPTURE", List.of("alice@example.com"), true); long partial = submit(20);
        Files.writeString(attachment, "changed after capture"); service.process(partial); assertEquals("FAILED", status(partial)); verifyNoInteractions(transport);
        settings.setMaxAttempts(1); assertThrows(IllegalArgumentException.class, () -> service.retry(partial, 1L));
        doThrow(new RejectedExecutionException()).when(held).process(anyLong());
        var rejected = service.request(21, 1L); assertEquals(true, rejected.get("capacityLimited"));
        assertTrue(rejected.get("deliveries").toString().contains("FAILED"));
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM kpi_mail_outbox WHERE reportId=21", Integer.class));
    }
    @Test void preparationRetryRegeneratesAnUnpublishedMimeWithoutChangingEnvelope() throws Exception {
        profile("CAPTURE", List.of("alice@example.com"), false); long id = submit(30);
        byte[] original = Files.readAllBytes(attachment); Files.writeString(attachment, "tampered");
        service.process(id); assertEquals("FAILED", status(id));
        Path orphan = directory.resolve("mail/delivery-" + id + "/message.eml"); Files.writeString(orphan, "interrupted unpublished write");
        Files.write(attachment, original); when(provider.getObject()).thenReturn(service);
        service.retry(id, 1L); assertEquals("CAPTURED", status(id)); assertEquals(2, service.get(id).get("attempts"));
        assertTrue(Files.size(service.captured(id)) > original.length);
    }
    @Test void acceptancePersistenceFailureBecomesUnknownInsteadOfResending() throws Exception {
        settings.setOutboundEnabled(true); settings.setHost("smtp.example.com"); settings.setFrom("reports@example.com");
        profile("SMTP", List.of("alice@example.com"), false); long id = submit(31);
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return new ReportMailTransport.Receipt("ACCEPTED", "250 accepted test-44"); }).when(transport).deliver(eq("SMTP"), any(), any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected completion write failure")).doCallRealMethod()
                .when(jdbc).update(startsWith("UPDATE kpi_mail_outbox SET status=?,message=?,providerResponse=?"), any(Object[].class));
        service.process(id); assertEquals("UNKNOWN", status(id)); service.process(id);
        verify(transport, times(1)).deliver(eq("SMTP"), any(), any());
        assertThrows(IllegalArgumentException.class, () -> service.retry(id, 1L));
    }
    @Test void portalCannotSendUsingRecipientSettingsThatChangedAfterReview() throws Exception {
        profile("CAPTURE", List.of("alice@example.com"), false);
        String reviewed = service.profile(500).get("profileHash").toString();
        profile("CAPTURE", List.of("bob@example.com"), false);
        assertThrows(IllegalArgumentException.class, () -> service.request(40, 1L, reviewed));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM kpi_mail_outbox", Integer.class)); verifyNoInteractions(held, transport);
    }
}
