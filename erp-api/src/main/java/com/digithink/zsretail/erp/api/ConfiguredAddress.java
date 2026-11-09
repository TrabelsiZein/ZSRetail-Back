package com.digithink.zsretail.erp.api;

/**
 * Boot 4 migration, step 1.3b: a badly written address in a configuration file. UriComponentsBuilder.fromUriString does not
 * refuse one as fromHttpUrl did, so the calls fail later with the HTTP layer's message; for the addresses no startup check
 * refuses (the head office link's, and those of the connectors without such a check), this gives the one clear line at the
 * start. It stops nothing: an installation that started keeps starting. An empty value stays allowed and silent.
 */
public final class ConfiguredAddress {

	private ConfiguredAddress() {
	}

	/**
	 * The line to log at ERROR when the value is not empty and does not start with http:// or https:// (in any case:
	 * HTTP:// works); null when there is nothing to say.
	 */
	public static String complaint(String key, String value) {
		String address = value == null ? "" : value.trim();
		if (address.isEmpty() || address.regionMatches(true, 0, "http://", 0, 7)
				|| address.regionMatches(true, 0, "https://", 0, 8)) {
			return null;
		}
		return "The address " + key + "=" + address + " is badly written: it must start with http:// or https://."
				+ " The application starts, but every call to this address will fail.";
	}
}
