package com.digithink.zsretail.store.rfid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.store.StoreApplication;
import com.digithink.zsretail.store.rfid.repository.RfidSaleTagRepository;
import com.digithink.zsretail.store.rfid.repository.RfidTagRepository;

/**
 * Boot 4 migration, step 1.2: a store with RFID on really starts. Its four repositories are the only ones behind a condition
 * (the other 184 of the 188 @Query are checked by every start), and no reader is connected: a reader is only reached when
 * it is called.
 */
class RfidStoreStartupTest {

	@Test
	@DisplayName("With rfid.enabled=true the RFID repositories and their @Query are built on the real SQL Server")
	void startsWithRfid() {
		try (MigTestApplication store = MigTestApplication.start(StoreApplication.class, "store", "rfid.enabled=true")) {
			assertEquals(0, store.bean(RfidTagRepository.class).count());
			assertEquals(0, store.bean(RfidSaleTagRepository.class).count());
		}
	}
}
