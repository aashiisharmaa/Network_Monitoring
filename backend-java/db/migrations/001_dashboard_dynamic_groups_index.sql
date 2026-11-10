-- Additive migration. Check INFORMATION_SCHEMA.STATISTICS before applying once.
-- Evidence: dashboard groups count scans kpidynamicdata; other dimensions already
-- have a (fileId, dimension) index. No historical data is modified.
CREATE INDEX `idx_kpidynamicdata_file_groups`
    ON `kpidynamicdata` (`fileId`, `groupsName`) ALGORITHM=INPLACE LOCK=NONE;

-- Rollback (only if this migration created the index):
-- DROP INDEX `idx_kpidynamicdata_file_groups` ON `kpidynamicdata`;
