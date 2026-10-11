package com.digithink.zsretail.repository;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.ReturnLine;

import java.util.List;

@Repository
public interface ReturnLineRepository extends _BaseRepository<ReturnLine, Long> {
	
	List<ReturnLine> findByReturnHeader(ReturnHeader returnHeader);
	
	void deleteByReturnHeader(ReturnHeader returnHeader);
}

