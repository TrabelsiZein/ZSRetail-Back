package com.digithink.zsretail.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import com.digithink.zsretail.model.Vendor;

public interface VendorRepository extends _BaseRepository<Vendor, Long> {

	Optional<Vendor> findByVendorCode(String vendorCode);

	long countByCreatedAtGreaterThanEqual(LocalDateTime date);
}
