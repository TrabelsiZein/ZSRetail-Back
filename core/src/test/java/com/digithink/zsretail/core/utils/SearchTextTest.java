package com.digithink.zsretail.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 3.1.0: the till's search text (SearchText): words, folding, matching. */
class SearchTextTest {

	@Test
	@DisplayName("Words: split on spaces, each once, at most 6; under 2 characters (spaces aside): none")
	void words() {
		assertEquals(Arrays.asList("vanil", "1l"), SearchText.words("  vanil   1l vanil "));
		assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f"), SearchText.words("a b c d e f g h"));
		assertEquals(Collections.emptyList(), SearchText.words(null));
		assertEquals(Collections.emptyList(), SearchText.words("  b  "));
		assertEquals(Arrays.asList("ab"), SearchText.words("ab"));
	}

	@Test
	@DisplayName("Fold: lower case without accents (French and Latin letters)")
	void fold() {
		assertEquals("apres shampoing", SearchText.fold("Après Shampoing"));
		assertEquals("creme", SearchText.fold("CRÈME"));
		assertEquals("demaquillant", SearchText.fold("DÉMAQUILLANT"));
		assertEquals("", SearchText.fold(null));
	}

	@Test
	@DisplayName("Matches: every word somewhere in the texts, in any order; never across two texts")
	void matches() {
		assertTrue(SearchText.matches(Arrays.asList("sham"), "Après Shampoing"));
		assertTrue(SearchText.matches(Arrays.asList("1l", "vanil"), "V H 52 VANILLE 1L"));
		assertTrue(SearchText.matches(Arrays.asList("vh52", "vanil"), "Vanille", "VH52-1L"));
		assertFalse(SearchText.matches(Arrays.asList("vanil", "2l"), "V H 52 VANILLE 1L"));
		assertFalse(SearchText.matches(Arrays.asList("le1"), "Vanille", "1L"));
		assertFalse(SearchText.matches(Collections.emptyList(), "Vanille"));
	}

	@Test
	@DisplayName("Starts with the first word, accents ignored")
	void startsWith() {
		assertTrue(SearchText.startsWithFirstWord(Arrays.asList("sham"), "Shampoing"));
		assertFalse(SearchText.startsWithFirstWord(Arrays.asList("sham"), "Après Shampoing"));
		assertTrue(SearchText.startsWithFirstWord(Arrays.asList("apres", "sham"), "Après Shampoing"));
	}
}
