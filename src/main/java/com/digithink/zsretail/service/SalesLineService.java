package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class SalesLineService extends _BaseService<SalesLine, Long> {

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Override
	protected _BaseRepository<SalesLine, Long> getRepository() {
		return salesLineRepository;
	}
}

