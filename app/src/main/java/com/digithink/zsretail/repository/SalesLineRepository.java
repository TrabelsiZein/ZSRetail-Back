package com.digithink.zsretail.repository;

import java.util.List;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;

public interface SalesLineRepository extends _BaseRepository<SalesLine, Long> {

	List<SalesLine> findBySalesHeader(SalesHeader salesHeader);

	List<SalesLine> findByItem(Item item);
	
	void deleteBySalesHeader(SalesHeader salesHeader);
}

