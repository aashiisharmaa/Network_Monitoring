package com.network.monitoring.identity.repository;

import com.network.monitoring.identity.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
}
