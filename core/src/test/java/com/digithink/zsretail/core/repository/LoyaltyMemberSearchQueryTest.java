package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.data.jpa.repository.Query;

import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.MemberFunction;

/**
 * 3.5.0: the loyalty member search (LoyaltyMemberRepository.searchMembers, the POS popup and GET /loyalty/member/search),
 * its JPQL run as it is with Hibernate over an in-memory H2 database: the card number, the first name, the last name,
 * the phone, and the full name in both orders, any case. (The 2 characters minimum is the API's, the active members the
 * service's: unchanged.)
 */
class LoyaltyMemberSearchQueryTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(LoyaltyMember.class).addAnnotatedClass(Customer.class)
				.addAnnotatedClass(MemberFunction.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:loyaltysearch;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			member(session, "FID-001", "Test", "Tombola", "98111222");
			member(session, "FID-002", "Amel", "Ben Salah", "98333444");
			member(session, "FID-003", "Marie Claire", "Dupont", "98555666");
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void member(Session session, String card, String first, String last, String phone) {
		LoyaltyMember m = new LoyaltyMember();
		m.setCardNumber(card);
		m.setFirstName(first);
		m.setLastName(last);
		m.setPhone(phone);
		m.setActive(true);
		session.persist(m);
	}

	private static List<String> search(String query) throws Exception {
		String jpql = LoyaltyMemberRepository.class.getMethod("searchMembers", String.class).getAnnotation(Query.class).value();
		try (Session session = sessions.openSession()) {
			return session.createQuery(jpql, LoyaltyMember.class).setParameter("query", query).getResultList().stream()
					.map(LoyaltyMember::getCardNumber).sorted().collect(Collectors.toList());
		}
	}

	@Test
	@DisplayName("The full name, first name then last name or the reverse, any case, a part of it too")
	void fullName() throws Exception {
		assertEquals(Arrays.asList("FID-001"), search("Test Tombola"));
		assertEquals(Arrays.asList("FID-001"), search("tombola test"));
		assertEquals(Arrays.asList("FID-001"), search("TEST TOMB"));
		assertEquals(Arrays.asList("FID-002"), search("Amel Ben Salah"));
		assertEquals(Arrays.asList("FID-002"), search("ben salah amel"));
		assertEquals(Collections.emptyList(), search("Test Ben"), "two names of two members: none");
	}

	@Test
	@DisplayName("As before: one name, the card number, the phone; a compound first name with the last name")
	void asBefore() throws Exception {
		assertEquals(Arrays.asList("FID-001"), search("Tombola"));
		assertEquals(Arrays.asList("FID-002"), search("fid-002"));
		assertEquals(Arrays.asList("FID-002"), search("98333"));
		assertEquals(Arrays.asList("FID-003"), search("Marie Claire Dupont"));
		assertEquals(Arrays.asList("FID-003"), search("dupont marie claire"));
		assertEquals(Arrays.asList("FID-001", "FID-002", "FID-003"), search("98"));
	}
}
