package com.digithink.zsretail.store.rfid.reader;

import java.util.List;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.model.RfidReader;

/**
 * RFID step 4: what the back end asks of an RFID reader. One implementation talks to the Hopeland readers with the
 * vendor's SDK (the only class that uses it); the tests use a fake one (store.support.FakeRfidReader). The caller
 * (RfidReadService) makes sure a reader serves one request at a time. Antenna 1 always.
 * <p>
 * Every failure is an {@link RfidException}: UNREACHABLE when the reader does not answer at its address and port,
 * READER_ERROR when it answers with an error.
 */
public interface RfidReaderDevice {

	/** The antenna every read uses. */
	int ANTENNA = 1;

	/** Connects to the reader, asks its information (model, firmware), disconnects: the text the reader gives. */
	String testConnection(RfidReader reader);

	/**
	 * Connects, sets the reader's power on {@link #ANTENNA} when it differs from {@link RfidReader#getPower()}, reads for
	 * the given time, stops, disconnects: every tag seen, once ({@link RfidTagCollector}).
	 */
	List<RfidTag> read(RfidReader reader, int millis);
}
