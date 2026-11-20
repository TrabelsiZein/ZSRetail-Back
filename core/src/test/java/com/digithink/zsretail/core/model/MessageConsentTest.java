package com.digithink.zsretail.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.core.dto.LoyaltyMemberDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.core.service.LoyaltyService;
import com.digithink.zsretail.core.support.InMemoryLoyalty;

/**
 * 3.3.0, communication step 5: the member's consent to receive messages, its one rule (MessageConsent) and the store
 * whose loyalty is its own (LoyaltyService with no network): the tick at enrolment and on the members page, nothing
 * asked = nothing changed. The four loyalty tests of LoyaltyService are not touched.
 */
class MessageConsentTest {

	private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 21, 9, 0);
	private static final LocalDateTime T2 = T1.plusHours(2);

	private static LoyaltyMember member(Boolean consent, LocalDateTime at, String by) {
		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber("LYL-000001");
		member.setMessageConsent(consent);
		member.setMessageConsentAt(at);
		member.setMessageConsentBy(by);
		return member;
	}

	@Test
	@DisplayName("A new member has no consent; apply: null asks nothing, the same value keeps its date, a new one is dated")
	void apply() {
		assertFalse(new LoyaltyMember().getMessageConsent(), "no by default");
		LoyaltyMember m = member(false, null, null);
		assertFalse(MessageConsent.apply(m, null, "sami", T1));
		assertFalse(MessageConsent.apply(m, false, "sami", T1));
		assertNull(m.getMessageConsentAt(), "nothing changed: no date");
		assertTrue(MessageConsent.apply(m, true, "sami", T1));
		assertEquals(T1, m.getMessageConsentAt());
		assertEquals("sami", m.getMessageConsentBy());
		assertFalse(MessageConsent.apply(m, true, "ali", T2));
		assertEquals(T1, m.getMessageConsentAt(), "the same value keeps its date");
		assertTrue(MessageConsent.apply(m, false, "ali", T2));
		assertEquals(T2, m.getMessageConsentAt());
		assertEquals("ali", m.getMessageConsentBy());
	}

	@Test
	@DisplayName("Copy: the owner's value with its date and author; none (an older head office) changes nothing")
	void copy() {
		LoyaltyMember m = member(true, T1, "sami");
		assertFalse(MessageConsent.copy(m, null, null, null));
		assertTrue(m.getMessageConsent());
		assertFalse(MessageConsent.copy(m, true, T1, "sami"), "the same: unchanged");
		assertTrue(MessageConsent.copy(m, false, T2, "STORE:RS02/ali"));
		assertFalse(m.getMessageConsent());
		assertEquals(T2, m.getMessageConsentAt());
		assertEquals("STORE:RS02/ali", m.getMessageConsentBy());
	}

	@Test
	@DisplayName("Merge: a yes newer than the card's last change is kept with its date; a no, or an older yes, changes nothing")
	void mergeYes() {
		LoyaltyMember never = member(false, null, null);
		assertTrue(MessageConsent.mergeYes(never, true, T1, "STORE:RS01/sami", T2));
		assertEquals(T1, never.getMessageConsentAt(), "the date of the enrolment");

		LoyaltyMember given = member(true, T1, "admin");
		assertFalse(MessageConsent.mergeYes(given, false, T2, "STORE:RS01/sami", T2), "a no never removes a yes");
		assertTrue(given.getMessageConsent());

		LoyaltyMember withdrawn = member(false, T2, "admin");
		assertFalse(MessageConsent.mergeYes(withdrawn, true, T1, "STORE:RS01/sami", T2), "withdrawn after: it stands");
		assertTrue(MessageConsent.mergeYes(withdrawn, true, T2.plusMinutes(1), "STORE:RS01/sami", T2),
				"given again after the withdrawal");

		LoyaltyMember noDate = member(false, null, null);
		assertTrue(MessageConsent.mergeYes(noDate, true, null, "STORE:RS01", T2));
		assertEquals(T2, noDate.getMessageConsentAt(), "a yes without a date: now");
	}

	@Test
	@DisplayName("A store whose loyalty is its own: the tick at enrolment and on the members page, the list's filter and"
			+ " count; the copy and the member DTO carry it")
	void localStore() {
		InMemoryLoyalty db = new InMemoryLoyalty(1);
		LoyaltyService loyalty = db.loyaltyService(null);
		LoyaltyMemberDTO yes = loyalty.createMember(request("SAMI", "29954290", true));
		LoyaltyMemberDTO no = loyalty.createMember(request("ALI", "22984935", null));
		assertTrue(yes.getMessageConsent());
		assertEquals("System", yes.getMessageConsentBy());
		assertFalse(no.getMessageConsent());
		assertNull(no.getMessageConsentAt());

		LoyaltyMember sami = db.card(yes.getCardNumber()).get();
		LocalDateTime given = sami.getMessageConsentAt();
		loyalty.updateMember(sami.getId(), request("SAMIR", "29954290", null));
		assertTrue(sami.getMessageConsent(), "the tick not shown: unchanged");
		assertEquals(given, sami.getMessageConsentAt());
		loyalty.updateMember(sami.getId(), request("SAMIR", "29954290", false));
		assertFalse(sami.getMessageConsent());

		loyalty.updateMember(db.card(no.getCardNumber()).get().getId(), request("ALI", "22984935", true));
		LoyaltyMemberCopyDTO copy = LoyaltyMemberCopyDTO.of(db.card(no.getCardNumber()).get());
		assertTrue(copy.getMessageConsent());
		assertEquals("System", copy.getMessageConsentBy());
	}

	private static CreateLoyaltyMemberRequestDTO request(String first, String phone, Boolean consent) {
		CreateLoyaltyMemberRequestDTO r = new CreateLoyaltyMemberRequestDTO();
		r.setFirstName(first);
		r.setLastName("BEN");
		r.setPhone(phone);
		r.setMessageConsent(consent);
		return r;
	}
}
