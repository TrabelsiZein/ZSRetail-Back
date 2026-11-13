package com.digithink.zsretail.core.exception;

import java.util.Map;

/**
 * 3.2.0, POS machines step 2: a refusal that carries a code and the values of its message besides its English text. An
 * endpoint answers it as {"error": the text, "code", "params"}, and the front shows serverErrors.&lt;code&gt; with the
 * values in the language of the screen (ZSRetail-Front src/libs/server-messages.js). Implemented by an exception of an
 * application (e.g. the store's drawer rules), read by core's endpoints (GeneralSetupAPI) without naming it.
 */
public interface CodedRefusal {

	/** The message's code, e.g. CASH_DRAWER_OWNER_SESSIONS_OPEN. */
	String getCode();

	/** The values of the message ({count}, {name}...). */
	Map<String, Object> getParams();
}
