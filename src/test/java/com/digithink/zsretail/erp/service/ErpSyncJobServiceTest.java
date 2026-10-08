package com.digithink.zsretail.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;

/** The status of a run is cut to erp_sync_job.last_status (100), so a long error message is saved, not lost. */
class ErpSyncJobServiceTest {

	private final ErpSyncJobRepository repository = mock(ErpSyncJobRepository.class);
	private final ErpSyncJobService service = new ErpSyncJobService(repository);

	private String mark(String status) {
		ErpSyncJob job = new ErpSyncJob();
		LocalDateTime at = LocalDateTime.of(2026, 10, 8, 10, 0);
		service.markExecution(job, status, at, at.plusHours(1));
		verify(repository).save(job);
		assertEquals(at, job.getLastRunAt());
		assertEquals(at.plusHours(1), job.getNextRunAt());
		return job.getLastStatus();
	}

	@Test
	@DisplayName("A long status is cut to 100 characters; a short one, exactly 100, or null is kept as it is")
	void statusCut() {
		StringBuilder message = new StringBuilder("ERROR: navpospages: reading the page ItemBarCodePOS failed: ");
		while (message.length() < 400) {
			message.append("a long message from the ERP ");
		}
		String cut = mark(message.toString());
		assertEquals(100, cut.length());
		assertEquals(message.substring(0, 100), cut);
		assertEquals("SUCCESS", new ErpSyncJobServiceTest().mark("SUCCESS"));
		String exactly = message.substring(0, 100);
		assertEquals(exactly, new ErpSyncJobServiceTest().mark(exactly));
		assertNull(new ErpSyncJobServiceTest().mark(null));
		assertEquals(100, ErpSyncJobService.LAST_STATUS_LENGTH);
	}
}
