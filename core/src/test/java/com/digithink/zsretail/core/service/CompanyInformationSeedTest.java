package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.CompanyInformation;
import com.digithink.zsretail.core.repository.CompanyInformationRepository;

/**
 * Boot 4 migration, step 1.3: the one row is created without an id, so IDENTITY gives it. With id=1 and no row, Hibernate 6.6
 * throws where Hibernate 5 inserted, and a new database would not start. The rest of the application reads the first row.
 */
class CompanyInformationSeedTest {

	private final CompanyInformationRepository repository = mock(CompanyInformationRepository.class);
	private final CompanyInformationService service = new CompanyInformationService(repository);

	private CompanyInformation saved() {
		ArgumentCaptor<CompanyInformation> saved = ArgumentCaptor.forClass(CompanyInformation.class);
		verify(repository).save(saved.capture());
		return saved.getValue();
	}

	@Test
	@DisplayName("A new database: the row is created without an id")
	void createdWithoutAnId() {
		when(repository.count()).thenReturn(0L);
		when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

		service.ensureExists();

		assertNull(saved().getId());
	}

	@Test
	@DisplayName("A database that has it, whatever its id: nothing created")
	void alreadyThere() {
		when(repository.count()).thenReturn(1L);

		service.ensureExists();

		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("Read and updated: the first row, whatever its id")
	void theFirstRow() {
		CompanyInformation row = new CompanyInformation();
		row.setId(7L);
		row.setCompanyName("Before");
		when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(row));
		when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

		assertEquals("Before", service.get().getCompanyName());

		CompanyInformationDTO change = new CompanyInformationDTO();
		change.setCompanyName("After");
		service.update(change, "admin");
		assertEquals(Long.valueOf(7L), saved().getId());
		assertEquals("After", row.getCompanyName());
		assertNotNull(row.getUpdatedAt());
	}
}
