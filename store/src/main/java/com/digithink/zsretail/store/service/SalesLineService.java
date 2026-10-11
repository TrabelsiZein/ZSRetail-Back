package com.digithink.zsretail.store.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;

@Service
public class SalesLineService extends _BaseService<SalesLine, Long> {

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Override
	protected _BaseRepository<SalesLine, Long> getRepository() {
		return salesLineRepository;
	}
}

