package com.digithink.zsretail.repository;

import java.util.Optional;

import com.digithink.zsretail.model.Location;

public interface LocationRepository extends _BaseRepository<Location, Long> {

	Optional<Location> findByLocationCode(String locationCode);

	Optional<Location> findByName(String name);

	Optional<Location> findByIsDefaultTrue();

	Optional<Location> findByErpExternalId(String erpExternalId);
}

