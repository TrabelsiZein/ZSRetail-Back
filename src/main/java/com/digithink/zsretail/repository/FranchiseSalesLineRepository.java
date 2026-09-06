package com.digithink.zsretail.repository;

import java.util.List;

import com.digithink.zsretail.model.FranchiseSalesLine;

public interface FranchiseSalesLineRepository extends _BaseRepository<FranchiseSalesLine, Long> {

	List<FranchiseSalesLine> findByHeaderId(Long headerId);
}
