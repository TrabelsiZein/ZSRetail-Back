package com.digithink.zsretail.core.convention.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 3.6.0, conventions parts B4 and B5: new cards (a generation, an import) inserted in JDBC batches (convention_card's
 * id is an identity: JPA would insert them one by one, 5,000 round trips). Run inside the caller's transaction: the JPA
 * transaction manager gives JdbcTemplate the same connection, so a refusal after it rolls the cards back too. A new
 * card is ACTIVE, never exported, its holder's fields as given (a generation: none); the unique key
 * uk_convention_card_number refuses a number taken meanwhile (the whole run then rolls back).
 */
public final class ConventionCardBatchInsert {

	public static final String SQL = "insert into convention_card (active, created_at, created_by, updated_at, updated_by,"
			+ " card_number, convention_id, status, holder_name, holder_phone, holder_employee_no)"
			+ " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	static final int BATCH = 1000;

	/** A card to insert: its number (normalised) and its holder's optional fields (checked). */
	public static final class NewCard {
		public final String number;
		public final String holderName;
		public final String holderPhone;
		public final String holderEmployeeNo;

		public NewCard(String number, String holderName, String holderPhone, String holderEmployeeNo) {
			this.number = number;
			this.holderName = holderName;
			this.holderPhone = holderPhone;
			this.holderEmployeeNo = holderEmployeeNo;
		}

		public static NewCard numbered(String number) {
			return new NewCard(number, null, null, null);
		}
	}

	private final JdbcTemplate jdbc;

	public ConventionCardBatchInsert(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(long conventionId, List<NewCard> cards, String user, LocalDateTime at) {
		jdbc.batchUpdate(SQL, cards, BATCH, (ps, card) -> bind(ps, conventionId, card, user, at));
	}

	/** One card's values, in the order of {@link #SQL}. */
	public static void bind(PreparedStatement ps, long conventionId, NewCard card, String user, LocalDateTime at)
			throws SQLException {
		ps.setBoolean(1, true);
		ps.setTimestamp(2, Timestamp.valueOf(at));
		ps.setString(3, user);
		ps.setTimestamp(4, Timestamp.valueOf(at));
		ps.setString(5, user);
		ps.setString(6, card.number); // nvarchar: the driver sends strings as Unicode
		ps.setLong(7, conventionId);
		ps.setString(8, "ACTIVE");
		text(ps, 9, card.holderName);
		text(ps, 10, card.holderPhone);
		text(ps, 11, card.holderEmployeeNo);
	}

	private static void text(PreparedStatement ps, int i, String value) throws SQLException {
		if (value == null) {
			ps.setNull(i, Types.NVARCHAR);
		} else {
			ps.setString(i, value);
		}
	}
}
