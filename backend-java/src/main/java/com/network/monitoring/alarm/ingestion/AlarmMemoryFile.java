package com.network.monitoring.alarm.ingestion;
import java.io.*;
import java.nio.file.*;
import org.springframework.web.multipart.MultipartFile;
/** Bounded in-memory adapter shared by manual replay and approved-folder discovery. */
public record AlarmMemoryFile(String filename, byte[] data) implements MultipartFile {
        public String getName() { return "file"; } public String getOriginalFilename() { return filename; }
        public String getContentType() { return "application/octet-stream"; } public boolean isEmpty() { return data.length == 0; }
        public long getSize() { return data.length; } public byte[] getBytes() { return data; }
        public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        public void transferTo(File file) throws IOException { Files.write(file.toPath(), data); }
    }
