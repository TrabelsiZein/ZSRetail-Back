package com.digithink.zsretail.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopyCursor;
import com.digithink.zsretail.repository._BaseRepository;

public interface SalesCopyCursorRepository extends _BaseRepository<SalesCopyCursor, Long> {

	Optional<SalesCopyCursor> findByDocumentType(SalesCopyType documentType);
}
