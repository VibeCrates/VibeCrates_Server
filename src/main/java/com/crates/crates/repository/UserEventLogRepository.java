package com.crates.crates.repository;

import com.crates.crates.entity.UserEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserEventLogRepository extends JpaRepository<UserEventLog, Long> {
}
