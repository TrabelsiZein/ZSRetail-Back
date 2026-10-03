package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.headoffice.model.HoReturn;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoReturnRepository extends _BaseRepository<HoReturn, Long> {

	Optional<HoReturn> findByStoreIdAndReturnNumber(Long storeId, String returnNumber);
}
