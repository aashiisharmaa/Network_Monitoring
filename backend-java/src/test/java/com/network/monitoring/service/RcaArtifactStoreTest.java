package com.network.monitoring.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class RcaArtifactStoreTest {
    @TempDir Path directory;
    @Test void validatesContentAndRejectsPathTraversal()throws Exception{
        var store=new RcaArtifactStore(directory.resolve("artifacts").toString());String key="a".repeat(64);Path file=directory.resolve("source");Files.writeString(file,"captured full analysis");
        store.publishAnalysis(key,file);Path saved=store.analysis(key);assertNotNull(saved);assertEquals("captured full analysis",Files.readString(saved));
        Files.writeString(saved,"tampered");assertNull(store.analysis(key));assertThrows(IllegalArgumentException.class,()->store.excel("../escape"));
    }
}
