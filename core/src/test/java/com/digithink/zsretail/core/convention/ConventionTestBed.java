package com.digithink.zsretail.core.convention;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.function.Supplier;

import javax.persistence.Entity;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.convention.repository.ConventionCardBatchInsert;
import com.digithink.zsretail.core.convention.repository.ConventionCardRepository;
import com.digithink.zsretail.core.convention.repository.ConventionRepository;
import com.digithink.zsretail.core.convention.service.ConventionCards;
import com.digithink.zsretail.core.convention.service.ConventionEvents;
import com.digithink.zsretail.core.convention.service.ConventionService;
import com.digithink.zsretail.core.support.InMemoryEventLog;

/**
 * 3.6.0, conventions parts B3 and B4: the real ConventionService and ConventionCards over the real repositories (their
 * JPQL as it is), Hibernate over an in-memory H2 database (no Spring context; core's entities, so the items, barcodes
 * and loyalty members are there), the event log in memory (written at once). One session per test bed, the tables
 * emptied at its creation (unless it is a second bed of the same test); {@link #tx} is one transaction, as the
 * services' @Transactional. A generation's batch insert is ConventionCardBatchInsert's SQL on the session's connection.
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
	public final ConventionCards cardService;

	private static synchronized SessionFactory sessions() {
		if (sessions == null) {
			Configuration configuration = new Configuration();
			ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
			scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
			for (String base : new String[] { "com.digithink.zsretail.core.model",
					"com.digithink.zsretail.core.convention.model" }) {
				for (BeanDefinition entity : scan.findCandidateComponents(base)) {
					try {
						configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
					} catch (ClassNotFoundException e) {
						throw new IllegalStateException(e);
					}
				}
			}
			configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
			configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
			sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
					.setProperty("hibernate.connection.url",
							"jdbc:h2:mem:conventionservice;DB_CLOSE_DELAY=-1;MODE=MSSQLServer;LOCK_TIMEOUT=20000")
					.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
					.setProperty("hibernate.hbm2ddl.auto", "create").buildSessionFactory();
		}
		return sessions;
	}

	public ConventionTestBed() {
		this(true);
	}

	/** {@code clean}: the conventions, cards, items, barcodes and members emptied first. */
	public ConventionTestBed(boolean clean) {
		session = sessions().openSession();
		JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
		conventions = repositories.getRepository(ConventionRepository.class);
		cards = repositories.getRepository(ConventionCardRepository.class);
		ConventionEvents events = new ConventionEvents(log.eventLog());
		service = new ConventionService(conventions, cards, events, () -> ownedByHeadOffice, () -> today);
		cardService = new ConventionCards(conventions, cards, events, this::insert, () -> ownedByHeadOffice, () -> today);
		if (clean) {
			tx(() -> {
				for (String entity : new String[] { "ConventionCard", "Convention", "ItemBarcode", "Item",
						"LoyaltyMember" }) {
					session.createQuery("delete from " + entity).executeUpdate();
				}
				return null;
			});
		}
	}

	/** ConventionCardBatchInsert's statement in one JDBC batch on this session's connection (its transaction). */
	private void insert(long conventionId, java.util.List<String> numbers, String user, java.time.LocalDateTime at) {
		session.doWork(connection -> {
			try (PreparedStatement ps = connection.prepareStatement(ConventionCardBatchInsert.SQL)) {
				for (String number : numbers) {
					ConventionCardBatchInsert.bind(ps, conventionId, number, user, at);
					ps.addBatch();
				}
				ps.executeBatch();
			} catch (BatchUpdateException duplicate) {
				throw new DataIntegrityViolationException("uk_convention_card_number", duplicate); // as JdbcTemplate
			}
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
