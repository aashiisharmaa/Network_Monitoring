package com.network.monitoring.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/** Immutable file names are published in the same DB transaction as their KPI rows. */
@Service
public class KpiSnapshotStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public KpiSnapshotStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void verifySchema() {
        try {
            jdbc.queryForList("SELECT fileId FROM kpi_dataset_snapshots LIMIT 0");
            jdbc.queryForList("SELECT id,attempts FROM kpi_ingestion_runs LIMIT 0");
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new IllegalStateException("Apply backend-java/db/migrations/002_kpi_ingestion_ledger.sql before starting this build.", ex);
        }
    }

    public String activeName(long fileId) {
        List<String> names = jdbc.query("SELECT snapshotName FROM kpi_dataset_snapshots WHERE fileId=?",
                (rs, row) -> rs.getString(1), fileId);
        return names.isEmpty() ? null : names.get(0);
    }

    public boolean managed(long fileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM kpi_dataset_snapshots WHERE fileId=? AND identityJson IS NOT NULL)", Boolean.class, fileId));
    }

    public String revision(long fileId) {
        return jdbc.queryForObject("SELECT revision FROM kpi_dataset_snapshots WHERE fileId=?", String.class, fileId);
    }

    public <T> T locked(long fileId, Supplier<T> action) {
        return transactions.execute(status -> {
            if (jdbc.queryForList("SELECT id FROM uploadhistory WHERE id=? FOR UPDATE", Long.class, fileId).isEmpty()) {
                throw new IllegalArgumentException("KPI dataset was not found.");
            }
            return action.get();
        });
    }

    public void publish(long fileId, String name, String revision, String checksum) {
        locked(fileId, () -> {
            jdbc.update("""
                    INSERT INTO kpi_dataset_snapshots (fileId,snapshotName,revision,checksum,updatedAt)
                    VALUES (?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE snapshotName=VALUES(snapshotName),revision=VALUES(revision),
                        checksum=VALUES(checksum),updatedAt=VALUES(updatedAt)
                    """, fileId, name, revision, checksum, Timestamp.valueOf(LocalDateTime.now()));
            return null;
        });
    }

    public void delete(long fileId) {
        jdbc.update("DELETE FROM kpi_dataset_snapshots WHERE fileId=?", fileId);
    }
}
