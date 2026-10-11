package com.digithink.zsretail.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2.2.1: the quantity helper (scale 3, whole or not, plain writing, the one rounding of a line amount). */
class QuantitiesTest {

	@Test
	@DisplayName("Plain writing: no trailing zeros, never an exponent")
	void plain() {
		assertEquals("2", Quantities.plain(new BigDecimal("2.000")));
		assertEquals("0.2", Quantities.plain(new BigDecimal("0.200")));
		assertEquals("0.058", Quantities.plain(new BigDecimal("0.058")));
		assertEquals("100", Quantities.plain(new BigDecimal("100.000")));
		assertEquals("100", Quantities.normalize(new BigDecimal("100.000")).toString(), "not 1E+2");
		assertEquals("0", Quantities.plain(new BigDecimal("0.000")));
		assertEquals("-3", Quantities.plain(new BigDecimal("-3.000")));
		assertEquals("", Quantities.plain(null));
	}

	@Test
	@DisplayName("Whole: 2.000 and 0 are whole, 0.2 is not; decimals counted after trailing zeros")
	void whole() {
		assertTrue(Quantities.isWhole(new BigDecimal("2.000")));
		assertTrue(Quantities.isWhole(BigDecimal.ZERO));
		assertTrue(Quantities.isWhole(null));
		assertFalse(Quantities.isWhole(new BigDecimal("0.2")));
		assertEquals(2, Quantities.decimals(new BigDecimal("0.050")));
		assertEquals(4, Quantities.decimals(new BigDecimal("0.0581")));
		assertEquals(0, Quantities.decimals(new BigDecimal("12.000")));
	}

	@Test
	@DisplayName("A place not converted yet: a whole number passes, decimals throw with the place named")
	void wholeOrFail() {
		assertEquals(Integer.valueOf(2), Quantities.wholeOrFail(new BigDecimal("2.000"), "here"));
		assertNull(Quantities.wholeOrFail(null, "here"));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> Quantities.wholeOrFail(new BigDecimal("0.2"), "Ticket copy"));
		assertTrue(e.getMessage().startsWith("Ticket copy: quantity 0.2 is not a whole number"), e.getMessage());
		assertEquals(Long.valueOf(7), Quantities.wholeLongOrFail(7L, "row"));
		assertEquals(Integer.valueOf(4), Quantities.wholeIntOrFail(new BigDecimal("4.000"), "row"));
		assertThrows(IllegalStateException.class, () -> Quantities.wholeIntOrFail(new BigDecimal("4.8"), "row"));
	}

	@Test
	@DisplayName("Amounts round half up to the millime, through binary noise")
	void roundAmount() {
		assertEquals(1.001, Quantities.roundAmount(1.0005), 0.0, "1.0005 is stored as 1.000499...");
		assertEquals(6.173, Quantities.roundAmount(6.1725), 0.0);
		assertEquals(6.172, Quantities.roundAmount(6.17249), 0.0);
		assertEquals(10.0, Quantities.roundAmount(9.999999999999998), 0.0);
		assertEquals(0.0, Quantities.roundAmount(0.0), 0.0);
	}

	@Test
	@DisplayName("Line amount: a whole quantity gives the plain product of 2.2.0, a decimal one the rounded product")
	void lineAmount() {
		double unit = 50.0 / 1.19;
		assertEquals(unit * 3, Quantities.lineAmount(unit, new BigDecimal("3")), 0.0);
		assertEquals(unit * 3, Quantities.lineAmount(unit, new BigDecimal("3.000")), 0.0);
		assertEquals(8.403, Quantities.lineAmount(unit, new BigDecimal("0.2")), 0.0);
		assertEquals(2.9, Quantities.lineAmount(50.0, new BigDecimal("0.058")), 0.0);
	}
}
