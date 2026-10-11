package com.digithink.zsretail.core.repository;

import java.util.List;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;

public interface SalesLineRepository extends _BaseRepository<SalesLine, Long> {

	List<SalesLine> findBySalesHeader(SalesHeader salesHeader);

	List<SalesLine> findByItem(Item item);
	
	void deleteBySalesHeader(SalesHeader salesHeader);
}

