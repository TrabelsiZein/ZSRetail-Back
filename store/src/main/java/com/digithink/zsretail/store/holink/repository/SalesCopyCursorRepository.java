package com.digithink.zsretail.store.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.store.holink.model.SalesCopyCursor;

public interface SalesCopyCursorRepository extends _BaseRepository<SalesCopyCursor, Long> {

	Optional<SalesCopyCursor> findByDocumentType(SalesCopyType documentType);
}
