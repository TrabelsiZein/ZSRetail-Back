package com.digithink.zsretail.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.model.AppVersion;

public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {
}
