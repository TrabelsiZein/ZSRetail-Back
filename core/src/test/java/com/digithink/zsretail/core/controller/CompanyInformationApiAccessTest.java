package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CompanyInformationService;

/**
 * PUT /company-info behind the page's permission: write:admin-company-information on a store, the head office page's one key
 * on a head office. Before it any logged user saved, and an anonymous call answered 500. The GET stays public.
 */
class CompanyInformationApiAccessTest {

	private final CompanyInformationService service = mock(CompanyInformationService.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final ApplicationModeService mode = mock(ApplicationModeService.class);
	private final CompanyInformationAPI api = new CompanyInformationAPI(service, current, mode);

	private int put(boolean headOffice, String... keys) {
		Set<String> held = new HashSet<>(Arrays.asList(keys));
		when(mode.isHeadOffice()).thenReturn(headOffice);
		when(current.currentUserHasPermission(anyString())).thenAnswer(call -> held.contains(call.getArgument(0)));
		UserAccount user = new UserAccount();
		user.setUsername("someone");
		when(current.getCurrentUser()).thenReturn(user);
		return api.update(new CompanyInformationDTO()).getStatusCode().value();
	}

	@Test
	@DisplayName("A store: the write key saves; the read key alone, no key or anonymous are refused, nothing saved")
	void store() {
		assertEquals(403, put(false));
		assertEquals(403, put(false, "read:admin-company-information"));
		assertEquals(403, put(false, CompanyInformationAPI.HEAD_OFFICE_COMPANY));
		verify(service, never()).update(any(), any());

		assertEquals(200, put(false, CompanyInformationAPI.WRITE_COMPANY));
		verify(service).update(any(), any());
	}

	@Test
	@DisplayName("A head office: its page's one key saves; the store's key does not")
	void headOffice() {
		assertEquals(403, put(true));
		assertEquals(403, put(true, CompanyInformationAPI.WRITE_COMPANY));
		verify(service, never()).update(any(), any());

		assertEquals(200, put(true, CompanyInformationAPI.HEAD_OFFICE_COMPANY));
		verify(service).update(any(), any());
	}

	@Test
	@DisplayName("Reading stays public: no right asked")
	void readingStaysPublic() {
		when(service.get()).thenReturn(new CompanyInformationDTO());
		assertEquals(200, api.get().getStatusCode().value());
		verify(current, never()).currentUserHasPermission(anyString());
	}
}
