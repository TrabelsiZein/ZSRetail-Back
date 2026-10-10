package com.digithink.zsretail.utils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * 2.2.1, step 5: the guard of a quantity that stays a whole number (cash counts, promotion settings, the free quantity
 * of a sale line, the quantities of the copies not converted). Jackson's global setting is unchanged: elsewhere a JSON
 * 1.5 is still read into an int as before. On a field annotated with
 * {@code @JsonDeserialize(using = WholeQuantityDeserializer.class)}, 2 and 2.0 give 2 as in 2.2.0, and 1.5 is refused
 * with {@link NotWholeQuantity} naming the field (a 400 on a request, see {@code WholeQuantityExceptionResolver}),
 * never read as 1. Every other value (null, text, a number too large) is read as in 2.2.0.
 */
public class WholeQuantityDeserializer extends StdDeserializer<Integer> {

	private static final long serialVersionUID = 1L;

	private static final NumberDeserializers.IntegerDeserializer AS_IN_220 = new NumberDeserializers.IntegerDeserializer(
			Integer.class, null);

	public WholeQuantityDeserializer() {
		super(Integer.class);
	}

	@Override
	public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		if (p.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
			BigDecimal value = p.getDecimalValue();
			if (!Quantities.isWhole(value)) {
				throw new NotWholeQuantity(p, path(p), value);
			}
		}
		return AS_IN_220.deserialize(p, ctxt);
	}

	/** The field being read, with its place: "lines[0].freeQuantity", "minimumQuantity". */
	static String path(JsonParser p) {
		Deque<String> parts = new ArrayDeque<>();
		for (JsonStreamContext context = p.getParsingContext(); context != null && !context.inRoot();
				context = context.getParent()) {
			if (context.inArray()) {
				parts.addFirst("[" + Math.max(0, context.getCurrentIndex()) + "]");
			} else if (context.getCurrentName() != null) {
				parts.addFirst("." + context.getCurrentName());
			}
		}
		String path = String.join("", parts);
		return path.isEmpty() ? "quantity" : path.startsWith(".") ? path.substring(1) : path;
	}

	/** A whole-number quantity sent with decimals; {@link #getOriginalMessage()} is the text of the 400. */
	public static class NotWholeQuantity extends JsonMappingException {

		private static final long serialVersionUID = 1L;

		private final String field;

		NotWholeQuantity(JsonParser p, String field, BigDecimal value) {
			super(p, field + ": " + Quantities.plain(value)
					+ " is not a whole number, and decimal quantities are not supported here yet.");
			this.field = field;
		}

		public String getField() {
			return field;
		}
	}
}
