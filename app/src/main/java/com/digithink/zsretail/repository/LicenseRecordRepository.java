package com.digithink.zsretail.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.LicenseRecord;

@Repository
public interface LicenseRecordRepository extends JpaRepository<LicenseRecord, Long> {

    Optional<LicenseRecord> findByCurrentLicenseTrue();

    List<LicenseRecord> findAllByOrderByUploadedAtDesc();

    @Modifying
    @Transactional
    @Query("UPDATE LicenseRecord l SET l.currentLicense = false WHERE l.currentLicense = true")
    void clearCurrentLicense();
}
