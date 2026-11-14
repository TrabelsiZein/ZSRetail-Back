package com.digithink.zsretail.store.cashmovement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.CashMovementType;

/**
 * 3.2.0, POS machines step 5: the movements of a session (or of several) added up per type, in BigDecimal to the
 * millime: the expected cash (net), the tickets X / Z (counts and totals), the sessions list and the head office copy
 * (in, out).
 */
public final class CashMovementTotals {

	private long apportCount;
	private BigDecimal apportTotal = BigDecimal.ZERO.setScale(3);
	private long prelevementCount;
	private BigDecimal prelevementTotal = BigDecimal.ZERO.setScale(3);
	private long depenseCount;
	private BigDecimal depenseTotal = BigDecimal.ZERO.setScale(3);

	private CashMovementTotals() {
	}

	/** The totals of these movements (none: every count 0). */
	public static CashMovementTotals of(Collection<CashMovement> movements) {
		CashMovementTotals totals = new CashMovementTotals();
		if (movements != null) {
			for (CashMovement movement : movements) {
				totals.add(movement.getType(), movement.getAmount());
			}
		}
		return totals;
	}

	private void add(CashMovementType type, Double amount) {
		if (type == null) {
			return;
		}
		BigDecimal value = amount == null ? BigDecimal.ZERO.setScale(3)
				: BigDecimal.valueOf(amount).setScale(3, RoundingMode.HALF_UP);
		switch (type) {
			case APPORT:
				apportCount++;
				apportTotal = apportTotal.add(value);
				break;
			case PRELEVEMENT:
				prelevementCount++;
				prelevementTotal = prelevementTotal.add(value);
				break;
			default:
				depenseCount++;
				depenseTotal = depenseTotal.add(value);
				break;
		}
	}

	public boolean isEmpty() {
		return apportCount + prelevementCount + depenseCount == 0;
	}

	public long getApportCount() {
		return apportCount;
	}

	public BigDecimal getApportTotal() {
		return apportTotal;
	}

	public long getPrelevementCount() {
		return prelevementCount;
	}

	public BigDecimal getPrelevementTotal() {
		return prelevementTotal;
	}

	public long getDepenseCount() {
		return depenseCount;
	}

	public BigDecimal getDepenseTotal() {
		return depenseTotal;
	}

	/** Cash in: the apports. */
	public BigDecimal getIn() {
		return apportTotal;
	}

	/** Cash out: the prélèvements and the dépenses. */
	public BigDecimal getOut() {
		return prelevementTotal.add(depenseTotal);
	}

	/** In - out: what the movements add to the expected cash. */
	public BigDecimal getNet() {
		return getIn().subtract(getOut());
	}
}
