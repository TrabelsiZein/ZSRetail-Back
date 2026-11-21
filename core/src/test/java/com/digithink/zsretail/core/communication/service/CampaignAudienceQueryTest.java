package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.MemberFunction;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication step 7: who receives a campaign, with the member query run as it is (the JPQL of
 * LoyaltyMemberRepository.findCampaignCandidates and countActiveWithoutConsent, read from their annotations) with
 * Hibernate over an in-memory H2 database, and CampaignAudience after it: the consent, the active members, a head
 * office's copy left out, the address for the channel, one message per address (the first member by id), a merged card,
 * a field without a value. The window's count and the maker's messages are the same members.
 */
class CampaignAudienceQueryTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(LoyaltyMember.class).addAnnotatedClass(Customer.class)
				.addAnnotatedClass(MemberFunction.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:campaignaudience;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			member(session, "LYL-000001", "Sana", "20000001", null, true, true, null); // receives
			member(session, "LYL-000002", "Ali", "20000002", null, false, true, null); // no consent
			member(session, "LYL-000003", "Rim", "2000 0003", null, true, true, null); // no address
			member(session, "LYL-000004", "Twin", "20000001", null, true, true, null); // the phone of LYL-000001
			member(session, "LYL-000005", "Old", "20000005", null, true, false, null); // inactive: not counted
			member(session, "LYL-HO-000001", "Copy", "20000006", null, true, true, RecordOrigin.HEAD_OFFICE); // a copy
			member(session, "LYL-000007", "Merged", "20000007", null, true, true, null); // an alias card
			member(session, "LYL-000008", " ", "20000008", null, true, true, null); // no first name
			member(session, "LYL-000009", "Mail", null, "mail@example.tn", true, true, null); // email only
			member(session, "LYL-000010", "Trim", " 20000001 ", null, true, true, null); // LYL-000001's, spaces
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void member(Session session, String card, String firstName, String phone, String email,
			boolean consent, boolean active, RecordOrigin origin) {
		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber(card);
		member.setFirstName(firstName);
		member.setLastName("L");
		member.setPhone(phone);
		member.setEmail(email);
		member.setMessageConsent(consent);
		member.setActive(active);
		member.setOrigin(origin);
		member.setLoyaltyPoints(50);
		session.persist(member);
	}

	/** The JPQL of a repository method, as Spring Data runs it. */
	private static String jpql(String method) {
		for (Method m : LoyaltyMemberRepository.class.getMethods()) {
			if (m.getName().equals(method)) {
				return m.getAnnotation(Query.class).value();
			}
		}
		throw new IllegalStateException(method);
	}

	/** The repository: its two campaign queries on H2. */
	private static LoyaltyMemberRepository h2Members() {
		return CampaignTestBed.stub(LoyaltyMemberRepository.class, (method, args) -> {
			try (Session session = sessions.openSession()) {
				switch (method) {
					case "findCampaignCandidates":
						return session.createQuery(jpql(method), Object[].class).setParameter("notOrigin", args[0])
								.setParameter("afterId", args[1]).setMaxResults(((Pageable) args[2]).getPageSize())
								.getResultList();
					case "countActiveWithoutConsent":
						return session.createQuery(jpql(method), Long.class).setParameter("notOrigin", args[0])
								.getSingleResult();
					default:
						return CampaignTestBed.UNHANDLED;
				}
			}
		});
	}

	private static CampaignAudience audience(String channel) {
		return new CampaignAudience(channel, Arrays.asList("firstName", "companyName"), "216", "Happyness", null,
				(createdBy, card) -> null, Collections.singleton("LYL-000007"));
	}

	@Test
	@DisplayName("SMS: the consent, active members only, a head office's copy never; no 8 digits, the same phone, a merged"
			+ " card, no first name: left out, each counted for its reason")
	void sms() {
		CampaignAudience.Counts counts = audience(CommunicationTypes.SMS).count(h2Members());
		assertEquals(1, counts.getReceive());
		assertEquals("LYL-000001", counts.getFirst().getCard());
		assertEquals("+21620000001", counts.getFirst().getAddress());
		assertEquals(1L, counts.getNoConsent());
		assertEquals(2, counts.getNoAddress()); // "2000 0003", no phone
		assertEquals(2, counts.getSameAddress()); // LYL-000004, LYL-000010
		assertEquals(1, counts.getNoValue()); // the first name " "
	}

	@Test
	@DisplayName("Email: the member with an email receives it, the others have no address")
	void email() {
		CampaignAudience.Counts counts = audience(CommunicationTypes.EMAIL).count(h2Members());
		assertEquals(1, counts.getReceive());
		assertEquals("mail@example.tn", counts.getFirst().getAddress());
		assertEquals(5, counts.getNoAddress()); // LYL-000001, 3, 4, 8 and 10: no email
	}

	@Test
	@DisplayName("The window's count and the messages made are the same members (one query, one rule)")
	void countIsTheList() {
		CampaignTestBed bed = new CampaignTestBed();
		bed.members = h2Members();
		bed.aliasCards = Collections.singleton("LYL-000007");
		bed.template("PROMO_SMS_FR", CommunicationTypes.SMS, "{firstName}, nos offres chez {companyName} !", true);
		CommunicationCampaigns.LaunchForm form = new CommunicationCampaigns.LaunchForm();
		form.name = "Promo";
		form.templateCode = "PROMO_SMS_FR";
		Object targeted = bed.campaigns().launch(form, "admin").get("targeted");
		bed.maker().run(Instant.now().plusSeconds(20));
		List<String> cards = bed.messagesOf(1L).stream().map(CommunicationMessage::getRecipientRef)
				.collect(Collectors.toList());
		assertEquals(Collections.singletonList("LYL-000001"), cards);
		assertEquals(targeted, cards.size());
	}
}
