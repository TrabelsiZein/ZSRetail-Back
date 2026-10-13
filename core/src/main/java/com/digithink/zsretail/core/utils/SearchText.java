package com.digithink.zsretail.core.utils;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 3.1.0: the text typed in the till's search box (ItemService.searchPos, PosCatalogueService.search): its words, and
 * names compared without case and accents, as SQL Server's Latin1_General_CI_AI compares the items' names (the till
 * highlights the same way, src/libs/search-text.js).
 */
public final class SearchText {

	/** Shorter texts are not searched (one letter matches half the catalogue). */
	public static final int MIN_LENGTH = 2;

	/** The words searched at most (the rest are ignored). */
	public static final int MAX_WORDS = 6;

	private SearchText() {
	}

	/** The words of a text: split on spaces, each once, at most {@link #MAX_WORDS}; none under {@link #MIN_LENGTH}. */
	public static List<String> words(String text) {
		String trimmed = text == null ? "" : text.trim();
		if (trimmed.length() < MIN_LENGTH) {
			return Collections.emptyList();
		}
		return Arrays.stream(trimmed.split("\\s+")).distinct().limit(MAX_WORDS).collect(Collectors.toList());
	}

	/** Lower case without accents: "Après Shampoing" is "apres shampoing", "CRÈME" is "creme"; null is "". */
	public static String fold(String text) {
		if (text == null) {
			return "";
		}
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}

	/** True when every word is in one of the texts (case and accents ignored), anywhere and in any order. */
	public static boolean matches(List<String> words, String... texts) {
		String folded = Arrays.stream(texts).map(SearchText::fold).collect(Collectors.joining("\n"));
		return !words.isEmpty() && words.stream().map(SearchText::fold).allMatch(folded::contains);
	}

	/** True when the name starts with the first word (case and accents ignored): such names are listed first. */
	public static boolean startsWithFirstWord(List<String> words, String name) {
		return !words.isEmpty() && fold(name).startsWith(fold(words.get(0)));
	}
}
