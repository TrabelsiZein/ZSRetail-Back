package com.digithink.zsretail.erp.dynamicsnav.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.dto.ErpReturnDTO;
import com.digithink.zsretail.erp.dto.ErpReturnLineDTO;
import com.digithink.zsretail.erp.dto.ErpSalesDiscountDTO;
import com.digithink.zsretail.erp.dto.ErpSalesPriceDTO;
import com.digithink.zsretail.erp.dto.ErpSessionDTO;
import com.digithink.zsretail.erp.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.dto.ErpTicketLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavBarcodeDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavCustomerDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavFamilyDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavLocationDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavLogEntryDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavReturnHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavReturnLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesDiscountDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesOrderHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesOrderLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesPriceDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSessionDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavStockKeepingUnitDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSubFamilyDTO;

@Component
public class DynamicsNavMapper {

	public List<ErpItemFamilyDTO> toItemFamilyDTOs(List<DynamicsNavFamilyDTO> navFamilies) {
		if (navFamilies == null) {
			return List.of();
		}
		return navFamilies.stream().map(this::toItemFamilyDTO).toList();
	}

	public ErpItemFamilyDTO toItemFamilyDTO(DynamicsNavFamilyDTO navFamily) {
		ErpItemFamilyDTO dto = new ErpItemFamilyDTO();
		dto.setExternalId(navFamily.getCode());
		dto.setCode(navFamily.getCode());
		dto.setName(navFamily.getDescription());
		dto.setDescription(navFamily.getDescription());
		dto.setActive(true);
		return dto;
	}

	public List<ErpItemSubFamilyDTO> toItemSubFamilyDTOs(List<DynamicsNavSubFamilyDTO> navSubFamilies) {
		if (navSubFamilies == null) {
			return List.of();
		}
		return navSubFamilies.stream().map(this::toItemSubFamilyDTO).toList();
	}

	public ErpItemSubFamilyDTO toItemSubFamilyDTO(DynamicsNavSubFamilyDTO navSubFamily) {
		ErpItemSubFamilyDTO dto = new ErpItemSubFamilyDTO();
		dto.setExternalId(navSubFamily.getCode());
		dto.setCode(navSubFamily.getCode());
		dto.setName(navSubFamily.getDescription());
		dto.setDescription(navSubFamily.getDescription());
		dto.setActive(true);
		dto.setFamilyExternalId(navSubFamily.getFamilyCode());
		return dto;
	}

	public List<ErpLocationDTO> toLocationDTOs(List<DynamicsNavLocationDTO> navLocations) {
		if (navLocations == null) {
			return List.of();
		}
		return navLocations.stream().map(this::toLocationDTO).toList();
	}

	public ErpLocationDTO toLocationDTO(DynamicsNavLocationDTO navLocation) {
		ErpLocationDTO dto = new ErpLocationDTO();
		dto.setExternalId(navLocation.getCode());
		dto.setCode(navLocation.getCode());
		dto.setName(navLocation.getName());
		dto.setResponsibilityCenter(navLocation.getResponsibilityCenter());
//		dto.setAddress(navLocation.getAddress());
//		dto.setCity(navLocation.getCity());
//		dto.setCountry(navLocation.getCountryRegionCode());
//		dto.setActive(navLocation.getBlocked() == null ? Boolean.TRUE : !navLocation.getBlocked());
		dto.setActive(Boolean.TRUE);
		return dto;
	}

	public List<ErpItemDTO> toItemDTOs(List<DynamicsNavStockKeepingUnitDTO> navItems) {
		if (navItems == null) {
			return List.of();
		}
		return navItems.stream().map(this::toItemDTO).toList();
	}

	public ErpItemDTO toItemDTO(DynamicsNavStockKeepingUnitDTO navItem) {
		ErpItemDTO dto = new ErpItemDTO();
		dto.setExternalId(navItem.getItemNo());
		dto.setCode(navItem.getItemNo());
		dto.setName(navItem.getDescription());
		dto.setDescription(navItem.getDescription());
		dto.setSubFamilyExternalId(navItem.getSubFamily());
		dto.setUnitPrice(navItem.getUnitPrice());
		dto.setDefaultVAT(navItem.getDefaultVAT());
		dto.setItemDiscGroup(navItem.getItemDiscGroup());
		dto.setMaximumAuthorizedDiscount(navItem.getMaximumAuthorizedDiscount());
		dto.setActive(true);
		dto.setLastModifiedAt(navItem.getModifiedAt());
		return dto;
	}

	public List<ErpItemBarcodeDTO> toItemBarcodeDTOs(List<DynamicsNavBarcodeDTO> navBarcodes) {
		if (navBarcodes == null) {
			return List.of();
		}
		return navBarcodes.stream().map(this::toItemBarcodeDTO).toList();
	}

	public ErpItemBarcodeDTO toItemBarcodeDTO(DynamicsNavBarcodeDTO navBarcode) {
		ErpItemBarcodeDTO dto = new ErpItemBarcodeDTO();
		dto.setExternalId(navBarcode.getCrossReferenceNo());
		dto.setItemExternalId(navBarcode.getItemNo());
		dto.setBarcode(navBarcode.getCrossReferenceNo());
		dto.setLastModifiedAt(navBarcode.getModifiedAt());
		return dto;
	}

	public List<ErpCustomerDTO> toCustomerDTOs(List<DynamicsNavCustomerDTO> navCustomers) {
		if (navCustomers == null) {
			return List.of();
		}
		return navCustomers.stream().map(this::toCustomerDTO).toList();
	}

	public ErpCustomerDTO toCustomerDTO(DynamicsNavCustomerDTO navCustomer) {
		ErpCustomerDTO dto = new ErpCustomerDTO();
		dto.setExternalId(navCustomer.getNumber());
		dto.setCode(navCustomer.getNumber());
		dto.setName(navCustomer.getName());
		dto.setEmail(navCustomer.getEmail());
		dto.setPhone(navCustomer.getPhoneNumber());
		dto.setAddress(buildFullAddress(navCustomer));
		dto.setLastModifiedAt(navCustomer.getLastModifiedAt());
		dto.setActive(navCustomer.getStatus().equals("Active"));
		dto.setCustomerPriceGroup(navCustomer.getCustomerPriceGroup());
		dto.setCustomerDiscGroup(navCustomer.getCustomerDiscGroup());
		dto.setAuxiliaryIndex1(navCustomer.getAuxiliaryIndex1());
		return dto;
	}

	private String buildFullAddress(DynamicsNavCustomerDTO navCustomer) {
		StringBuilder builder = new StringBuilder();
		if (navCustomer.getAddress() != null && !navCustomer.getAddress().isBlank()) {
			builder.append(navCustomer.getAddress());
		}
//		if (navCustomer.getAddress2() != null && !navCustomer.getAddress2().isBlank()) {
//			if (builder.length() > 0) {
//				builder.append(", ");
//			}
//			builder.append(navCustomer.getAddress2());
//		}
//		if (navCustomer.getPostCode() != null && !navCustomer.getPostCode().isBlank()) {
//			if (builder.length() > 0) {
//				builder.append(", ");
//			}
//			builder.append(navCustomer.getPostCode());
//		}
//		if (navCustomer.getCity() != null && !navCustomer.getCity().isBlank()) {
//			if (builder.length() > 0) {
//				builder.append(", ");
//			}
//			builder.append(navCustomer.getCity());
//		}
		return builder.length() == 0 ? null : builder.toString();
	}

	public List<ErpSalesPriceDTO> toSalesPriceDTOs(List<DynamicsNavSalesPriceDTO> navSalesPrices) {
		if (navSalesPrices == null) {
			return List.of();
		}
		return navSalesPrices.stream().map(this::toSalesPriceDTO).toList();
	}

	public ErpSalesPriceDTO toSalesPriceDTO(DynamicsNavSalesPriceDTO navSalesPrice) {
		ErpSalesPriceDTO dto = new ErpSalesPriceDTO();
		// Build composite externalId: Item_No + Sales_Type + Sales_Code +
		// Responsibility_Center + Starting_Date + Currency_Code + Variant_Code +
		// Unit_of_Measure_Code + Minimum_Quantity
		String externalId = buildSalesPriceExternalId(navSalesPrice.getItemNo(), navSalesPrice.getSalesType(),
				navSalesPrice.getSalesCode(), navSalesPrice.getResponsibilityCenter(), navSalesPrice.getStartingDate(),
				navSalesPrice.getCurrencyCode(), navSalesPrice.getVariantCode(), navSalesPrice.getUnitOfMeasureCode(),
				navSalesPrice.getMinimumQuantity());
		dto.setExternalId(externalId);
		dto.setItemNo(navSalesPrice.getItemNo());
		dto.setSalesType(navSalesPrice.getSalesType());
		dto.setSalesCode(navSalesPrice.getSalesCode());
		if (navSalesPrice.getUnitPrice() != null) {
			dto.setUnitPrice(java.math.BigDecimal.valueOf(navSalesPrice.getUnitPrice()));
		}
		dto.setPriceIncludesVat(navSalesPrice.getPriceIncludesVat());
		dto.setResponsibilityCenter(navSalesPrice.getResponsibilityCenter());
		dto.setResponsibilityCenterType(navSalesPrice.getResponsibilityCenterType());
		dto.setStartingDate(navSalesPrice.getStartingDate());
		dto.setEndingDate(navSalesPrice.getEndingDate());
		dto.setCurrencyCode(navSalesPrice.getCurrencyCode());
		dto.setVariantCode(navSalesPrice.getVariantCode());
		dto.setUnitOfMeasureCode(navSalesPrice.getUnitOfMeasureCode());
		dto.setMinimumQuantity(navSalesPrice.getMinimumQuantity());
		dto.setLastModifiedAt(navSalesPrice.getModifiedAt());
		return dto;
	}

	public List<ErpSalesDiscountDTO> toSalesDiscountDTOs(List<DynamicsNavSalesDiscountDTO> navSalesDiscounts) {
		if (navSalesDiscounts == null) {
			return List.of();
		}
		return navSalesDiscounts.stream().map(this::toSalesDiscountDTO).toList();
	}

	public ErpSalesDiscountDTO toSalesDiscountDTO(DynamicsNavSalesDiscountDTO navSalesDiscount) {
		ErpSalesDiscountDTO dto = new ErpSalesDiscountDTO();
		// Build composite externalId: Type + Code + Sales_Type + Sales_Code +
		// Responsibility_Center + Starting_Date + AuxiliaryIndex1-4
		String externalId = buildSalesDiscountExternalId(navSalesDiscount.getType(), navSalesDiscount.getCode(),
				navSalesDiscount.getSalesType(), navSalesDiscount.getSalesCode(),
				navSalesDiscount.getResponsibilityCenter(), navSalesDiscount.getStartingDate(),
				navSalesDiscount.getAuxiliaryIndex1(), navSalesDiscount.getAuxiliaryIndex2(),
				navSalesDiscount.getAuxiliaryIndex3(), navSalesDiscount.getAuxiliaryIndex4());
		dto.setExternalId(externalId);
		dto.setType(navSalesDiscount.getType());
		dto.setCode(navSalesDiscount.getCode());
		dto.setSalesType(navSalesDiscount.getSalesType());
		dto.setSalesCode(navSalesDiscount.getSalesCode());
		dto.setResponsibilityCenterType(navSalesDiscount.getResponsibilityCenterType());
		dto.setResponsibilityCenter(navSalesDiscount.getResponsibilityCenter());
		dto.setStartingDate(navSalesDiscount.getStartingDate());
		dto.setEndingDate(navSalesDiscount.getEndingDate());
		if (navSalesDiscount.getLineDiscount() != null) {
			dto.setLineDiscount(java.math.BigDecimal.valueOf(navSalesDiscount.getLineDiscount()));
		}
		dto.setLastModifiedAt(navSalesDiscount.getModifiedAt());
		dto.setAuxiliaryIndex1(navSalesDiscount.getAuxiliaryIndex1());
		dto.setAuxiliaryIndex2(navSalesDiscount.getAuxiliaryIndex2());
		dto.setAuxiliaryIndex3(navSalesDiscount.getAuxiliaryIndex3());
		dto.setAuxiliaryIndex4(navSalesDiscount.getAuxiliaryIndex4());
		return dto;
	}

	private String buildSalesPriceExternalId(String itemNo, String salesType, String salesCode,
			String responsibilityCenter, String startingDate, String currencyCode, String variantCode,
			String unitOfMeasureCode, Double minimumQuantity) {
		return (itemNo != null ? itemNo : "") + "|" + (salesType != null ? salesType : "") + "|"
				+ (salesCode != null ? salesCode : "") + "|"
				+ (responsibilityCenter != null ? responsibilityCenter : "") + "|"
				+ (startingDate != null ? startingDate : "") + "|" + (currencyCode != null ? currencyCode : "") + "|"
				+ (variantCode != null ? variantCode : "") + "|" + (unitOfMeasureCode != null ? unitOfMeasureCode : "")
				+ "|" + (minimumQuantity != null ? minimumQuantity.toString() : "");
	}

	private String buildSalesDiscountExternalId(String type, String code, String salesType, String salesCode,
			String responsibilityCenter, String startingDate, String auxiliaryIndex1, String auxiliaryIndex2,
			String auxiliaryIndex3, Integer auxiliaryIndex4) {
		return (type != null ? type : "") + "|" + (code != null ? code : "") + "|"
				+ (salesType != null ? salesType : "") + "|" + (salesCode != null ? salesCode : "") + "|"
				+ (responsibilityCenter != null ? responsibilityCenter : "") + "|"
				+ (startingDate != null ? startingDate : "") + "|" + (auxiliaryIndex1 != null ? auxiliaryIndex1 : "")
				+ "|" + (auxiliaryIndex2 != null ? auxiliaryIndex2 : "") + "|"
				+ (auxiliaryIndex3 != null ? auxiliaryIndex3 : "") + "|"
				+ (auxiliaryIndex4 != null ? auxiliaryIndex4.toString() : "");
	}

	/**
	 * Build Sales Price external ID from a Log entry. Returns null if entry is not
	 * for Source_Table "Sales Price".
	 */
	public String buildSalesPriceExternalIdFromLogEntry(DynamicsNavLogEntryDTO entry) {
		if (entry == null || !"Sales Price".equals(entry.getSourceTable())) {
			return null;
		}
		return buildSalesPriceExternalId(entry.getItemNo(), entry.getSalesType(), entry.getSalesCode(),
				entry.getResponsibilityCenter(), entry.getStartingDate(), entry.getCurrencyCode(),
				entry.getVariantCode(), entry.getUnitOfMeasureCode(), entry.getMinimumQuantity());
	}

	/**
	 * Build Sales Discount external ID from a Log entry. Returns null if entry is
	 * not for Source_Table "Sales Discount".
	 */
	public String buildSalesDiscountExternalIdFromLogEntry(DynamicsNavLogEntryDTO entry) {
		if (entry == null || !"Sales Discount".equals(entry.getSourceTable())) {
			return null;
		}
		return buildSalesDiscountExternalId(entry.getType(), entry.getCode(), entry.getSalesType(),
				entry.getSalesCode(), entry.getResponsibilityCenter(), entry.getStartingDate(),
				entry.getAuxiliaryIndex1(), entry.getAuxiliaryIndex2(), entry.getAuxiliaryIndex3(),
				entry.getAuxiliaryIndex4());
	}

	/**
	 * Map a NAV log entry to the neutral ERP DTO.
	 */
	public ErpDeletionLogEntryDTO toErpDeletionLogEntryDTO(DynamicsNavLogEntryDTO nav) {
		if (nav == null) {
			return null;
		}
		ErpDeletionLogEntryDTO dto = new ErpDeletionLogEntryDTO();
		dto.setSourceTable(nav.getSourceTable());
		dto.setItemNo(nav.getItemNo());
		dto.setLocationCode(nav.getLocationCode());
		dto.setVariantCode(nav.getVariantCode());
		dto.setSalesType(nav.getSalesType());
		dto.setSalesCode(nav.getSalesCode());
		dto.setStartingDate(nav.getStartingDate());
		dto.setEndingDate(nav.getEndingDate());
		dto.setResponsibilityCenter(nav.getResponsibilityCenter());
		dto.setType(nav.getType());
		dto.setCode(nav.getCode());
		dto.setCurrencyCode(nav.getCurrencyCode());
		dto.setUnitOfMeasureCode(nav.getUnitOfMeasureCode());
		dto.setMinimumQuantity(nav.getMinimumQuantity());
		dto.setAuxiliaryIndex1(nav.getAuxiliaryIndex1());
		dto.setAuxiliaryIndex2(nav.getAuxiliaryIndex2());
		dto.setAuxiliaryIndex3(nav.getAuxiliaryIndex3());
		dto.setAuxiliaryIndex4(nav.getAuxiliaryIndex4());
		dto.setModifiedAt(nav.getModifiedAt());
		dto.setDeletedBy(nav.getDeletedBy());
		return dto;
	}

	public List<ErpDeletionLogEntryDTO> toErpDeletionLogEntryDTOs(List<DynamicsNavLogEntryDTO> navList) {
		if (navList == null) {
			return List.of();
		}
		return navList.stream().map(this::toErpDeletionLogEntryDTO).toList();
	}

	/**
	 * Build Sales Price external ID from an ERP log entry DTO. Returns null if entry
	 * is not for Source_Table "Sales Price".
	 */
	public String buildSalesPriceExternalIdFromLogEntry(ErpDeletionLogEntryDTO entry) {
		if (entry == null || !"Sales Price".equals(entry.getSourceTable())) {
			return null;
		}
		return buildSalesPriceExternalId(entry.getItemNo(), entry.getSalesType(), entry.getSalesCode(),
				entry.getResponsibilityCenter(), entry.getStartingDate(), entry.getCurrencyCode(),
				entry.getVariantCode(), entry.getUnitOfMeasureCode(), entry.getMinimumQuantity());
	}

	/**
	 * Build Sales Discount external ID from an ERP log entry DTO. Returns null if
	 * entry is not for Source_Table "Sales Discount".
	 */
	public String buildSalesDiscountExternalIdFromLogEntry(ErpDeletionLogEntryDTO entry) {
		if (entry == null || !"Sales Discount".equals(entry.getSourceTable())) {
			return null;
		}
		return buildSalesDiscountExternalId(entry.getType(), entry.getCode(), entry.getSalesType(),
				entry.getSalesCode(), entry.getResponsibilityCenter(), entry.getStartingDate(),
				entry.getAuxiliaryIndex1(), entry.getAuxiliaryIndex2(), entry.getAuxiliaryIndex3(),
				entry.getAuxiliaryIndex4());
	}

	/**
	 * Convert ErpTicketDTO to DynamicsNavSalesOrderHeaderDTO
	 */
	public DynamicsNavSalesOrderHeaderDTO toSalesOrderHeaderDTO(ErpTicketDTO ticket) {
		DynamicsNavSalesOrderHeaderDTO dto = new DynamicsNavSalesOrderHeaderDTO();

		// Set customer information
		if (ticket.getCustomerExternalId() != null) {
			dto.setSellToCustomerNo(ticket.getCustomerExternalId());
		}

		// Optional invoice customer name (Bill_to_Name_2)
		if (ticket.getInvoiceCustomerName() != null) {
			dto.setBillToName2(ticket.getInvoiceCustomerName());
		}

		// Set responsibility center
		if (ticket.getResponsibilityCenter() != null) {
			dto.setResponsibilityCenter(ticket.getResponsibilityCenter());
		}

		// Set location code
		if (ticket.getLocationExternalId() != null) {
			dto.setLocationCode(ticket.getLocationExternalId());
		}

		// Set posting date
		if (ticket.getSaleDate() != null) {
			dto.setPostingDate(ticket.getSaleDate().toLocalDate());
		}

		// Set Fence_No to cashier session ID
		if (ticket.getCashierSessionId() != null) {
			dto.setFenceNo(ticket.getCashierSessionId());
		}

		// Set POS document number
		if (ticket.getTicketNumber() != null) {
			dto.setPosDocumentNo(ticket.getTicketNumber());
		}

		// Set discount percentage
		if (ticket.getDiscountPercentage() != null) {
			dto.setDiscountPercent(ticket.getDiscountPercentage());
		}

		// Set TicketAmount
		if (ticket.getTotalAmount() != null) {
			dto.setTicketAmount(ticket.getTotalAmount().doubleValue());
		}

		// Set POS_Order to false initially (sync task sets to true when all lines
		// synched)
		dto.setPosOrder(false);

		// POS_Invoice and Fiscal_Registration (from Prepare Invoice)
		if (ticket.getPosInvoice() != null) {
			dto.setPosInvoice(ticket.getPosInvoice());
		}
		if (ticket.getFiscalRegistration() != null) {
			dto.setFiscalRegistration(ticket.getFiscalRegistration());
		}

		return dto;
	}

	/**
	 * Convert ErpTicketLineDTO to DynamicsNavSalesOrderLineDTO Note: Line_No is not
	 * set as it's auto-generated by ERP
	 */
	public DynamicsNavSalesOrderLineDTO toSalesOrderLineDTO(ErpTicketLineDTO line, String documentNo) {
		DynamicsNavSalesOrderLineDTO dto = new DynamicsNavSalesOrderLineDTO();

		// Set document number
		if (documentNo != null) {
			dto.setDocumentNo(documentNo);
		}

		// Set item number
		if (line.getItemExternalId() != null) {
			dto.setNo(line.getItemExternalId());
		}

		// Set quantity
		if (line.getQuantity() != null) {
			dto.setQuantity(line.getQuantity().doubleValue());
		}

		// Set unit price
		if (line.getUnitPrice() != null) {
			dto.setUnitPrice(line.getUnitPrice().doubleValue());
		}

		// Set line discount percentage
		if (line.getDiscountPercentage() != null) {
			dto.setLineDiscountPercent(line.getDiscountPercentage().doubleValue());
		}

		// Set location code
		if (line.getLocationCode() != null) {
			dto.setLocationCode(line.getLocationCode());
		}

		// Type is read-only, so it's excluded from serialization via @JsonIgnore on
		// getter

		return dto;
	}

	/**
	 * Convert ErpReturnDTO to DynamicsNavReturnHeaderDTO
	 */
	public DynamicsNavReturnHeaderDTO toReturnHeaderDTO(ErpReturnDTO returnDTO) {
		DynamicsNavReturnHeaderDTO dto = new DynamicsNavReturnHeaderDTO();

		// Document_Type is set to "Return Order" by default in the DTO

		// Set customer number
		if (returnDTO.getCustomerExternalId() != null) {
			dto.setSellToCustomerNo(returnDTO.getCustomerExternalId());
		}

		// Set location code
		if (returnDTO.getLocationExternalId() != null) {
			dto.setLocationCode(returnDTO.getLocationExternalId());
		}

		// Set posting date
		if (returnDTO.getReturnDate() != null) {
			dto.setPostingDate(returnDTO.getReturnDate().toLocalDate());
		}

		// Set Fence_No to cashier session ID
		if (returnDTO.getCashierSessionId() != null) {
			dto.setFenceNo(returnDTO.getCashierSessionId());
		}

		// Set POS document number (return number from our POS)
		if (returnDTO.getReturnNumber() != null) {
			dto.setPosDocumentNo(returnDTO.getReturnNumber());
		}

		// Set Ticket_Amount — use the pre-computed net total (discount already applied)
		if (returnDTO.getTotalReturnAmount() != null) {
			dto.setTicketAmount(returnDTO.getTotalReturnAmount().doubleValue());
		}

		if (returnDTO.getDiscountPercentage() != null) {
			dto.setDiscountPercent(returnDTO.getDiscountPercentage());
		}

		return dto;
	}

	/**
	 * Convert ErpReturnLineDTO to DynamicsNavReturnLineDTO Note: Line_No is not set
	 * as it's auto-generated by ERP
	 */
	public DynamicsNavReturnLineDTO toReturnLineDTO(ErpReturnLineDTO line, String documentNo) {
		DynamicsNavReturnLineDTO dto = new DynamicsNavReturnLineDTO();

		// Document_Type is set to "Return Order" by default in the DTO

		// Set document number (from header erpNo)
		if (documentNo != null) {
			dto.setDocumentNo(documentNo);
		}

		// Type is set to "Item" by default in the DTO

		// Set item number
		if (line.getItemExternalId() != null) {
			dto.setNo(line.getItemExternalId());
		}

		// Set quantity
		if (line.getQuantity() != null) {
			dto.setQuantity(line.getQuantity().doubleValue());
			// Return_Qty_to_Receive = quantity (same as Quantity)
			dto.setReturnQtyToReceive(line.getQuantity().doubleValue());
		}

		// Set unit price (HT - excluding VAT)
		if (line.getUnitPrice() != null) {
			dto.setUnitPrice(line.getUnitPrice().doubleValue());
		}

		// Set line discount percentage from original sales line
		if (line.getDiscountPercentage() != null) {
			dto.setLineDiscountPercent(line.getDiscountPercentage().doubleValue());
		}

		if (line.getLocationCode() != null) {
			dto.setLocationCode(line.getLocationCode());
		}

		return dto;
	}

	/**
	 * Convert ErpSessionDTO to DynamicsNavSessionDTO
	 */
	public DynamicsNavSessionDTO toSessionDTO(ErpSessionDTO sessionDTO) {
		DynamicsNavSessionDTO dto = new DynamicsNavSessionDTO();

		// Set fence_no (session number)
		if (sessionDTO.getSessionNumber() != null) {
			dto.setFenceNo(sessionDTO.getSessionNumber());
		}

		// Set location
		if (sessionDTO.getLocationCode() != null) {
			dto.setLocation(sessionDTO.getLocationCode());
		}

		// Set number of tickets
		if (sessionDTO.getTicketCount() != null) {
			dto.setNberTicket(sessionDTO.getTicketCount());
		} else {
			dto.setNberTicket(0);
		}

		// Set closing amount
		if (sessionDTO.getClosingAmount() != null) {
			dto.setClosingAmount(sessionDTO.getClosingAmount());
		} else {
			dto.setClosingAmount(0.0);
		}

		// Set number of returns cashed
		if (sessionDTO.getReturnCashedCount() != null) {
			dto.setNberReturnCashed(sessionDTO.getReturnCashedCount());
		} else {
			dto.setNberReturnCashed(0);
		}

		// Set amount of returns cashed
		if (sessionDTO.getReturnCashedAmount() != null) {
			dto.setAmountReturnCashed(sessionDTO.getReturnCashedAmount().doubleValue());
		} else {
			dto.setAmountReturnCashed(0.0);
		}

		// Set number of returns (all returns - simple + voucher)
		if (sessionDTO.getReturnCount() != null) {
			dto.setNberReturn(sessionDTO.getReturnCount());
		} else {
			dto.setNberReturn(0);
		}

		return dto;
	}
}
