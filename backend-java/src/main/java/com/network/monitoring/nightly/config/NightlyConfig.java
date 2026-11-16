package com.network.monitoring.nightly.config;

import com.network.monitoring.nightly.service.NightlyProfile;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class NightlyConfig {
    @Bean("nightlyClock") public Clock nightlyClock() { return Clock.system(NightlyProfile.ZONE); }
}
