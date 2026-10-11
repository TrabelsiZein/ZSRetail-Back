package com.digithink.zsretail.core.security;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

/**
 * The store-to-head-office API (/ho/**): the authority a store gets once its key is accepted, and the refusal every
 * other request gets. Used by the /ho/** chain (HeadOfficeApiSecurityConfig, both installation types) and by the
 * head office's key filter (StoreApiKeyFilter). Code cleanup step 5: was in StoreApiKeyFilter.
 */
public final class StoreApiAccess {

	public static final String STORE_AUTHORITY = "HO_STORE";
	public static final String REFUSAL_BODY = "{\"code\":401,\"msg\":\"Invalid store credentials.\"}";

	private StoreApiAccess() {
	}

	/** 401 with the refusal body. */
	public static void writeRefusal(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(REFUSAL_BODY);
	}
}
