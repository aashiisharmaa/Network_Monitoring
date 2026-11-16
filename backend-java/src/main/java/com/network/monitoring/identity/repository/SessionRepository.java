package com.network.monitoring.identity.repository;

import com.network.monitoring.identity.entity.Session;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, Long> {
    Optional<Session> findByToken(String token);
    void deleteByToken(String token);
}
