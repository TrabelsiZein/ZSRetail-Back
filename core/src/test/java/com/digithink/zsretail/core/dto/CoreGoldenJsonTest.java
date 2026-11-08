package com.digithink.zsretail.core.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.Location;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.support.GoldenJson;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Boot 4 migration, step 1.2: what the till and the admin pages read from core, entities included (they are served as they
 * are), as the mapper of Spring MVC writes them; and the edge cases Jackson 3 changes, as a table of what each mapper does
 * today: a null into a primitive, an empty string into a number or a date, a decimal into an int, an unknown property.
 */
class CoreGoldenJsonTest {

	private static final ObjectMapper BOOT = GoldenJson.bootMapper();

	/** What the 20-odd mappers made by hand in main are: no Boot defaults. */
	private static final ObjectMapper BY_HAND = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	static Stream<Class<?>> served() {
		return Stream.of(AppRole.class, CashierSession.class, Customer.class, GeneralSetup.class, Item.class, ItemBarcode.class,
				ItemFamily.class, ItemSubFamily.class, Location.class, LoyaltyMember.class, Payment.class, PaymentMethod.class,
				Promotion.class, ReturnHeader.class, ReturnLine.class, SalesHeader.class, SalesLine.class, UserAccount.class,
				AppConfigDTO.class, CompanyInformationDTO.class, ItemCatalogueLineDTO.class, ItemLookupLineDTO.class,
				ItemStockMovementDTO.class, LoyaltyMemberDTO.class, PricingResult.class, TillActionDTO.class,
				UserAccountDTO.class);
	}

	@ParameterizedTest
	@MethodSource("served")
	void written(Class<?> served) throws Exception {
		GoldenJson.assertMatches("golden/core/" + served.getSimpleName() + ".json",
				BOOT.writeValueAsString(GoldenJson.sample(served)));
	}

	enum Kind {
		FIRST, SECOND
	}

	/** One field per kind of value; public fields: only the mapper's settings are under test. */
	static class Fields {
		public int anInt;
		public long aLong;
		public double aDouble;
		public boolean aBoolean;
		public Integer boxedInt;
		public Double boxedDouble;
		public java.math.BigDecimal decimal;
		public Boolean boxedBoolean;
		public LocalDate day;
		public LocalDateTime moment;
		public Kind kind;
		public String text;
		@JsonFormat(pattern = "yyyy-MM-dd")
		public LocalDate formatted;
	}

	private static final String[] INPUTS = {
			// A null into a primitive: 0 or false today, refused by Jackson 3's FAIL_ON_NULL_FOR_PRIMITIVES
			"anInt|null", "aLong|null", "aDouble|null", "aBoolean|null",
			// An empty string
			"anInt|\"\"", "aDouble|\"\"", "aBoolean|\"\"", "boxedInt|\"\"", "boxedDouble|\"\"", "decimal|\"\"", "boxedBoolean|\"\"",
			"day|\"\"", "moment|\"\"", "kind|\"\"", "formatted|\"\"",
			// A number that is not of the field's type
			"anInt|1.5", "boxedInt|1.5", "aLong|1.5", "anInt|2.0", "boxedDouble|2", "decimal|2", "text|12", "text|1.5", "text|true",
			// Text into a number or a boolean, and a number into a boolean
			"anInt|\"12\"", "aDouble|\"1.5\"", "decimal|\"1.5\"", "aBoolean|\"true\"", "aBoolean|1", "boxedBoolean|0",
			// Dates, in the forms a client may send
			"day|\"2026-03-04\"", "day|\"2026-03-04T05:06:07\"", "moment|\"2026-03-04T05:06:07\"", "moment|\"2026-03-04T05:06\"",
			"moment|\"2026-03-04\"", "moment|\"2026-03-04T05:06:07Z\"", "moment|\"2026-03-04 05:06:07\"", "formatted|\"2026-03-04\"",
			// Enums
			"kind|\"SECOND\"", "kind|\"second\"", "kind|\"NOPE\"", "kind|1",
			// Unknown property
			"unknown|1" };

	/** One line per input: what the mapper reads, or that it refuses it. */
	private static String table(ObjectMapper mapper) throws Exception {
		List<String> lines = new ArrayList<>();
		for (String input : INPUTS) {
			String field = input.substring(0, input.indexOf('|'));
			String value = input.substring(input.indexOf('|') + 1);
			String read;
			try {
				Fields fields = mapper.readValue("{\"" + field + "\":" + value + "}", Fields.class);
				read = "unknown".equals(field) ? "ignored" : String.valueOf(Fields.class.getField(field).get(fields));
			} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
				read = "refused";
			}
			lines.add(field + " = " + value + " -> " + read);
		}
		return String.join("\n", lines);
	}

	@Test
	@DisplayName("The edge cases, as the mapper of Spring MVC reads them")
	void edgesOfTheBootMapper() throws Exception {
		GoldenJson.assertMatches("golden/core/edges-boot-mapper.txt", table(BOOT));
	}

	@Test
	@DisplayName("The edge cases, as a mapper made by hand reads them")
	void edgesOfAMapperByHand() throws Exception {
		GoldenJson.assertMatches("golden/core/edges-mapper-by-hand.txt", table(BY_HAND));
	}
}
