package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.UserAccountDTO;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.core.service.UserAccountService;

/**
 * /user-account behind the permissions of the Users pages, under Spring MVC. Before them a cashier, on the running
 * application, read every badge code, generated the administrator's badge and set the administrator's password, then logged
 * in with it. POST /badge/scan stays open, the till needs it with a real badge (TillRightsEndToEndTest): what closes that
 * path is that the code can no longer be read here, nor in an entity (UserAccountSecretsJsonTest).
 */
class UserAccountApiAccessTest {

	private final UserAccountService users = mock(UserAccountService.class);
	private final BadgeService badges = mock(BadgeService.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final ApplicationModeService mode = mock(ApplicationModeService.class);
	private final UserAccountRepository accounts = mock(UserAccountRepository.class);

	private final UserAccount target = new UserAccount();
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		UserAccountAPI api = new UserAccountAPI();
		ReflectionTestUtils.setField(api, "userAccountService", users);
		ReflectionTestUtils.setField(api, "badgeService", badges);
		ReflectionTestUtils.setField(api, "currentUserProvider", current);
		ReflectionTestUtils.setField(api, "applicationModeService", mode);
		ReflectionTestUtils.setField(api, "accountRepository", accounts);
		mvc = MockMvcBuilders.standaloneSetup(api).build();

		target.setId(1L);
		target.setUsername("admin");
		target.setBadgeCode("THE-BADGE-CODE");
		UserAccount actor = new UserAccount();
		actor.setId(9L);
		actor.setUsername("actor");
		actor.setBadgeCode("MY-OWN-BADGE-CODE");
		when(current.getCurrentUser()).thenReturn(actor);
		when(users.findById(any())).thenReturn(Optional.of(target));
		when(users.save(any())).thenAnswer(call -> call.getArgument(0));
		when(users.getAllUsers()).thenReturn(Collections.singletonList(UserAccountDTO.fromEntity(target)));
		when(users.getUserById(any())).thenReturn(UserAccountDTO.fromEntity(target));
		when(badges.generateBadgeCode(any())).thenReturn("A-NEW-BADGE-CODE");
		when(accounts.findAll()).thenReturn(Collections.singletonList(target));
	}

	private void roleHolds(String... keys) {
		Set<String> held = new HashSet<>(Arrays.asList(keys));
		when(current.currentUserHasPermission(anyString())).thenAnswer(call -> held.contains(call.getArgument(0)));
	}

	private int status(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse().getStatus();
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static final String NEW_PASSWORD = "{\"password\":\"probe-password\"}";

	@Test
	@DisplayName("A cashier: the list, a user, the admin's badge and the admin's password all refused, nothing touched")
	void theCashiersCalls() throws Exception {
		roleHolds();
		assertEquals(403, status(get("/user-account")));
		assertEquals(403, status(get("/user-account/2")));
		assertEquals(403, status(post("/user-account/1/badge/generate")));
		assertEquals(403, status(json(put("/user-account/1"), NEW_PASSWORD)));
		verifyNoInteractions(users, badges);
		assertTrue(target.getPassword() == null, "the password stays as it was");
		assertEquals("THE-BADGE-CODE", target.getBadgeCode());
	}

	@Test
	@DisplayName("A cashier: creating, deleting, switching off, revoking and reactivating refused too")
	void theOtherCalls() throws Exception {
		roleHolds();
		assertEquals(403, status(json(post("/user-account"), "{\"username\":\"x\",\"password\":\"y\",\"appRoleId\":1}")));
		assertEquals(403, status(delete("/user-account/1")));
		assertEquals(403, status(put("/user-account/1/toggle-status")));
		assertEquals(403, status(json(post("/user-account/1/badge/revoke"), "{\"reason\":\"x\"}")));
		assertEquals(403, status(post("/user-account/1/badge/reactivate")));
		verifyNoInteractions(users, badges);
	}

	@Test
	@DisplayName("An administrator: the same calls answer 200, the badge code included, and the password is set")
	void anAdministrator() throws Exception {
		roleHolds(UserAccountAPI.READ_USERS, UserAccountAPI.WRITE_USERS, UserAccountAPI.DELETE_USERS);
		assertEquals(200, status(get("/user-account")));
		String one = mvc.perform(get("/user-account/1").accept(MediaType.APPLICATION_JSON)).andReturn().getResponse()
				.getContentAsString();
		assertTrue(one.contains("THE-BADGE-CODE"), "the Users page reads it here: " + one);
		String list = mvc.perform(get("/user-account").accept(MediaType.APPLICATION_JSON)).andReturn().getResponse()
				.getContentAsString();
		assertTrue(list.contains("THE-BADGE-CODE"), "and in the list, for its badge column and buttons: " + list);
		String generated = mvc.perform(post("/user-account/1/badge/generate").accept(MediaType.APPLICATION_JSON)).andReturn()
				.getResponse().getContentAsString();
		assertTrue(generated.contains("\"badgeCode\":\"A-NEW-BADGE-CODE\""), "the new code, to print the badge: " + generated);
		assertEquals(200, status(json(put("/user-account/1"), NEW_PASSWORD)));
		assertEquals("probe-password", target.getPassword());
		assertEquals(200, status(put("/user-account/1/toggle-status")));
		assertEquals(200, status(json(post("/user-account/1/badge/revoke"), "{\"reason\":\"x\"}")));
		assertEquals(200, status(post("/user-account/1/badge/reactivate")));
		assertEquals(200, status(delete("/user-account/1")));
		assertEquals(201, status(json(post("/user-account"), "{\"username\":\"x\",\"password\":\"y\",\"appRoleId\":1}")));
	}

	@Test
	@DisplayName("A role with the read key only: reads, changes nothing")
	void readOnlyRole() throws Exception {
		roleHolds(UserAccountAPI.READ_USERS);
		assertEquals(200, status(get("/user-account")));
		assertEquals(200, status(get("/user-account/1")));
		assertEquals(403, status(json(put("/user-account/1"), NEW_PASSWORD)));
		assertEquals(403, status(post("/user-account/1/badge/generate")));
		assertEquals(403, status(delete("/user-account/1")));
		verify(users, never()).save(any());
	}

	@Test
	@DisplayName("A head office: its page's one key opens every call; on a store that key opens nothing")
	void headOfficeKey() throws Exception {
		roleHolds(UserAccountAPI.HEAD_OFFICE_USERS);

		when(mode.isHeadOffice()).thenReturn(false);
		assertEquals(403, status(get("/user-account")));
		assertEquals(403, status(json(put("/user-account/1"), NEW_PASSWORD)));

		when(mode.isHeadOffice()).thenReturn(true);
		assertEquals(200, status(get("/user-account")));
		assertEquals(200, status(json(put("/user-account/1"), NEW_PASSWORD)));
		assertEquals(200, status(delete("/user-account/1")));
	}

	@Test
	@DisplayName("Open to any logged user: the names for the history filters and one's own account, neither with a badge code")
	void stayOpen() throws Exception {
		roleHolds();
		String names = mvc.perform(get("/user-account/cashiers").accept(MediaType.APPLICATION_JSON)).andReturn().getResponse()
				.getContentAsString();
		assertTrue(names.contains("\"username\":\"admin\""), names);
		assertFalse(names.contains("BADGE"), names);
		String own = mvc.perform(get("/user-account/current").accept(MediaType.APPLICATION_JSON)).andReturn().getResponse()
				.getContentAsString();
		assertTrue(own.contains("\"username\":\"actor\""), own);
		assertFalse(own.contains("MY-OWN-BADGE-CODE"), "one's own code is not sent back either: " + own);
	}

	@Test
	@DisplayName("The refusal names the right, as the other refusals of the API do")
	void refusalBody() throws Exception {
		roleHolds();
		String body = mvc.perform(get("/user-account").accept(MediaType.APPLICATION_JSON)).andReturn().getResponse()
				.getContentAsString();
		assertEquals("{\"error\":\"The users need the right read:admin-users.\"}", body);
	}
}
