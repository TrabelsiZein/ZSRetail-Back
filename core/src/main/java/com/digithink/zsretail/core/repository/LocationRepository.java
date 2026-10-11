package com.digithink.zsretail.core.repository;

import java.util.Optional;

import com.digithink.zsretail.core.model.Location;

public interface LocationRepository extends _BaseRepository<Location, Long> {

	Optional<Location> findByLocationCode(String locationCode);

	Optional<Location> findByName(String name);

	Optional<Location> findByIsDefaultTrue();

	Optional<Location> findByErpExternalId(String erpExternalId);
}

