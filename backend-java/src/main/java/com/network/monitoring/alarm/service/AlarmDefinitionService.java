package com.network.monitoring.alarm.service;

import com.network.monitoring.alarm.dto.AlarmModels.Catalog;
import com.network.monitoring.alarm.dto.AlarmModels.Site;
import com.network.monitoring.alarm.exception.AlarmConfigurationException;
import com.network.monitoring.alarm.repository.AlarmConfigurationRepository;
import com.network.monitoring.alarm.repository.AlarmDefinitionRepository;
import com.network.monitoring.alarm.security.AlarmTenantAccess;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.*;
import static com.network.monitoring.alarm.support.AlarmValues.required;

/** Customer catalogue and physical-site decisions, separate from import orchestration. */
@Service
public class AlarmDefinitionService {
    private final AlarmTenantAccess access;
    private final AlarmConfigurationRepository master;
    private final AlarmDefinitionRepository repository;
    private final TransactionTemplate tx;

    public AlarmDefinitionService(AlarmTenantAccess access, AlarmConfigurationRepository master,
                                 AlarmDefinitionRepository repository, PlatformTransactionManager transactions) {
        this.access = access; this.master = master; this.repository = repository;
        this.tx = new TransactionTemplate(transactions);
    }

    public List<Catalog> catalogs(long company) { access.require(company, false); return repository.catalogs(company); }
    List<Catalog> snapshotCatalogs(long company) { return repository.catalogs(company); }
    List<Site> snapshotSites(long company, long feed) { return repository.sites(company, feed); }

    public Catalog addCatalog(long company, Catalog rule) {
        long actor = access.require(company, true);
        if (rule.included() == null || rule.serviceAffecting() == null || rule.siteDown() == null)
            throw invalid("Explicit inclusion, service-affecting and site-down flags are required.");
        String oem = required(rule.oem(), 16).toUpperCase(Locale.ROOT);
        String rat = required(rule.technology(), 2).toUpperCase(Locale.ROOT);
        String field = required(rule.matchField(), 8).toUpperCase(Locale.ROOT);
        String severity = required(rule.classification(), 16).toUpperCase(Locale.ROOT);
        if (!Set.of("NOKIA", "ERICSSON", "ZTE", "HUAWEI", "SAMSUNG").contains(oem)
                || !Set.of("2G", "4G").contains(rat) || !Set.of("CODE", "TEXT").contains(field)
                || !Set.of("CRITICAL", "MAJOR", "MINOR").contains(severity))
            throw invalid("Use supported OEM/RAT, CODE or TEXT matching and CRITICAL/MAJOR/MINOR classification.");
        var normalized = new Catalog(0, oem, rat, field, required(rule.matchValue(), 255), severity,
                rule.included(), rule.serviceAffecting(), rule.siteDown());
        return tx.execute(status -> {
            master.lockCompany(company);
            if (repository.catalogs(company).size() >= 1000) throw invalid("Catalogue limit reached.");
            long id = repository.insertCatalog(company, normalized, actor);
            return new Catalog(id, oem, rat, field, normalized.matchValue(), severity,
                    rule.included(), rule.serviceAffecting(), rule.siteDown());
        });
    }

    public void removeCatalog(long company, long id) {
        access.require(company, true);
        if (repository.deleteCatalog(company, id) != 1) throw missing();
    }

    public List<Site> sites(long company, long feed) {
        access.require(company, false); master.feed(company, feed).orElseThrow(AlarmConfigurationException::missing);
        return repository.sites(company, feed);
    }

    public Site addSite(long company, long feed, Site site) {
        long actor = access.require(company, true);
        return tx.execute(status -> {
            master.lockCompany(company);
            master.feed(company, feed).orElseThrow(AlarmConfigurationException::missing);
            master.circle(company, site.circleId()).orElseThrow(AlarmConfigurationException::missing);
            var normalized = new Site(0, site.circleId(), required(site.objectKey(), 512), required(site.siteCode(), 128));
            if (repository.sites(company, feed).size() >= 20000) throw invalid("Site mapping limit reached.");
            return new Site(repository.insertSite(company, feed, normalized, actor), normalized.circleId(), normalized.objectKey(), normalized.siteCode());
        });
    }

    public void removeSite(long company, long feed, long id) {
        access.require(company, true);
        if (repository.deleteSite(company, feed, id) != 1) throw missing();
    }
}
