package com.digithink.zsretail.core.convention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import javax.persistence.PersistenceException;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;

import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.enumeration.ConventionRecognition;
import com.digithink.zsretail.core.convention.enumeration.ConventionStatus;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

/**
 * 3.6.0, conventions step 1, part B2 (docs/roadmap/conventions.md): the two tables.
 * <ul>
 * <li>db/3.6.0/update.sql creates them in Hibernate's form: every statement Hibernate itself writes for SQL Server
 * (SQLServer2012Dialect, Spring's naming, as a start) is in the script, word for word. So a start with ddl-auto=update
 * finds every column and constraint and adds nothing;</li>
 * <li>on H2 (made by Hibernate): the code unique, the card number unique over all conventions, a card needs its
 * convention, the defaults of a new row, the text kept as typed (nvarchar).</li>
 * </ul>
 */
class ConventionTableTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration().addAnnotatedClass(Convention.class)
				.addAnnotatedClass(ConventionCard.class);
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:convention;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	/** The statements Hibernate writes to create the two tables on SQL Server, one per line, without the ";". */
	static List<String> hibernateDdl() throws Exception {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.SQLServer2012Dialect")
				.applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false").build();
		try {
			Metadata metadata = new MetadataSources(registry).addAnnotatedClass(Convention.class)
					.addAnnotatedClass(ConventionCard.class).getMetadataBuilder()
					.applyPhysicalNamingStrategy(new SpringPhysicalNamingStrategy())
					.applyImplicitNamingStrategy(new SpringImplicitNamingStrategy()).build();
			Path file = Files.createTempFile("convention-ddl", ".sql");
			try {
				new SchemaExport().setFormat(false).setDelimiter(";").setOutputFile(file.toString())
						.createOnly(EnumSet.of(TargetType.SCRIPT), metadata);
				List<String> statements = new ArrayList<>();
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					String statement = line.trim();
					if (statement.endsWith(";")) {
						statement = statement.substring(0, statement.length() - 1).trim();
					}
					if (!statement.isEmpty()) {
						statements.add(statement);
					}
				}
				return statements;
			} finally {
				Files.deleteIfExists(file);
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}

	/** Lower case, every run of white space one space: the script may wrap a statement and indent it. */
	private static String normalised(String sql) {
		return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}

	private static String script() throws Exception {
		try (InputStream in = ConventionTableTest.class.getResourceAsStream("/db/3.6.0/update.sql")) {
			assertTrue(in != null, "db/3.6.0/update.sql on the class path");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("db/3.6.0/update.sql holds every statement Hibernate writes for the two tables (SQL Server), word for word")
	void scriptIsHibernatesForm() throws Exception {
		List<String> ddl = hibernateDdl();
		assertEquals(6, ddl.size(), "two tables, two unique keys, the index and the foreign key: " + ddl);
		String script = normalised(script());
		for (String statement : ddl) {
			assertTrue(script.contains(normalised(statement)), "not in db/3.6.0/update.sql: " + statement);
		}
	}

	@Test
	@DisplayName("Every text column is nvarchar, the two tables named as the decisions say")
	void nvarcharAndNames() throws Exception {
		String ddl = String.join("\n", hibernateDdl());
		assertFalse(ddl.matches("(?s).*\\b(code|name|organisation|status|recognition|card_prefix|origin|card_number"
				+ "|holder_name|holder_phone|holder_employee_no) varchar.*"), ddl);
		for (String name : new String[] { "uk_convention_code", "uk_convention_card_number", "fk_convention_card_convention",
				"ix_convention_card_convention on convention_card (convention_id, status)" }) {
			assertTrue(ddl.contains(name), name);
		}
	}

	private static Convention convention(String code) {
		Convention convention = new Convention();
		convention.setCode(code);
		convention.setName("Convention ministère — اتفاقية");
		convention.setOrganisation("Ministère de la Santé");
		convention.setStartDate(LocalDate.of(2026, 12, 1));
		return convention;
	}

	private static ConventionCard card(Convention convention, String number) {
		ConventionCard card = new ConventionCard();
		card.setConvention(convention);
		card.setCardNumber(number);
		return card;
	}

	@Test
	@DisplayName("A new convention and a new card: their defaults (ACTIVE, CARDS, 5 digits, counter 0, LOCAL; card ACTIVE)")
	void defaults() {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Convention convention = convention("MIN");
			session.persist(convention);
			ConventionCard card = card(convention, "MIN-00001");
			session.persist(card);
			session.flush();
			session.clear();
			Convention read = session.get(Convention.class, convention.getId());
			assertEquals(ConventionStatus.ACTIVE, read.getStatus());
			assertEquals(ConventionRecognition.CARDS, read.getRecognition());
			assertEquals(Integer.valueOf(5), read.getCardDigits());
			assertEquals(Long.valueOf(0), read.getLastCardSeq());
			assertEquals(RecordOrigin.LOCAL, read.getOrigin());
			assertNull(read.getEndDate());
			assertNull(read.getCardPrefix());
			assertNull(read.getMaxUsesPerDay());
			assertNull(read.getMaxAmountPerMonth());
			assertEquals("Convention ministère — اتفاقية", read.getName(), "nvarchar: accents and Arabic kept");
			ConventionCard readCard = session.get(ConventionCard.class, card.getId());
			assertEquals(ConventionCardStatus.ACTIVE, readCard.getStatus());
			assertEquals(convention.getId(), readCard.getConvention().getId());
			assertNull(readCard.getHolderName(), "the holder is optional");
			assertNull(readCard.getExportedAt(), "never exported");
			session.getTransaction().rollback();
		}
	}

	@Test
	@DisplayName("The code twice is refused")
	void codeOnce() {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.persist(convention("DUP"));
			// IDENTITY: the insert runs at persist
			assertThrows(PersistenceException.class, () -> session.persist(convention("DUP")));
			session.getTransaction().rollback();
		}
	}

	@Test
	@DisplayName("A card number is unique over all the conventions, not per convention")
	void cardNumberOnceInTheInstallation() {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Convention first = convention("ONE");
			Convention second = convention("TWO");
			session.persist(first);
			session.persist(second);
			session.persist(card(first, "CARD-00001"));
			assertThrows(PersistenceException.class, () -> session.persist(card(second, "CARD-00001")));
			session.getTransaction().rollback();
		}
	}

	@Test
	@DisplayName("A card needs its convention")
	void cardNeedsConvention() {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			assertThrows(PersistenceException.class, () -> session.persist(card(null, "ORPHAN-1")));
			session.getTransaction().rollback();
		}
	}
}
