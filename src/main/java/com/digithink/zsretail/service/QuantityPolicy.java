package com.digithink.zsretail.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.utils.Quantities;

/**
 * 2.2.1: who may use a decimal quantity. A store allows them with the general setting ALLOW_DECIMAL_QUANTITY (false by
 * default, "Allow decimal quantities"); without it a non-whole quantity is refused, as are more than 3 decimals
 * anywhere. Whole quantities always pass, without reading the setting.
 */
@Service
public class QuantityPolicy {

	public static final String SETTING = "ALLOW_DECIMAL_QUANTITY";

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	/** The quantity columns of the database (DECIMAL(18,3) after db/2.2.1/update.sql); absent in some unit tests. */
	@Autowired(required = false)
	private DecimalColumnsCheck decimalColumns;

	/**
	 * True when the general setting ALLOW_DECIMAL_QUANTITY is "true" and the quantity columns are DECIMAL(18,3) (with a
	 * column still int the setting is treated as off: DecimalColumnsCheck).
	 */
	public boolean decimalAllowed() {
		return settingOn() && columnsReady();
	}

	private boolean settingOn() {
		return generalSetupRepository.findByCode(SETTING).map(GeneralSetup::getValeur)
				.map(value -> "true".equalsIgnoreCase(value.trim())).orElse(false);
	}

	private boolean columnsReady() {
		return decimalColumns == null || decimalColumns.ready();
	}

	/**
	 * Refuses (IllegalArgumentException, shown to the cashier) a quantity with more than 3 decimals, or with decimals
	 * when the setting is off. Null, whole, zero or negative quantities pass: each caller keeps its own range checks.
	 *
	 * @param quantity the quantity typed
	 * @param itemLabel the item named in the message (its code)
	 */
	public void check(BigDecimal quantity, String itemLabel) {
		if (Quantities.isWhole(quantity)) {
			return;
		}
		if (Quantities.decimals(quantity) > Quantities.SCALE) {
			throw new IllegalArgumentException("Quantity " + Quantities.plain(quantity) + " of item " + itemLabel
					+ ": at most " + Quantities.SCALE + " decimals");
		}
		if (settingOn() && !columnsReady()) {
			throw new IllegalArgumentException("Quantity " + Quantities.plain(quantity) + " of item " + itemLabel + ": "
					+ DecimalColumnsCheck.SCRIPT_MESSAGE);
		}
		if (!decimalAllowed()) {
			throw new IllegalArgumentException("Quantity " + Quantities.plain(quantity) + " of item " + itemLabel
					+ ": decimal quantities are not allowed in this store (General Setup, Allow decimal quantities)");
		}
	}
}
