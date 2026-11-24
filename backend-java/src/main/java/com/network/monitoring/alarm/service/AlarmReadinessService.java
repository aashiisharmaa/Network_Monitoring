package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.repository.AlarmReadinessRepository;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import com.network.monitoring.alarm.storage.AlarmInputFolders;
import com.network.monitoring.platform.mail.config.ReportMailSettings;
import java.util.*;
import org.springframework.stereotype.Service;

/** Exposes readiness facts without contacting SMTP, opening customer files or changing schemas. */
@Service
public class AlarmReadinessService {
    private final AlarmTenantAccess access;
    private final AlarmReadinessRepository repository;
    private final AlarmWorkflowService workflow;
    private final AlarmOperationalService operational;
    private final AlarmInputFolders folders;
    private final ReportMailSettings mail;
    public AlarmReadinessService(AlarmTenantAccess access,AlarmReadinessRepository repository,AlarmWorkflowService workflow,
            AlarmOperationalService operational,AlarmInputFolders folders,ReportMailSettings mail) {
        this.access=access;this.repository=repository;this.workflow=workflow;this.operational=operational;this.folders=folders;this.mail=mail;
    }
    public Map<String,Object> inspect(long company) {
        access.require(company,false);
        var result=new LinkedHashMap<>(repository.inspect(company));
        result.put("workflowEnabled",workflow.enabled());result.put("operationalInputsEnabled",operational.enabled());
        result.put("inputFolderConfigured",folders.configured());result.put("smtpOutboundEnabled",mail.isOutboundEnabled());
        result.put("automaticClearingEnabled",false);result.put("deltaProcessingSupported",false);
        result.put("note","Schema checks do not establish customer rule approval, provider delivery, native OEM compatibility or UAT acceptance.");
        return result;
    }
}
