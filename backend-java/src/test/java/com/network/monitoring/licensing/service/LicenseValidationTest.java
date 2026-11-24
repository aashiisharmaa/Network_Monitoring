package com.network.monitoring.licensing.service;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class LicenseValidationTest {
    @TempDir Path directory;
    final LicenseFileService signatures=new LicenseFileService("synthetic-test-signing-secret-only");
    private Map<String,Object> payload(LocalLicenseService local,int start,int end,String status) {
        LocalDate now=LocalDate.now(ZoneOffset.UTC);
        return new HashMap<>(Map.of("status",status,"deviceId",local.currentDeviceId(),
                "validFrom",now.plusDays(start).toString(),"expiresAt",now.plusDays(end).toString()));
    }
    private MockMultipartFile file(Map<String,Object> payload) {
        return new MockMultipartFile("file","synthetic-license.dat","application/json",signatures.createLicenseFile(payload));
    }
    @Test void missingAndValidLicenseAndFileTamperingAreRecognized() throws Exception {
        Path path=directory.resolve("license.dat");var local=new LocalLicenseService(path.toString(),signatures);
        assertEquals(false,local.status().get("valid"));
        assertEquals(true,local.uploadLicense(file(payload(local,-1,2,"ACTIVE"))).get("valid"));
        Files.writeString(path,"tampered");assertEquals(false,local.status().get("valid"));
    }
    @Test void expiredFutureRevokedAndWrongDeviceInputsCannotReplaceValidLicense() throws Exception {
        Path path=directory.resolve("license.dat");var local=new LocalLicenseService(path.toString(),signatures);
        local.uploadLicense(file(payload(local,-1,2,"ACTIVE")));byte[] installed=Files.readAllBytes(path);
        var wrongDevice=payload(local,-1,2,"ACTIVE");wrongDevice.put("deviceId","SYNTHETIC-OTHER-DEVICE");
        for(var invalid:List.of(payload(local,-3,-1,"ACTIVE"),payload(local,1,2,"ACTIVE"),payload(local,-1,2,"REVOKED"),wrongDevice)) {
            assertThrows(IllegalArgumentException.class,()->local.uploadLicense(file(invalid)));
            assertArrayEquals(installed,Files.readAllBytes(path));
        }
    }
    @Test void signaturesFromAnotherInstallationAreRejected() {
        var other=new LicenseFileService("different-synthetic-key");
        byte[] bytes=signatures.createLicenseFile(Map.of("status","ACTIVE"));
        assertThrows(IllegalArgumentException.class,()->other.verifyLicenseFile(bytes));
    }
}
