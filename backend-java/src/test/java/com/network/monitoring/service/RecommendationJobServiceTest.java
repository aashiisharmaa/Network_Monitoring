package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationJobServiceTest {
    @Test
    void rejectedSubmissionFailsJobInsteadOfLeavingItQueued() {
        var fixture = new Fixture();
        doThrow(new RejectedExecutionException("full")).when(fixture.worker)
                .processJob(eq(123L), eq(1L), anyMap(), eq("version-a"));
        var ex = assertThrows(JobCapacityException.class, () -> fixture.service.startJob(1L, Map.of()));
        assertEquals(123L, ex.getJobId());
        assertEquals(List.of("FAILED"), fixture.states);
    }

    @Test
    void pollingQueuedJobDoesNotExpireItBasedOnItsAge() {
        var fixture = new Fixture();
        assertNotNull(fixture.service.getJob(123L));
        assertTrue(fixture.states.isEmpty());
    }

    @Test
    void workerCannotExecuteAnAlreadyClaimedJob() throws Exception {
        var fixture = new Fixture();
        fixture.workerClaim = false;
        fixture.service.processJob(123L, 1L, Map.of(), "version-a");
        verify(fixture.recommendation, never()).runRecommendation(anyLong(), anyMap(), anyBoolean(), any());
    }
    @Test
    void unchangedDataReusesCompletedJobWithoutSchedulingPython() {
        var fixture = new Fixture();
        fixture.cached = Map.of("jobId", 7L, "status", "COMPLETED");
        var result = fixture.service.startJob(1L, Map.of("vendor", "nokia"));
        assertEquals(fixture.cached, result.get("data"));
        verifyNoInteractions(fixture.worker);
        assertTrue(fixture.insertedKeys.isEmpty());
    }

    @Test
    void unverifiableSourceBypassesOldJobsAndUsesNonReusableKeys() {
        var fixture = new Fixture();
        when(fixture.recommendation.currentCacheKey(anyLong(), anyMap())).thenReturn(Optional.empty());
        fixture.cached = Map.of("jobId", 7L, "status", "COMPLETED");
        fixture.service.startJob(1L, Map.of());
        fixture.service.startJob(1L, Map.of());
        assertEquals(2, fixture.insertedKeys.size());
        assertNotEquals(fixture.insertedKeys.get(0), fixture.insertedKeys.get(1));
        assertTrue(fixture.queries.stream().noneMatch(query -> query.contains("`status` = 'COMPLETED'")));
        verify(fixture.worker, times(2)).processJob(eq(123L), eq(1L), anyMap(), isNull());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void queuedSettingsAreDetachedFromCallerMutation() {
        var fixture = new Fixture();
        Map<String, Object> thresholds = new HashMap<>(Map.of("limit", 10));
        Map<String, Object> request = new HashMap<>(Map.of("kpis", thresholds));
        fixture.service.startJob(1L, request);
        thresholds.put("limit", 99);
        ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass((Class) Map.class);
        verify(fixture.worker).processJob(eq(123L), eq(1L), captured.capture(), eq("version-a"));
        assertEquals(Map.of("limit", 10), captured.getValue().get("kpis"));
    }

    @Test
    void changeBetweenCacheLookupAndReturnDoesNotServeOldResult() {
        var fixture = new Fixture();
        fixture.cached = Map.of("jobId", 7L, "status", "COMPLETED");
        when(fixture.recommendation.currentCacheKey(anyLong(), anyMap()))
                .thenReturn(Optional.of("version-a"), Optional.of("version-b"));
        var response = fixture.service.startJob(1L, Map.of());
        assertNotEquals(fixture.cached, response.get("data"));
        verify(fixture.worker).processJob(eq(123L), eq(1L), anyMap(), eq("version-a"));
    }

    @Test
    void changeWhileQueuedFailsWithoutRunningPython() throws Exception {
        var fixture = new Fixture();
        when(fixture.recommendation.currentCacheKey(anyLong(), anyMap())).thenReturn(Optional.of("version-b"));
        fixture.service.processJob(123L, 1L, Map.of(), "version-a");
        assertEquals(List.of("PROCESSING", "FAILED"), fixture.states);
        verify(fixture.recommendation, never()).runRecommendation(anyLong(), anyMap(), anyBoolean(), any());
    }

    @Test
    void changeDuringRunRejectsResultAndCleansTemporaryWork() throws Exception {
        var fixture = new Fixture();
        when(fixture.recommendation.currentCacheKey(anyLong(), anyMap()))
                .thenReturn(Optional.of("version-a"), Optional.of("version-b"));
        when(fixture.recommendation.runRecommendation(anyLong(), anyMap(), anyBoolean(), any()))
                .thenReturn(result("version-a"));
        fixture.service.processJob(123L, 1L, Map.of(), "version-a");
        assertEquals(List.of("PROCESSING", "FAILED"), fixture.states);
        verify(fixture.recommendation).cleanup(Path.of("test-work"));
    }

    @Test
    void exactInputSnapshotPreventsAbaRaceEvenWhenCurrentDataReverts() throws Exception {
        var fixture = new Fixture();
        // Current data is A, but the actual loaded input was B during an intervening replacement.
        when(fixture.recommendation.runRecommendation(anyLong(), anyMap(), anyBoolean(), any()))
                .thenReturn(result("version-b"));
        fixture.service.processJob(123L, 1L, Map.of(), "version-a");
        assertEquals(List.of("PROCESSING", "FAILED"), fixture.states);
        verify(fixture.recommendation).cleanup(Path.of("test-work"));
    }

    @Test
    void stableInputCompletesAndDatabaseOnlyFallbackCanStillRunWithoutCaching() throws Exception {
        var fixture = new Fixture();
        when(fixture.recommendation.runRecommendation(anyLong(), anyMap(), anyBoolean(), any()))
                .thenReturn(result("version-a"));
        fixture.service.processJob(123L, 1L, Map.of(), "version-a");
        assertEquals(List.of("PROCESSING", "COMPLETED"), fixture.states);
        fixture.states.clear();
        when(fixture.recommendation.runRecommendation(anyLong(), anyMap(), anyBoolean(), any()))
                .thenReturn(result(null));
        fixture.service.processJob(124L, 1L, Map.of(), null);
        assertEquals(List.of("PROCESSING", "COMPLETED"), fixture.states);
    }

    private static RecommendationService.RecommendationResult result(String key) {
        return new RecommendationService.RecommendationResult(Map.of("summary", Map.of()), null,
                Path.of("test-work"), key);
    }

    private static class Fixture {
        final List<String> states = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        final List<String> insertedKeys = new ArrayList<>();
        Map<String, Object> cached;
        boolean workerClaim = true;
        final RecommendationService recommendation = mock(RecommendationService.class);
        final RecommendationJobService worker = mock(RecommendationJobService.class);
        final RecommendationJobService service;

        @SuppressWarnings("unchecked")
        Fixture() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
                Object[] args = invocation.getArguments();
                if (invocation.getMethod().getName().equals("query")) {
                    String sql = (String) args[0];
                    queries.add(sql);
                    return sql.contains("`status` = 'COMPLETED'")
                            ? (cached == null ? List.of() : List.of(cached))
                            : List.of(Map.of("jobId", 123L, "status", "QUEUED"));
                }
                if (invocation.getMethod().getName().equals("update")) {
                    if (args[0] instanceof String sql && sql.contains("AND `status` = 'QUEUED'") && !workerClaim) return 0;
                    if (args[0] instanceof PreparedStatementCreator creator) {
                        Connection connection = mock(Connection.class);
                        PreparedStatement statement = mock(PreparedStatement.class);
                        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
                        creator.createPreparedStatement(connection);
                        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
                        verify(statement).setString(eq(2), key.capture());
                        insertedKeys.add(key.getValue());
                        ((KeyHolder) args[1]).getKeyList().add(Map.of("id", 123L));
                    } else {
                        states.add((String) args[1]);
                    }
                    return 1;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            ObjectProvider<RecommendationJobService> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(worker);
            when(recommendation.currentCacheKey(anyLong(), anyMap())).thenReturn(Optional.of("version-a"));
            service = new RecommendationJobService(jdbc, recommendation, provider);
        }
    }
}
