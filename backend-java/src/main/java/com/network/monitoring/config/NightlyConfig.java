package com.network.monitoring.config;

import com.network.monitoring.service.NightlyProfile;
import org.springframework.context.annotation.*;
import java.time.Clock;

@Configuration
public class NightlyConfig {
    @Bean("nightlyClock") public Clock nightlyClock() { return Clock.system(NightlyProfile.ZONE); }
}
