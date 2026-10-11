package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.model.SessionCashCount;
import com.digithink.zsretail.repository.SessionCashCountRepository;

@Service
public class SessionCashCountService extends _BaseService<SessionCashCount, Long> {

	@Autowired
	private SessionCashCountRepository sessionCashCountRepository;

	@Override
	protected _BaseRepository<SessionCashCount, Long> getRepository() {
		return sessionCashCountRepository;
	}
}

