package com.digithink.zsretail.core.communication.service;

import java.util.Map;

/**
 * 3.3.0, communication step 6: the stores of a head office by code, with their names, for the Communication page (the
 * store column and filter of the messages and of the events left out). Answered by the head office; absent on a store
 * (its page has no store column).
 */
public interface CommunicationStoreNames {

	/** Every store of the network: code to name, by code. */
	Map<String, String> names();
}
