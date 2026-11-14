package com.digithink.zsretail.store.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SessionDashboardDTO {

	private Long id;
	private String sessionNumber;
	private Long cashierId;
	private String cashierFullName;
	private String cashierUsername;
	private LocalDateTime openedAt;
	private LocalDateTime closedAt;
	private SessionStatus status;
	private Double openingCash;
	private Double posUserClosureCash;
	private Double responsibleClosureCash;
	private Long salesCount;
	private Double totalSalesAmount; // Gross sales (sum of all sales, no returns subtracted)
	private Long returnsCount;
	private Double totalReturnsAmount;
	private Double simpleReturnsAmount;
	private Double voucherReturnsAmount;
	private Double cashDifference;
	private Double responsibleDifference;
	private String verificationNotes;
	private String verifiedByName;
	private LocalDateTime verifiedAt;
	private SynchronizationStatus synchronizationStatus;
	private String erpNo;
	/** 3.2.0, POS machines step 2: the POS machine the session was opened on; null without one. */
	private String posMachineName;

	public static SessionDashboardDTO fromEntity(CashierSession session, Long salesCount, Double totalSalesAmount) {
		return fromEntity(session, salesCount, totalSalesAmount, 0L, 0.0, 0.0, 0.0, null);
	}
	
	public static SessionDashboardDTO fromEntity(CashierSession session, Long salesCount, Double totalSalesAmount,
			Long returnsCount, Double totalReturnsAmount, Double simpleReturnsAmount, Double voucherReturnsAmount) {
		return fromEntity(session, salesCount, totalSalesAmount, returnsCount, totalReturnsAmount, 
			simpleReturnsAmount, voucherReturnsAmount, null);
	}
	
	public static SessionDashboardDTO fromEntity(CashierSession session, Long salesCount, Double totalSalesAmount,
			Long returnsCount, Double totalReturnsAmount, Double simpleReturnsAmount, Double voucherReturnsAmount,
			Double calculatedRealCash) {
		return fromEntity(session, salesCount, totalSalesAmount, returnsCount, totalReturnsAmount, simpleReturnsAmount,
				voucherReturnsAmount, calculatedRealCash, 0.0);
	}

	/**
	 * 3.2.0: {@code scannedFaceGap} = the session's scanned tickets' face total - their net (deduction + excess kept). A
	 * scanned-tickets method is declared at its face total and the sales hold its net: the system side gets the gap, so the
	 * list's differences follow the session details' rule (face against face) and a right count reads 0.
	 */
	public static SessionDashboardDTO fromEntity(CashierSession session, Long salesCount, Double totalSalesAmount,
			Long returnsCount, Double totalReturnsAmount, Double simpleReturnsAmount, Double voucherReturnsAmount,
			Double calculatedRealCash, double scannedFaceGap) {
		SessionDashboardDTO dto = new SessionDashboardDTO();
		dto.setId(session.getId());
		dto.setSessionNumber(session.getSessionNumber());
		
		if (session.getCashier() != null) {
			dto.setCashierId(session.getCashier().getId());
			dto.setCashierFullName(session.getCashier().getFullName() != null ?
				session.getCashier().getFullName() : session.getCashier().getUsername());
			dto.setCashierUsername(session.getCashier().getUsername());
		}
		
		dto.setOpenedAt(session.getOpenedAt());
		dto.setClosedAt(session.getClosedAt());
		dto.setStatus(session.getStatus());
		dto.setOpeningCash(session.getOpeningCash());
		
		// realCash used only locally for cashDifference calculation
		Double realCash = calculatedRealCash != null ? calculatedRealCash : session.getRealCash();

		dto.setPosUserClosureCash(session.getPosUserClosureCash());
		dto.setResponsibleClosureCash(session.getResponsibleClosureCash());
		dto.setSalesCount(salesCount != null ? salesCount : 0L);
		dto.setTotalSalesAmount(totalSalesAmount != null ? totalSalesAmount : 0.0); // Gross sales
		dto.setReturnsCount(returnsCount != null ? returnsCount : 0L);
		dto.setTotalReturnsAmount(totalReturnsAmount != null ? totalReturnsAmount : 0.0);
		dto.setSimpleReturnsAmount(simpleReturnsAmount != null ? simpleReturnsAmount : 0.0);
		dto.setVoucherReturnsAmount(voucherReturnsAmount != null ? voucherReturnsAmount : 0.0);
		
		// cashDifference: posUserClosureCash vs system; responsibleDifference: responsibleClosureCash vs system
		double openingCashVal = session.getOpeningCash() != null ? session.getOpeningCash() : 0.0;
		double salesVal = totalSalesAmount != null ? totalSalesAmount : 0.0;
		double simpleRetVal = simpleReturnsAmount != null ? simpleReturnsAmount : 0.0;
		double totalSystemAmount = openingCashVal + salesVal - simpleRetVal + scannedFaceGap;
		if (session.getPosUserClosureCash() != null) {
			dto.setCashDifference(com.digithink.zsretail.core.utils.Quantities.roundAmount(session.getPosUserClosureCash() - totalSystemAmount));
		}
		if (session.getResponsibleClosureCash() != null) {
			dto.setResponsibleDifference(com.digithink.zsretail.core.utils.Quantities.roundAmount(session.getResponsibleClosureCash() - totalSystemAmount));
		}
		
		dto.setVerificationNotes(session.getVerificationNotes());
		
		if (session.getVerifiedBy() != null) {
			dto.setVerifiedByName(session.getVerifiedBy().getFullName() != null ? 
				session.getVerifiedBy().getFullName() : session.getVerifiedBy().getUsername());
			dto.setVerifiedAt(session.getVerifiedAt());
		}
		
		dto.setSynchronizationStatus(session.getSynchronizationStatus());
		dto.setErpNo(session.getErpNo());
		
		return dto;
	}
}

