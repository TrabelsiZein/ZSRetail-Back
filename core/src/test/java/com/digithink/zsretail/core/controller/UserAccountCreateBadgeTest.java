package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.core.service.UserAccountService;

/**
 * 3.4.0 (rows check 2): the badge typed in the Users add popup is saved by POST /user-account, checked as an update
 * checks it (format, a code no other user holds, the date); it was dropped before. The real API, service and badge
 * format check; the repositories mocked.
 */
class UserAccountCreateBadgeTest {

	private final UserAccountRepository accounts = mock(UserAccountRepository.class);
	private final AppRoleRepository roles = mock(AppRoleRepository.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final AtomicReference<UserAccount> saved = new AtomicReference<>();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		UserAccountService users = new UserAccountService();
		PasswordEncoder encoder = mock(PasswordEncoder.class);
		when(encoder.encode(anyString())).thenReturn("$2a$encoded");
		ReflectionTestUtils.setField(users, "accountRepository", accounts);
		ReflectionTestUtils.setField(users, "appRoleRepository", roles);
		ReflectionTestUtils.setField(users, "passwordEncoder", encoder);
		ReflectionTestUtils.setField(users, "currentUserProvider", current);

		UserAccountAPI api = new UserAccountAPI();
		ReflectionTestUtils.setField(api, "userAccountService", users);
		ReflectionTestUtils.setField(api, "badgeService", new BadgeService());
		ReflectionTestUtils.setField(api, "currentUserProvider", current);
		ReflectionTestUtils.setField(api, "applicationModeService", mock(ApplicationModeService.class));
		ReflectionTestUtils.setField(api, "accountRepository", accounts);
		mvc = MockMvcBuilders.standaloneSetup(api).build();

		AppRole admin = new AppRole("ADMIN", "ADMIN", false);
		admin.setId(1L);
		AppRole cashier = new AppRole("POS_USER", "POS_USER", true);
		cashier.setId(3L);
		UserAccount actor = new UserAccount();
		actor.setId(1L);
		actor.setUsername("admin");
		actor.setAppRole(admin);
		when(current.getCurrentUser()).thenReturn(actor);
		when(current.getCurrentUserName()).thenReturn("admin");
		when(current.currentUserHasPermission(anyString())).thenReturn(true);
		when(roles.findById(3L)).thenReturn(Optional.of(cashier));
		when(accounts.findByUsername(anyString())).thenReturn(Optional.empty());
		when(accounts.findByBadgeCode(anyString())).thenReturn(Optional.empty());
		when(accounts.save(any())).thenAnswer(call -> {
			UserAccount user = call.getArgument(0);
			user.setId(9L);
			saved.set(user);
			return user;
		});
	}

	private MockHttpServletResponse create(String badge, String expiration) throws Exception {
		String body = "{\"username\":\"caissier_test\",\"password\":\"probe-password\",\"fullName\":\"Caissier Test\","
				+ "\"appRoleId\":3" + (badge == null ? "" : ",\"badgeCode\":\"" + badge + "\"")
				+ (expiration == null ? "" : ",\"badgeExpirationDate\":\"" + expiration + "\"") + "}";
		return mvc.perform(post("/user-account").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()
				.getResponse();
	}

	@Test
	@DisplayName("Created with a badge: the code (trimmed) and its date are saved")
	void createdWithBadge() throws Exception {
		MockHttpServletResponse answer = create(" CAISSIER-TEST-001 ", "2026-12-31T23:59");
		assertEquals(201, answer.getStatus(), answer.getContentAsString());
		assertEquals("CAISSIER-TEST-001", saved.get().getBadgeCode());
		assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59), saved.get().getBadgeExpirationDate());
	}

	@Test
	@DisplayName("Created without a badge (the popup's empty fields): no code, no date")
	void createdWithoutBadge() throws Exception {
		assertEquals(201, create("", "").getStatus());
		assertNull(saved.get().getBadgeCode());
		assertNull(saved.get().getBadgeExpirationDate());
	}

	@Test
	@DisplayName("A badge code another user holds: refused, said, nothing saved")
	void duplicateBadge() throws Exception {
		UserAccount holder = new UserAccount();
		holder.setId(4L);
		when(accounts.findByBadgeCode("CAISSIER-TEST-001")).thenReturn(Optional.of(holder));
		MockHttpServletResponse answer = create("CAISSIER-TEST-001", null);
		assertEquals(400, answer.getStatus());
		assertTrue(answer.getContentAsString().contains("Badge code already exists"), answer.getContentAsString());
		verify(accounts, never()).save(any());
	}

	@Test
	@DisplayName("A badge code of a wrong format, or a date unreadable: refused, said, nothing saved")
	void badFormat() throws Exception {
		MockHttpServletResponse answer = create("AB 1", null);
		assertEquals(400, answer.getStatus());
		assertTrue(answer.getContentAsString().contains("Invalid badge code format"), answer.getContentAsString());
		answer = create("CAISSIER-TEST-001", "31/12/2026");
		assertEquals(400, answer.getStatus());
		assertTrue(answer.getContentAsString().contains("Invalid badge expiration date"), answer.getContentAsString());
		verify(accounts, never()).save(any());
	}
}
