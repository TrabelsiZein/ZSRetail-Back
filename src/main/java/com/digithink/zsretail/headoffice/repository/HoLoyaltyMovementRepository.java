package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoLoyaltyMovementRepository extends _BaseRepository<HoLoyaltyMovement, Long> {

	Optional<HoLoyaltyMovement> findByStoreIdAndStoreKey(Long storeId, String storeKey);
}
