package com.digithink.zsretail.holink.enumeration;

/** Direction of an exchange with the head office (task 2.6). */
public enum ExchangeDirection {

	/** Store to head office: heartbeat, sales copies. */
	UP,

	/** Head office to store: copies down (later steps). */
	DOWN
}
