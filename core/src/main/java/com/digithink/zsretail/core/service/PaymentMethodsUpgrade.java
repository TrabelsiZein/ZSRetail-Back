package com.digithink.zsretail.core.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.log4j.Log4j2;

/**
 * 3.2.0, payment methods the admin creates: the changes Hibernate cannot make, also done by db/3.2.0/update.sql (the
 * same statements, in the same order); here at each start too, before ZZDataInitializer reads or writes a payment
 * method, so a database that went back to 3.1.2 and recreated what 3.2.0 removed is put right again. Each statement is
 * guarded (it changes nothing the second time); a failure is logged, never stops the start.
 * <ol>
 * <li>the unique constraint on payment_method.type dropped (SQL Server's allows one NULL only; Hibernate 3.1.2 named it
 * UK_drvond376oipl4k39o8kor2e4, the name is read, never assumed);</li>
 * <li>payment_method.type nullable (a method the admin creates has no type), its own SQL type kept;</li>
 * <li>the filtered unique index ux_payment_method_type: still one row per fixed type (findByType), any number
 * without;</li>
 * <li>the columns behaviour and title_number_length (on a database at 3.1.2 the script runs before Hibernate adds
 * them);</li>
 * <li>in one transaction: the N° titre lengths of the four settings PAYMENT_METHOD_&lt;type&gt;_TITLE_NUMBER_LENGTH
 * copied to the row of their type (a length already set is kept; blank, zero or not digits only: none; no TRY_CAST,
 * which an old compatibility level lacks), then Ticket
 * Restaurant and Chèque Cadeau become methods without type, behaviour AMOUNT (code, name, active, order and switches
 * untouched, their payments too), every row without behaviour gets AMOUNT, and the four settings are deleted.</li>
 * </ol>
 */
@Component
@Log4j2
public class PaymentMethodsUpgrade {

	public static final String INDEX = "ux_payment_method_type";

	static final String DROP_TYPE_UNIQUE_CONSTRAINT = "DECLARE @uk sysname, @sql nvarchar(400);"
			+ " WHILE 1 = 1 BEGIN"
			+ " SET @uk = (SELECT TOP 1 k.name FROM sys.key_constraints k"
			+ " JOIN sys.index_columns ic ON ic.object_id = k.parent_object_id AND ic.index_id = k.unique_index_id"
			+ " JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id"
			+ " WHERE k.parent_object_id = OBJECT_ID(N'payment_method') AND k.type = 'UQ' AND c.name = 'type'"
			+ " AND NOT EXISTS (SELECT 1 FROM sys.index_columns o WHERE o.object_id = k.parent_object_id"
			+ " AND o.index_id = k.unique_index_id AND o.column_id <> c.column_id));"
			+ " IF @uk IS NULL BREAK;"
			+ " SET @sql = N'ALTER TABLE payment_method DROP CONSTRAINT ' + QUOTENAME(@uk);"
			+ " EXEC(@sql);"
			+ " END";

	static final String TYPE_NULLABLE = "DECLARE @type nvarchar(200) = (SELECT TYPE_NAME(user_type_id)"
			+ " + CASE WHEN max_length = -1 THEN '(max)' WHEN TYPE_NAME(user_type_id) IN ('nvarchar', 'nchar')"
			+ " THEN '(' + CAST(max_length / 2 AS varchar(10)) + ')' ELSE '(' + CAST(max_length AS varchar(10)) + ')' END"
			+ " FROM sys.columns WHERE object_id = OBJECT_ID(N'payment_method') AND name = 'type' AND is_nullable = 0);"
			+ " IF @type IS NOT NULL EXEC('ALTER TABLE payment_method ALTER COLUMN type ' + @type + ' NULL')";

	static final String TYPE_INDEX = "IF COL_LENGTH(N'payment_method', N'type') IS NOT NULL"
			+ " AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = '" + INDEX
			+ "' AND object_id = OBJECT_ID(N'payment_method'))"
			+ " CREATE UNIQUE INDEX " + INDEX + " ON payment_method (type) WHERE type IS NOT NULL";

	static final String BEHAVIOUR_COLUMN = "IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL"
			+ " AND COL_LENGTH(N'payment_method', N'behaviour') IS NULL"
			+ " ALTER TABLE payment_method ADD behaviour varchar(255) NULL";

	static final String TITLE_NUMBER_LENGTH_COLUMN = "IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL"
			+ " AND COL_LENGTH(N'payment_method', N'title_number_length') IS NULL"
			+ " ALTER TABLE payment_method ADD title_number_length int NULL";

	/** The four settings of before, one per type that had a N° titre (3.2.0: the length is the method's). */
	static final String SETTINGS = "''PAYMENT_METHOD_CLIENT_CHEQUE_TITLE_NUMBER_LENGTH'',"
			+ " ''PAYMENT_METHOD_TICKET_RESTAURANT_TITLE_NUMBER_LENGTH'', ''PAYMENT_METHOD_CHEQUE_CADEAU_TITLE_NUMBER_LENGTH'',"
			+ " ''PAYMENT_METHOD_CLIENT_TRAITE_TITLE_NUMBER_LENGTH''";

	/** In EXEC: compiled once the two columns exist. */
	static final String CONVERSION = "IF COL_LENGTH(N'payment_method', N'behaviour') IS NOT NULL"
			+ " AND COL_LENGTH(N'payment_method', N'title_number_length') IS NOT NULL"
			+ " AND OBJECT_ID(N'general_setup', N'U') IS NOT NULL"
			+ " EXEC('BEGIN TRY BEGIN TRANSACTION;"
			+ " UPDATE m SET m.title_number_length = CASE WHEN t.v <> '''' AND t.v NOT LIKE ''%[^0-9]%'' AND LEN(t.v) <= 9"
			+ " THEN NULLIF(CAST(t.v AS int), 0) END"
			+ " FROM payment_method m JOIN general_setup g ON g.code = ''PAYMENT_METHOD_'' + m.type + ''_TITLE_NUMBER_LENGTH''"
			+ " CROSS APPLY (SELECT LTRIM(RTRIM(g.valeur)) AS v) t"
			+ " WHERE m.type IS NOT NULL AND m.title_number_length IS NULL;"
			+ " UPDATE payment_method SET type = NULL, behaviour = ''AMOUNT'', updated_at = SYSDATETIME(), updated_by = ''System''"
			+ " WHERE type IN (''TICKET_RESTAURANT'', ''CHEQUE_CADEAU'');"
			+ " UPDATE payment_method SET behaviour = ''AMOUNT'' WHERE behaviour IS NULL;"
			+ " DELETE FROM general_setup WHERE code IN (" + SETTINGS + ");"
			+ " COMMIT TRANSACTION;"
			+ " END TRY BEGIN CATCH IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION; THROW; END CATCH')";

	/** The statements in their order: each one a batch of its own (db/3.2.0/update.sql: each followed by GO). */
	/** Step 3b: "send to the ERP" per method, replacing ERP_SKIP_CHEQUE_PAYMENTS. */
	static final String SEND_TO_ERP_COLUMN = "IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL"
			+ " AND COL_LENGTH(N'payment_method', N'send_to_erp') IS NULL"
			+ " ALTER TABLE payment_method ADD send_to_erp bit NULL";

	/**
	 * Step 3b, in one transaction, only on the rows not set yet: the return voucher off (never sent), the cheque off when
	 * ERP_SKIP_CHEQUE_PAYMENTS was true, every other method on; then the setting is deleted (it existed on an
	 * installation with an ERP only: without it, the cheque is on). In EXEC: compiled once the column exists.
	 */
	static final String SEND_TO_ERP_CONVERSION = "IF COL_LENGTH(N'payment_method', N'send_to_erp') IS NOT NULL"
			+ " AND OBJECT_ID(N'general_setup', N'U') IS NOT NULL"
			+ " EXEC('BEGIN TRY BEGIN TRANSACTION;"
			+ " UPDATE payment_method SET send_to_erp = CASE WHEN type = ''RETURN_VOUCHER'' THEN 0"
			+ " WHEN type = ''CLIENT_CHEQUE'' AND EXISTS (SELECT 1 FROM general_setup"
			+ " WHERE code = ''ERP_SKIP_CHEQUE_PAYMENTS'' AND LOWER(LTRIM(RTRIM(valeur))) = ''true'') THEN 0 ELSE 1 END"
			+ " WHERE send_to_erp IS NULL;"
			+ " DELETE FROM general_setup WHERE code = ''ERP_SKIP_CHEQUE_PAYMENTS'';"
			+ " COMMIT TRANSACTION;"
			+ " END TRY BEGIN CATCH IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION; THROW; END CATCH')";

	/**
	 * Step 4, a store: a scanned ticket is held by one sale at most, across tills (the database decides when two scan it at
	 * the same moment); the rows that gave the ticket back (live_barcode null) are left out. Hibernate creates the table
	 * (an entity of the store), not a filtered index.
	 */
	static final String SCANNED_TICKET_LIVE_INDEX = "IF OBJECT_ID(N'scanned_ticket_payment', N'U') IS NOT NULL"
			+ " AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_scanned_ticket_live_barcode'"
			+ " AND object_id = OBJECT_ID(N'scanned_ticket_payment'))"
			+ " CREATE UNIQUE INDEX ux_scanned_ticket_live_barcode ON scanned_ticket_payment (live_barcode)"
			+ " WHERE live_barcode IS NOT NULL";

	/**
	 * Step 5: a card on a terminal (CLIENT_TPE) asks its slip number, optional (the N° titre, askTitleNumber); a fixed
	 * method's switches are not the admin's, so the row is set here, only while not set (empty). The column is
	 * Hibernate's (or update.sql's): read through EXEC, the batch compiles before it exists.
	 */
	static final String TPE_SLIP_NUMBER = "IF COL_LENGTH(N'payment_method', N'ask_title_number') IS NOT NULL"
			+ " EXEC('UPDATE payment_method SET ask_title_number = 1 WHERE type = ''CLIENT_TPE'' AND ask_title_number IS NULL')";

	static final List<String> STATEMENTS = Arrays.asList(DROP_TYPE_UNIQUE_CONSTRAINT, TYPE_NULLABLE, TYPE_INDEX,
			BEHAVIOUR_COLUMN, TITLE_NUMBER_LENGTH_COLUMN, CONVERSION, SEND_TO_ERP_COLUMN, SEND_TO_ERP_CONVERSION,
			SCANNED_TICKET_LIVE_INDEX, TPE_SLIP_NUMBER);

	private static final List<String> NAMES = Arrays.asList("the unique constraint on payment_method.type dropped",
			"payment_method.type nullable", "the index " + INDEX, "payment_method.behaviour",
			"payment_method.title_number_length", "Ticket Restaurant and Chèque Cadeau converted, the lengths copied",
			"payment_method.send_to_erp", "send_to_erp set, ERP_SKIP_CHEQUE_PAYMENTS deleted",
			"the index ux_scanned_ticket_live_barcode", "the terminal's slip number asked (optional)");

	private final JdbcTemplate jdbc;

	public PaymentMethodsUpgrade(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void run() {
		for (int i = 0; i < STATEMENTS.size(); i++) {
			try {
				jdbc.execute(STATEMENTS.get(i));
			} catch (RuntimeException e) {
				log.warn("Payment methods (3.2.0): {} not done ({}); db/3.2.0/update.sql does it", NAMES.get(i),
						e.getMessage());
			}
		}
	}
}
