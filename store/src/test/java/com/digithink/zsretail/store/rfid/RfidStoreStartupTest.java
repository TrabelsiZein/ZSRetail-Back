package com.digithink.zsretail.store.rfid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.core.support.QuerySweep;
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
			// Step 1.8: every @Query of this shape on SQL Server, the RFID ones with it: 80 of core, 41 of the store, twice (QuerySweep)
			List<String> sweep = QuerySweep.run(store);
			assertEquals("calls 242", sweep.get(0), String.join("\n", QuerySweep.queries(store)));
			assertEquals(Collections.emptyList(), sweep.subList(1, sweep.size()), String.join("\n", sweep));
		}
	}
}
