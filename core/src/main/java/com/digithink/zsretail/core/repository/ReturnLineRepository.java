package com.digithink.zsretail.core.repository;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;

import java.util.List;

@Repository
public interface ReturnLineRepository extends _BaseRepository<ReturnLine, Long> {
	
	List<ReturnLine> findByReturnHeader(ReturnHeader returnHeader);

	/** 3.1.0, day journal: the lines of several returns in one query (never called with an empty list). */
	List<ReturnLine> findByReturnHeaderIn(List<ReturnHeader> returnHeaders);
	
	void deleteByReturnHeader(ReturnHeader returnHeader);
}

