package com.digithink.zsretail.erp.navpospages;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.erp.navpospages.client.NavPosPagesRestClient;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesConfig;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesStartupCheck;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosResult;
import com.digithink.zsretail.erp.navpospages.reader.NavPosPagesReader;
import com.digithink.zsretail.support.Installations;

/**
 * ERP catalogue, step 5: a live read of the ERP named in configs/local/happyness_ho.properties, GET only. Skipped unless
 * -Dnavpospages.live=true (never in the normal suite). Fill domain, username and password in that file first (local,
 * never committed with real values). Prints per page: rows read, kept, left out by reason, five translated rows and the
 * time; for the barcodes the first two pages only. Writes nothing anywhere.
 * <p>
 * Run from Apps/ZSRetail-Back (JDK 21 and the settings of CLAUDE.md): mvn ... -Dtest=NavPosPagesLiveReadTest
 * -Dnavpospages.live=true test
 */
@EnabledIfSystemProperty(named = "navpospages.live", matches = "true")
class NavPosPagesLiveReadTest {

	static final String FILE = "local/happyness_ho.properties";

	@Test
	@DisplayName("Live read of the three pages (GET only), counts and samples printed")
	void liveRead() {
		MockEnvironment env = Installations.config(FILE);
		NavPosPagesStartupCheck.check(env);
		System.out.println(NavPosPagesStartupCheck.summary(env));
		NavPosPagesProperties properties = Binder.get(env).bind(NavPosPagesProperties.PREFIX, NavPosPagesProperties.class)
				.get();
		NavPosPagesReader reader = new NavPosPagesReader(
				new NavPosPagesRestClient(NavPosPagesConfig.restTemplate(properties), properties), properties);

		print("families (" + properties.getPage().getCategories() + ")", reader::readFamilies);
		print("sub-families (" + properties.getPage().getCategories() + ")", reader::readSubFamilies);
		print("items (" + properties.getPage().getItems() + ", location " + properties.getLocationCode() + ")",
				reader::readItems);
		long after = 0;
		for (int page = 1; page <= 2; page++) {
			long from = after;
			NavPosResult<?> barcodes = print("barcodes (" + properties.getPage().getBarcodes() + ") page " + page
					+ " after Entry_No " + from, () -> reader.readBarcodesAfter(from));
			if (barcodes.getHighestEntryNo() == null) {
				break;
			}
			after = barcodes.getHighestEntryNo();
		}
	}

	private static <T> NavPosResult<T> print(String title, Supplier<NavPosResult<T>> read) {
		long start = System.currentTimeMillis();
		NavPosResult<T> result = read.get();
		long millis = System.currentTimeMillis() - start;
		System.out.println("\n== " + title + ": " + result + " in " + millis + " ms");
		List<T> rows = result.getRows();
		for (int i = 0; i < Math.min(5, rows.size()); i++) {
			System.out.println("   " + describe(rows.get(i)));
		}
		return result;
	}

	/** The fields of an Erp*DTO (they have no toString). */
	private static String describe(Object dto) {
		StringBuilder text = new StringBuilder(dto.getClass().getSimpleName()).append(" {");
		for (java.lang.reflect.Method getter : dto.getClass().getMethods()) {
			if (getter.getParameterCount() == 0 && getter.getName().startsWith("get") && !getter.getName().equals("getClass")) {
				try {
					Object value = getter.invoke(dto);
					if (value != null) {
						text.append(' ').append(getter.getName().substring(3)).append('=').append(value);
					}
				} catch (ReflectiveOperationException e) {
					text.append(' ').append(getter.getName()).append("=?");
				}
			}
		}
		return text.append(" }").toString();
	}
}
