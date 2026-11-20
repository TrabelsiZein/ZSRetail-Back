package com.digithink.zsretail.store.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.MemberFunction;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.store.communication.StoreEventSource.MemberRow;
import com.digithink.zsretail.store.holink.model.LoyaltyMemberCopy;

/**
 * 3.3.0, communication module, step 2: the member queries of JpaStoreEventSource, run as they are with Hibernate over
 * an in-memory H2 database (no Spring context). Loyalty LOCAL: the members made here after the activation, active,
 * not of the network register, without an event. Loyalty owned by the head office: only the members with a copy row
 * (enrolled here) that is not a merge; never a copy received from the head office.
 */
class StoreMemberEventsQueryTest {

	private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 20, 10, 0);

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(LoyaltyMember.class).addAnnotatedClass(Customer.class)
				.addAnnotatedClass(MemberFunction.class).addAnnotatedClass(LoyaltyMemberCopy.class)
				.addAnnotatedClass(CommunicationEvent.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:commmembers;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			member(session, "LYL-000001", SINCE.minusDays(1), true, null, null); // before the activation
			member(session, "LYL-000002", SINCE.plusMinutes(1), true, null, null); // LOCAL: enrolled here
			member(session, "LYL-000003", SINCE.plusMinutes(2), false, null, null); // inactive
			member(session, "LYL-HO-000001", SINCE.plusMinutes(3), true, RecordOrigin.HEAD_OFFICE, null); // received
			member(session, "LYL-S-000001", SINCE.plusMinutes(4), true, RecordOrigin.HEAD_OFFICE, "CREATED"); // enrolled here
			member(session, "LYL-S-000002", SINCE.plusMinutes(5), true, RecordOrigin.HEAD_OFFICE, ""); // enrolled, not sent yet
			member(session, "LYL-S-000003", SINCE.plusMinutes(6), true, RecordOrigin.HEAD_OFFICE, "MERGED"); // merged
			long withEvent = member(session, "LYL-000004", SINCE.plusMinutes(7), true, null, null);
			CommunicationEvent event = new CommunicationEvent();
			event.setEventType(CommunicationTypes.MEMBER_CREATED);
			event.setSourceType(CommunicationTypes.LOYALTY_MEMBER);
			event.setSourceId(withEvent);
			event.setDedupKey("MEMBER_CREATED:LYL-000004");
			event.setOccurredAt(SINCE.plusMinutes(7));
			session.persist(event);
			session.getTransaction().commit();
		}
	}

	/** A member; copyOutcome null: no hol_loyalty_member_copy row, "" a row not answered yet, else its outcome. */
	private static long member(Session session, String card, LocalDateTime createdAt, boolean active, RecordOrigin origin,
			String copyOutcome) {
		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber(card);
		member.setFirstName("F");
		member.setLastName("L");
		member.setCreatedAt(createdAt);
		member.setActive(active);
		member.setOrigin(origin);
		session.persist(member);
		if (copyOutcome != null) {
			LoyaltyMemberCopy copy = new LoyaltyMemberCopy();
			copy.setMemberId(member.getId());
			copy.setCardNumber(card);
			copy.setOutcome(copyOutcome.isEmpty() ? null : copyOutcome);
			session.persist(copy);
		}
		return member.getId();
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static List<String> cards(boolean loyaltyFromHeadOffice) {
		try (Session session = sessions.openSession()) {
			return new JpaStoreEventSource(session).membersEnrolledHere(SINCE, loyaltyFromHeadOffice, 200).stream()
					.map(MemberRow::getCardNumber).collect(Collectors.toList());
		}
	}

	@Test
	@DisplayName("Loyalty LOCAL: enrolled here after the activation, active, not of the network register, no event yet")
	void local() {
		assertEquals(List.of("LYL-000002"), cards(false));
	}

	@Test
	@DisplayName("Loyalty owned by the head office: the members with a copy row that is not a merge, never a copy received")
	void fromHeadOffice() {
		assertEquals(List.of("LYL-S-000001", "LYL-S-000002"), cards(true));
	}
}
