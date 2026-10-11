package com.digithink.zsretail.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.core.model.AppVersion;

public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {
}
