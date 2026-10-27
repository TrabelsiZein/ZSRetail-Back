package com.digithink.zsretail.erp.api;

/**
 * 3.1.1: the connector in effect cannot do what was asked (e.g. read the variants' stock): the default of an
 * {@link ErpConnector} method that only some connectors implement. The head office answers it as unavailable (503).
 */
public class ErpNotSupportedException extends RuntimeException {

	public ErpNotSupportedException(String message) {
		super(message);
	}
}
