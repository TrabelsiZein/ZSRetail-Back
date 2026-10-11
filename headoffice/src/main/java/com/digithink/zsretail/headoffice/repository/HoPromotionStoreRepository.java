package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoPromotionStore;

public interface HoPromotionStoreRepository extends _BaseRepository<HoPromotionStore, Long> {

	List<HoPromotionStore> findByPromotionId(Long promotionId);

	List<HoPromotionStore> findByPromotionIdIn(Collection<Long> promotionIds);
}
