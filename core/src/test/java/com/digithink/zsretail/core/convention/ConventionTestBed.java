package com.digithink.zsretail.core.convention;

import java.time.LocalDate;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.convention.repository.ConventionCardRepository;
import com.digithink.zsretail.core.convention.repository.ConventionRepository;
import com.digithink.zsretail.core.convention.service.ConventionEvents;
import com.digithink.zsretail.core.convention.service.ConventionService;
import com.digithink.zsretail.core.support.InMemoryEventLog;

/**
 * 3.6.0, conventions part B3: the real ConventionService over the real repositories (their JPQL as it is), Hibernate over
 * an in-memory H2 database (no Spring context), the event log in memory (written at once). One session per test bed,
 * the tables emptied at its creation; {@link #tx} is one transaction, as the service's @Transactional.
 */
public final class ConventionTestBed implements AutoCloseable {

	private static SessionFactory sessions;

	public final Session session;
	public final ConventionRepository conventions;
	public final ConventionCardRepository cards;
	public final InMemoryEventLog log = new InMemoryEventLog();
	/** Decision 2: the store's promotions are its head office's. */
	public boolean ownedByHeadOffice;
	public LocalDate today = LocalDate.of(2026, 11, 28);
	public final ConventionService service;

	private static synchronized SessionFactory sessions() {
		if (sessions == null) {
			Configuration configuration = new Configuration().addAnnotatedClass(Convention.class)
					.addAnnotatedClass(ConventionCard.class);
			configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
			configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
			sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
					.setProperty("hibernate.connection.url",
							"jdbc:h2:mem:conventionservice;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
					.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
					.setProperty("hibernate.hbm2ddl.auto", "create").buildSessionFactory();
		}
		return sessions;
	}

	public ConventionTestBed() {
		session = sessions().openSession();
		JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
		conventions = repositories.getRepository(ConventionRepository.class);
		cards = repositories.getRepository(ConventionCardRepository.class);
		service = new ConventionService(conventions, cards, new ConventionEvents(log.eventLog()), () -> ownedByHeadOffice,
				() -> today);
		tx(() -> {
			session.createQuery("delete from ConventionCard").executeUpdate();
			return session.createQuery("delete from Convention").executeUpdate();
		});
	}

	/** One transaction: committed, or rolled back when the work throws; the session cleared after (rows read again). */
	public <T> T tx(Supplier<T> work) {
		session.beginTransaction();
		try {
			T result = work.get();
			session.getTransaction().commit();
			return result;
		} catch (RuntimeException e) {
			session.getTransaction().rollback();
			throw e;
		} finally {
			session.clear();
		}
	}

	/** A card of this convention, saved. */
	public ConventionCard card(long conventionId, String number, ConventionCardStatus status) {
		return tx(() -> {
			ConventionCard card = new ConventionCard();
			card.setConvention(session.get(Convention.class, conventionId));
			card.setCardNumber(number);
			card.setStatus(status);
			session.persist(card);
			return card;
		});
	}

	public Convention row(long id) {
		return tx(() -> session.get(Convention.class, id));
	}

	/** A form with what a convention needs: the code, a name, an organisation, a start. */
	public static ConventionService.Form form(String code) {
		ConventionService.Form f = new ConventionService.Form();
		f.code = code;
		f.name = "Convention " + code;
		f.organisation = "Ministère de la Santé";
		f.startDate = LocalDate.of(2026, 11, 1);
		return f;
	}

	/** Created through the service; its id. */
	public long create(ConventionService.Form form) {
		return ((Number) tx(() -> service.create(form, "admin")).get("id")).longValue();
	}

	@Override
	public void close() {
		session.close();
	}
}
