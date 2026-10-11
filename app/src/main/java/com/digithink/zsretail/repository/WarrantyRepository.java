package com.digithink.zsretail.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.Warranty;

@Repository
public interface WarrantyRepository extends _BaseRepository<Warranty, Long> {

	List<Warranty> findBySalesHeader(SalesHeader salesHeader);

	List<Warranty> findBySalesLine(SalesLine salesLine);
}
