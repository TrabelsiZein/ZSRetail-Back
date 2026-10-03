package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.headoffice.model.HoSession;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoSessionRepository extends _BaseRepository<HoSession, Long> {

	Optional<HoSession> findByStoreIdAndSessionNumber(Long storeId, String sessionNumber);
}
