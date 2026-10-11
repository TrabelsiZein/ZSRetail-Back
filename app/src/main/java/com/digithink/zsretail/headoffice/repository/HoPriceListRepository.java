package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoPriceList;

public interface HoPriceListRepository extends _BaseRepository<HoPriceList, Long> {

	Optional<HoPriceList> findByCodeIgnoreCase(String code);
}
