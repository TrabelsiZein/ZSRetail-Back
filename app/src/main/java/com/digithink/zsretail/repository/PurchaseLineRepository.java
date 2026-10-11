package com.digithink.zsretail.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.PurchaseHeader;
import com.digithink.zsretail.model.PurchaseLine;

@Repository
public interface PurchaseLineRepository extends _BaseRepository<PurchaseLine, Long> {

	List<PurchaseLine> findByPurchaseHeader(PurchaseHeader purchaseHeader);

	void deleteByPurchaseHeader(PurchaseHeader purchaseHeader);
}
