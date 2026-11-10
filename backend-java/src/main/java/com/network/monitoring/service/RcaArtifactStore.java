package com.network.monitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.locks.ReentrantLock;

/** Integrity-checked full-analysis/export cache keyed by data, settings and engine identity. */
@Component
public class RcaArtifactStore {
    private final Path root;
    private final ReentrantLock[] locks = new ReentrantLock[32];
    public RcaArtifactStore(@Value("${app.rca-artifact-dir:uploads/rca-artifacts}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize(); for (int i=0;i<locks.length;i++) locks[i]=new ReentrantLock();
    }
    @FunctionalInterface public interface Work<T> { T run() throws IOException; }
    public <T> T locked(String key, Work<T> work) throws IOException { checkKey(key); var lock=locks[Math.floorMod(key.hashCode(),locks.length)];lock.lock();try{return work.run();}finally{lock.unlock();} }
    private void checkKey(String key) { if(key==null||!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Unverifiable RCA artifact key."); }
    public Path analysis(String key) { return verified(key,"analysis.json.gz"); }
    public Path excel(String key) { return verified(key,"export.xlsx"); }
    private Path verified(String key,String name) {
        checkKey(key);Path path=root.resolve(key).resolve(name), digest=path.resolveSibling(name+".sha256");
        try { if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(digest,LinkOption.NOFOLLOW_LINKS)||Files.size(digest)!=64||!path.toRealPath().startsWith(root.toRealPath()))return null;
            return Files.readString(digest).equals(RecommendationCacheKey.fileDigest(path))?path:null;
        }catch(IOException ex){return null;}
    }
    public void publishAnalysis(String key,Path source) throws IOException { publish(key,"analysis.json.gz",source,64L*1024*1024); }
    public void publishExcel(String key,Path source) throws IOException { publish(key,"export.xlsx",source,100L*1024*1024); }
    private void publish(String key,String name,Path source,long max) throws IOException {
        locked(key,()->{
            if(!Files.isRegularFile(source)||Files.size(source)>max)throw new IOException("RCA artifact is missing or exceeds its size budget.");
            Path dir=root.resolve(key);Files.createDirectories(dir);if(!dir.toRealPath().startsWith(root.toRealPath()))throw new IOException("Invalid RCA artifact directory.");
            Path temp=Files.createTempFile(dir,"pending-",".tmp"), checksum=Files.createTempFile(dir,"digest-",".tmp");
            try { Files.copy(source,temp,StandardCopyOption.REPLACE_EXISTING);Files.writeString(checksum,RecommendationCacheKey.fileDigest(temp));
                Files.move(temp,dir.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                Files.move(checksum,dir.resolve(name+".sha256"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(temp);Files.deleteIfExists(checksum);}return null;
        });
    }
}
