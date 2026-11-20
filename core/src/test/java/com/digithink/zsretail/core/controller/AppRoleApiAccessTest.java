package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.AppRoleService;

/**
 * 3.2.0: /app-roles over HTTP (standalone MockMvc over the real AppRoleAPI). Before, any logged user, a cashier included,
 * created, changed or deleted a role, and so gave their own role every permission. POST and PUT need write:admin-roles,
 * DELETE delete:admin-roles; a head office's Roles page has one key, read:admin-headoffice-roles, for all three. The
 * reads stay open (the Users pages list the roles).
 */
class AppRoleApiAccessTest {

	private static final String ROLE = "{\"name\":\"PIRATE\",\"label\":\"Pirate\",\"isPosRole\":false,"
			+ "\"permissions\":[\"read:admin-roles\",\"write:admin-roles\",\"delete:admin-roles\",\"write:admin-users\"]}";

	private final AppRoleService roles = mock(AppRoleService.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final ApplicationModeService mode = mock(ApplicationModeService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		AppRoleAPI api = new AppRoleAPI();
		ReflectionTestUtils.setField(api, "appRoleService", roles);
		ReflectionTestUtils.setField(api, "currentUserProvider", current);
		ReflectionTestUtils.setField(api, "applicationModeService", mode);
		mvc = MockMvcBuilders.standaloneSetup(api).build();

		UserAccount actor = new UserAccount();
		actor.setId(9L);
		actor.setUsername("actor");
		when(current.getCurrentUser()).thenReturn(actor);
		AppRole saved = new AppRole("PIRATE", "Pirate", false);
		saved.setId(5L);
		when(roles.create(anyString(), anyString(), anyBoolean(), any(), anyString())).thenReturn(saved);
		when(roles.update(any(), anyString(), anyBoolean(), any(), anyString())).thenReturn(saved);
		when(roles.findAll()).thenReturn(Collections.singletonList(saved));
		when(mode.isHeadOffice()).thenReturn(false);
	}

	private void roleHolds(String... keys) {
		Set<String> held = new HashSet<>(Arrays.asList(keys));
		when(current.currentUserHasPermission(anyString())).thenAnswer(call -> held.contains(call.getArgument(0)));
	}

	private MockHttpServletResponse call(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private static RequestBuilder create() {
		return post("/app-roles").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(ROLE);
	}

	private static RequestBuilder change() {
		return put("/app-roles/2").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(ROLE);
	}

	private static RequestBuilder remove() {
		return delete("/app-roles/2");
	}

	private void assertNoWrite() {
		verify(roles, never()).create(any(), any(), anyBoolean(), any(), any());
		verify(roles, never()).update(any(), any(), anyBoolean(), any(), any());
		verify(roles, never()).delete(any());
	}

	@Test
	@DisplayName("A cashier: creating, changing and deleting a role refused (403), no role written; the reads stay open")
	void theCashier() throws Exception {
		roleHolds("read:cashier-interface", "read:home");
		for (RequestBuilder write : new RequestBuilder[] { create(), change(), remove() }) {
			MockHttpServletResponse answer = call(write);
			assertEquals(403, answer.getStatus(), answer.getContentAsString());
			assertTrue(answer.getContentAsString().contains("admin-roles"), answer.getContentAsString());
		}
		assertNoWrite();
		assertEquals(200, call(get("/app-roles")).getStatus());
		assertEquals(200, call(get("/app-roles/permissions")).getStatus());
	}

	@Test
	@DisplayName("The Roles page's read right alone: the page reads, every write refused (403)")
	void readRightOnly() throws Exception {
		roleHolds("read:admin-roles");
		assertEquals(403, call(create()).getStatus());
		assertEquals(403, call(change()).getStatus());
		assertEquals(403, call(remove()).getStatus());
		assertNoWrite();
		assertEquals(200, call(get("/app-roles")).getStatus());
	}

	@Test
	@DisplayName("write:admin-roles creates and changes but does not delete; delete:admin-roles deletes")
	void writeAndDeleteSplit() throws Exception {
		roleHolds("read:admin-roles", "write:admin-roles");
		assertEquals(200, call(create()).getStatus());
		assertEquals(200, call(change()).getStatus());
		assertEquals(403, call(remove()).getStatus());
		verify(roles, never()).delete(any());

		roleHolds("read:admin-roles", "delete:admin-roles");
		assertEquals(204, call(remove()).getStatus());
		verify(roles).delete(2L);
	}

	@Test
	@DisplayName("An administrator (the three keys): created, changed, deleted, by the logged user's name")
	void theAdministrator() throws Exception {
		roleHolds("read:admin-roles", "write:admin-roles", "delete:admin-roles");
		MockHttpServletResponse created = call(create());
		assertEquals(200, created.getStatus(), created.getContentAsString());
		assertTrue(created.getContentAsString().contains("\"name\":\"PIRATE\""), created.getContentAsString());
		assertEquals(200, call(change()).getStatus());
		assertEquals(204, call(remove()).getStatus());
		verify(roles).create(eq("PIRATE"), eq("Pirate"), eq(false), any(), eq("actor"));
		verify(roles).update(eq(2L), eq("Pirate"), eq(false), any(), eq("actor"));
		verify(roles).delete(2L);
	}

	@Test
	@DisplayName("A head office: its page's key read:admin-headoffice-roles writes the three; without it 403; the same key"
			+ " on a store is refused")
	void theHeadOffice() throws Exception {
		when(mode.isHeadOffice()).thenReturn(true);
		roleHolds("read:admin-headoffice-home");
		assertEquals(403, call(create()).getStatus());
		assertEquals(403, call(change()).getStatus());
		assertEquals(403, call(remove()).getStatus());
		assertNoWrite();

		roleHolds("read:admin-headoffice-roles");
		assertEquals(200, call(create()).getStatus());
		assertEquals(200, call(change()).getStatus());
		assertEquals(204, call(remove()).getStatus());

		when(mode.isHeadOffice()).thenReturn(false);
		assertEquals(403, call(remove()).getStatus(), "a store reads only its own keys");
	}
}
