package com.digithink.zsretail.holink.repository;

import java.util.Collection;
import java.util.List;

import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopy;
import com.digithink.zsretail.repository._BaseRepository;

public interface SalesCopyRepository extends _BaseRepository<SalesCopy, Long> {

	List<SalesCopy> findByDocumentTypeAndLocalIdIn(SalesCopyType documentType, Collection<Long> localIds);
}
