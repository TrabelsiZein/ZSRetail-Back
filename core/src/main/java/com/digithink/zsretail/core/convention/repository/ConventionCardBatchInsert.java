package com.digithink.zsretail.core.convention.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 3.6.0, conventions part B4: a generation's cards inserted in JDBC batches (convention_card's id is an identity: JPA
 * would insert them one by one, 5,000 round trips). Run inside the generation's transaction: the JPA transaction
 * manager gives JdbcTemplate the same connection, so a refusal after it rolls the cards back too. The columns a new
 * card has (status ACTIVE, no holder, never exported); the unique key uk_convention_card_number refuses a number taken
 * meanwhile (the whole generation then rolls back).
 */
public final class ConventionCardBatchInsert {

	public static final String SQL = "insert into convention_card (active, created_at, created_by, updated_at, updated_by,"
			+ " card_number, convention_id, status) values (?, ?, ?, ?, ?, ?, ?, ?)";
	static final int BATCH = 1000;

	private final JdbcTemplate jdbc;

	public ConventionCardBatchInsert(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(long conventionId, List<String> numbers, String user, LocalDateTime at) {
		jdbc.batchUpdate(SQL, numbers, BATCH, (ps, number) -> bind(ps, conventionId, number, user, at));
	}

	/** One card's values, in the order of {@link #SQL}. */
	public static void bind(PreparedStatement ps, long conventionId, String number, String user, LocalDateTime at)
			throws SQLException {
		ps.setBoolean(1, true);
		ps.setTimestamp(2, Timestamp.valueOf(at));
		ps.setString(3, user);
		ps.setTimestamp(4, Timestamp.valueOf(at));
		ps.setString(5, user);
		ps.setString(6, number); // nvarchar(40): the driver sends strings as Unicode
		ps.setLong(7, conventionId);
		ps.setString(8, "ACTIVE");
	}
}
