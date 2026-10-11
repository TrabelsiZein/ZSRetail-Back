package com.digithink.zsretail.core.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.MemberFunction;
import com.digithink.zsretail.core.repository.MemberFunctionRepository;
import com.digithink.zsretail.core.repository._BaseRepository;

@Service
public class MemberFunctionService extends _BaseService<MemberFunction, Long> {

	@Autowired
	private MemberFunctionRepository memberFunctionRepository;

	@Override
	protected _BaseRepository<MemberFunction, Long> getRepository() {
		return memberFunctionRepository;
	}
}
