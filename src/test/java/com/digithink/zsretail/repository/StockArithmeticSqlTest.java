package com.digithink.zsretail.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

/**
 * 2.2.1, step 6: SQL Server types a BigDecimal parameter as DECIMAL(38, scale of the value); DECIMAL(18,3) - DECIMAL(38,1)
 * needs more than 38 digits and the result is cut to the parameter's scale (1.742 - 0.2 gave 1.500, 1.442 - 1 gave
 * 0.000, seen on the local Happyness copies with mssql-jdbc 7.4.1). Every stock update doing arithmetic with a quantity
 * parameter casts it to DECIMAL(18,3) first.
 */
class StockArithmeticSqlTest {

	private static final Pattern PARAMETER_IN_ARITHMETIC = Pattern.compile("(stock_quantity, 0\\) *[-+>=]+ *)(\\S+)");

	@Test
	@DisplayName("ItemRepository: each stock update casts its quantity parameter to DECIMAL(18,3)")
	void itemRepositoryCasts() {
		int checked = 0;
		for (Method method : ItemRepository.class.getDeclaredMethods()) {
			Query query = method.getAnnotation(Query.class);
			if (query == null || !query.value().contains("stock_quantity, 0)")) {
				continue;
			}
			Matcher m = PARAMETER_IN_ARITHMETIC.matcher(query.value());
			while (m.find()) {
				assertTrue(m.group(2).startsWith("CAST(:"), method.getName() + ": " + query.value());
				checked++;
			}
		}
		assertEquals(4, checked, "addToStockQuantity, decrementStockQuantityIfSufficient (twice), decrementStockQuantityUnconditional");
	}

	@Test
	@DisplayName("StockBatchRepository: the batch stock update casts its parameter too")
	@SuppressWarnings("unchecked")
	void batchCasts() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		new StockBatchRepository(jdbc).addToStockQuantities(Collections.singletonMap(1L, new BigDecimal("-0.058")));
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).batchUpdate(sql.capture(), any(java.util.Collection.class), anyInt(),
				any(ParameterizedPreparedStatementSetter.class));
		assertEquals("UPDATE item SET stock_quantity = COALESCE(stock_quantity, 0) + CAST(? AS DECIMAL(18,3)) WHERE id = ?",
				sql.getValue());
	}
}
