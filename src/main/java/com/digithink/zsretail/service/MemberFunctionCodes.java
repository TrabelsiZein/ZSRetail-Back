package com.digithink.zsretail.service;

import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.repository.MemberFunctionRepository;

/**
 * Head office plan, step 4: a member's function travels by code between a store and its head office. The receiving
 * side uses its own function of that code, and creates it (code and name as received) when it has none, so a member is
 * never refused for a function the other side added.
 */
public final class MemberFunctionCodes {

	private MemberFunctionCodes() {
	}

	/** The function of this code, created when missing; null when the code is blank. */
	public static MemberFunction resolve(MemberFunctionRepository repository, String code, String name) {
		if (code == null || code.trim().isEmpty()) {
			return null;
		}
		String trimmed = code.trim();
		return repository.findByCode(trimmed).orElseGet(() -> {
			MemberFunction function = new MemberFunction();
			function.setCode(trimmed);
			function.setName(name == null || name.trim().isEmpty() ? trimmed : name.trim());
			function.setDisplayOrder(0);
			function.setCreatedBy("HEAD_OFFICE_LINK");
			return repository.save(function);
		});
	}
}
