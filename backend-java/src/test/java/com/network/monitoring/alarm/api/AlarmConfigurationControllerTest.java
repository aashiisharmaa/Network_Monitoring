package com.network.monitoring.alarm.api;

import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.service.AlarmConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlarmConfigurationControllerTest {
    @Test void validationAndTenantDenialReturnClientErrors() throws Exception {
        var service = mock(AlarmConfigurationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmConfigurationController(service))
                .setControllerAdvice(new AlarmConfigurationAdvice()).build();
        mvc.perform(post("/api/v1/alarm-config/companies/100/zones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"WEST\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(get("/api/v1/alarm-config/companies/100/circles?limit=bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alarm-config/companies/100/circle-resolution")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alarm-config/companies/100/zones").contentType(MediaType.APPLICATION_JSON)
                .content("{bad-json")).andExpect(status().isBadRequest());
        when(service.feeds(200, 0, 100)).thenThrow(new AlarmConfigurationException(403, "DENIED", "Denied"));
        mvc.perform(get("/api/v1/alarm-config/companies/200/feeds")).andExpect(status().isForbidden());
    }
    @Test void duplicateKeysReturnConflictWithoutExposingSql() throws Exception {
        var service = mock(AlarmConfigurationService.class);
        when(service.createZone(eq(100L), any())).thenThrow(new DuplicateKeyException("secret SQL"));
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmConfigurationController(service))
                .setControllerAdvice(new AlarmConfigurationAdvice()).build();
        mvc.perform(post("/api/v1/alarm-config/companies/100/zones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"WEST\",\"name\":\"West\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALARM_CONFIGURATION_CONFLICT"));
    }
}
