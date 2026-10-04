package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoPriceListRepository extends _BaseRepository<HoPriceList, Long> {

	Optional<HoPriceList> findByCodeIgnoreCase(String code);
}
