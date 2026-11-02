package com.digithink.zsretail.core.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.digithink.zsretail.core.dto.ImportFieldMappingDTO;
import com.digithink.zsretail.core.dto.ImportPreviewDTO;
import com.digithink.zsretail.core.dto.ImportResultDTO;
import com.digithink.zsretail.core.dto.ImportResultDTO.RowError;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.Location;
import com.digithink.zsretail.core.model.SalesDiscount;
import com.digithink.zsretail.core.model.SalesPrice;
import com.digithink.zsretail.core.model.Vendor;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.model.enumeration.SalesDiscountSalesType;
import com.digithink.zsretail.core.model.enumeration.SalesDiscountType;
import com.digithink.zsretail.core.model.enumeration.SalesPriceType;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.repository.LocationRepository;
import com.digithink.zsretail.core.repository.SalesDiscountRepository;
import com.digithink.zsretail.core.repository.SalesPriceRepository;
import com.digithink.zsretail.core.repository.VendorRepository;
import com.digithink.zsretail.core.utils.Quantities;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Service
@RequiredArgsConstructor
@Log4j2
public class DataImportService {

    private static final int PREVIEW_ROW_LIMIT = 5;

    /** 3.1.2: the note of the stock movements an item import writes. */
    static final String IMPORT_NOTE = "Import Excel";

    private final ItemFamilyRepository itemFamilyRepository;
    private final ItemSubFamilyRepository itemSubFamilyRepository;
    private final ItemRepository itemRepository;
    private final ItemBarcodeRepository itemBarcodeRepository;
    private final VendorRepository vendorRepository;
    private final CustomerRepository customerRepository;
    private final LocationRepository locationRepository;
    private final SalesPriceRepository salesPriceRepository;
    private final SalesDiscountRepository salesDiscountRepository;

    /** Step 6: a head office that sends its catalogue records what an import saved; no bean on a store. */
    private final ObjectProvider<CatalogueHeadOfficeHooks> catalogueHooks;

    /** 3.1.2: the movement of the stock an item import writes and a new item's first cost, in the row's transaction. */
    private final StockMovementService stockMovementService;
    private final CostHistoryService costHistoryService;
    private final PlatformTransactionManager transactionManager;

    /** Step 6: the codes an import saved go to the stores of a head office (nothing elsewhere). */
    private void afterImport(CatalogueKind kind, List<String> codes) {
        CatalogueHeadOfficeHooks hooks = catalogueHooks.getIfAvailable();
        if (hooks != null && !codes.isEmpty()) {
            hooks.afterImport(kind, codes);
        }
    }

    public ImportPreviewDTO previewFile(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return new ImportPreviewDTO(new ArrayList<>(), new ArrayList<>(), 0);
            }

            List<String> columns = new ArrayList<>();
            for (Cell cell : headerRow) {
                String value = formatter.formatCellValue(cell).trim();
                if (!value.isEmpty()) {
                    columns.add(value);
                }
            }

            int totalRows = 0;
            List<List<String>> previewRows = new ArrayList<>();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;
                if (previewRows.size() < PREVIEW_ROW_LIMIT) {
                    List<String> rowData = new ArrayList<>();
                    for (int j = 0; j < columns.size(); j++) {
                        Cell cell = row.getCell(j, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        rowData.add(cell != null ? formatter.formatCellValue(cell).trim() : "");
                    }
                    previewRows.add(rowData);
                }
            }

            return new ImportPreviewDTO(columns, previewRows, totalRows);
        }
    }

    public ImportResultDTO executeImport(MultipartFile file, String entityType,
                                         List<ImportFieldMappingDTO> mapping) throws IOException {
        switch (entityType.toUpperCase()) {
            case "FAMILIES":    return importFamilies(file, mapping);
            case "SUBFAMILIES": return importSubFamilies(file, mapping);
            case "ITEMS":       return importItems(file, mapping);
            case "VENDORS":     return importVendors(file, mapping);
            case "CUSTOMERS":   return importCustomers(file, mapping);
            case "LOCATIONS":   return importLocations(file, mapping);
            case "BARCODES":    return importBarcodes(file, mapping);
            case "SALES_PRICES": return importSalesPrices(file, mapping);
            case "SALES_DISCOUNTS": return importSalesDiscounts(file, mapping);
            default:
                throw new IllegalArgumentException("Unknown entity type: " + entityType);
        }
    }

    // ─── Families ────────────────────────────────────────────────────────────

    private ImportResultDTO importFamilies(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        List<String> imported = new ArrayList<>(); // step 6: sent to the stores by a head office
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    String code = getRequiredValue(row, formatter, colIndex, fieldMap, "code", i);
                    String name = getRequiredValue(row, formatter, colIndex, fieldMap, "name", i);

                    ItemFamily family = itemFamilyRepository.findByCode(code).orElse(new ItemFamily());
                    family.setCode(code);
                    family.setName(name);
                    family.setDescription(getOptionalValue(row, formatter, colIndex, fieldMap, "description"));
                    String displayOrderStr = getOptionalValue(row, formatter, colIndex, fieldMap, "displayOrder");
                    if (displayOrderStr != null && !displayOrderStr.isEmpty()) {
                        family.setDisplayOrder(parseInteger(displayOrderStr));
                    }
                    if (family.getId() == null) {
                        family.setCreatedAt(LocalDateTime.now());
                        family.setCreatedBy("Import");
                    }
                    family.setUpdatedAt(LocalDateTime.now());
                    family.setUpdatedBy("Import");
                    itemFamilyRepository.save(family);
                    imported.add(family.getCode());
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("Family import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        afterImport(CatalogueKind.FAMILY, imported);
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Subfamilies ──────────────────────────────────────────────────────────

    private ImportResultDTO importSubFamilies(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        List<String> imported = new ArrayList<>(); // step 6: sent to the stores by a head office
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    String code = getRequiredValue(row, formatter, colIndex, fieldMap, "code", i);
                    String name = getRequiredValue(row, formatter, colIndex, fieldMap, "name", i);
                    String familyCode = getRequiredValue(row, formatter, colIndex, fieldMap, "familyCode", i);

                    ItemFamily family = itemFamilyRepository.findByCode(familyCode)
                            .orElseThrow(() -> new RuntimeException("Family not found: " + familyCode));

                    ItemSubFamily subFamily = itemSubFamilyRepository.findByCode(code).orElse(new ItemSubFamily());
                    subFamily.setCode(code);
                    subFamily.setName(name);
                    subFamily.setItemFamily(family);
                    subFamily.setDescription(getOptionalValue(row, formatter, colIndex, fieldMap, "description"));
                    String displayOrderStr = getOptionalValue(row, formatter, colIndex, fieldMap, "displayOrder");
                    if (displayOrderStr != null && !displayOrderStr.isEmpty()) {
                        subFamily.setDisplayOrder(parseInteger(displayOrderStr));
                    }
                    if (subFamily.getId() == null) {
                        subFamily.setCreatedAt(LocalDateTime.now());
                        subFamily.setCreatedBy("Import");
                    }
                    subFamily.setUpdatedAt(LocalDateTime.now());
                    subFamily.setUpdatedBy("Import");
                    itemSubFamilyRepository.save(subFamily);
                    imported.add(subFamily.getCode());
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("SubFamily import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        afterImport(CatalogueKind.SUBFAMILY, imported);
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Items ────────────────────────────────────────────────────────────────

    private ImportResultDTO importItems(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        List<String> imported = new ArrayList<>(); // step 6: sent to the stores by a head office
        int totalRows = 0;
        int[] costsIgnored = { 0 }; // 3.1.2: the costs of items that have a CMP, not imported

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    final int line = i;
                    int[] rowIgnored = { 0 };
                    imported.add(new TransactionTemplate(transactionManager)
                            .execute(status -> importItemRow(row, formatter, colIndex, fieldMap, line, rowIgnored)));
                    costsIgnored[0] += rowIgnored[0]; // counted once the row is imported
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("Item import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        afterImport(CatalogueKind.ITEM, imported);
        ImportResultDTO result = new ImportResultDTO(totalRows, successCount, errors.size(), errors);
        result.setCostsIgnored(costsIgnored[0]);
        return result;
    }

    /**
     * One row of the item import, in its own transaction (an error leaves nothing of the row). 3.1.2, the stock value
     * at a date: the stock column read, its movement with the item: a new item's OPENING_STOCK, an existing item's
     * difference as ADJUSTMENT_IN / ADJUSTMENT_OUT, note "Import Excel" (nothing when it does not change); a new item's
     * cost as its first cost history row (EXCEL_IMPORT, by Import), and so an existing item's initial cost (3.1.2, no CMP
     * before); the cost cell of an item with a CMP counted in costsIgnored[0]. Returns the item code.
     */
    private String importItemRow(Row row, DataFormatter formatter, Map<String, Integer> colIndex,
                                 Map<String, String> fieldMap, int i, int[] costsIgnored) {
        String itemCode = getRequiredValue(row, formatter, colIndex, fieldMap, "itemCode", i);
        String name = getRequiredValue(row, formatter, colIndex, fieldMap, "name", i);
        String unitPriceStr = getRequiredValue(row, formatter, colIndex, fieldMap, "unitPrice", i);

        Item item = itemRepository.findByItemCode(itemCode).orElse(new Item());
        boolean created = item.getId() == null;
        BigDecimal stockBefore = item.getStockQuantity();
        boolean stockRead = false;
        item.setItemCode(itemCode);
        item.setName(name);
        item.setUnitPrice(parseDouble(unitPriceStr));
        item.setDescription(getOptionalValue(row, formatter, colIndex, fieldMap, "description"));

        String vatStr = getOptionalValue(row, formatter, colIndex, fieldMap, "defaultVAT");
        if (vatStr != null && !vatStr.isEmpty()) {
            item.setDefaultVAT(parseInteger(vatStr));
        }
        // CMP: the cost of a new item, its starting weighted average. 3.1.2, the initial cost: an item already here
        // without a CMP takes a cost above 0 as its starting one; an item with a CMP keeps the average its entries wrote
        // (the cell ignored and counted; "Ajuster le CMP" on the item card changes it by hand)
        String costStr = getOptionalValue(row, formatter, colIndex, fieldMap, "costPrice");
        boolean initialCost = false;
        if (created && costStr != null && !costStr.isEmpty()) {
            item.setCostPrice(parseDouble(costStr));
        } else if (costStr != null && !costStr.isEmpty()) {
            // the CMP as stored, the row locked (ItemService.adjustCost): an entry made since the row was read is kept
            itemRepository.lockForCostChange(item.getId());
            Double stored = itemRepository.findCostPriceById(item.getId());
            item.setCostPrice(stored);
            if (stored != null) {
                costsIgnored[0]++;
            } else {
                Double cost = initialCostOf(costStr, i);
                if (cost != null && cost > 0) {
                    item.setCostPrice(cost);
                    initialCost = true;
                }
            }
        }
        String stockStr = getOptionalValue(row, formatter, colIndex, fieldMap, "stockQuantity");
        if (stockStr != null && !stockStr.isEmpty()) {
            refuseDecimalStock(getCell(row, colIndex, fieldMap, "stockQuantity"), stockStr);
            item.setStockQuantity(Quantities.of(parseInteger(stockStr))); // whole, read as in 2.2.0
            stockRead = true;
        }
        String minStockStr = getOptionalValue(row, formatter, colIndex, fieldMap, "minStockLevel");
        if (minStockStr != null && !minStockStr.isEmpty()) {
            item.setMinStockLevel(parseInteger(minStockStr));
        }
        item.setBarcode(getOptionalValue(row, formatter, colIndex, fieldMap, "barcode"));
        item.setUnitOfMeasure(getOptionalValue(row, formatter, colIndex, fieldMap, "unitOfMeasure"));
        item.setCategory(getOptionalValue(row, formatter, colIndex, fieldMap, "category"));
        item.setBrand(getOptionalValue(row, formatter, colIndex, fieldMap, "brand"));

        String familyCode = getOptionalValue(row, formatter, colIndex, fieldMap, "familyCode");
        if (familyCode != null && !familyCode.isEmpty()) {
            Optional<ItemFamily> family = itemFamilyRepository.findByCode(familyCode);
            if (family.isPresent()) {
                item.setItemFamily(family.get());
            } else {
                log.warn("Item row {}: family code '{}' not found, skipping family assignment", i + 1, familyCode);
            }
        }
        String subFamilyCode = getOptionalValue(row, formatter, colIndex, fieldMap, "subFamilyCode");
        if (subFamilyCode != null && !subFamilyCode.isEmpty()) {
            Optional<ItemSubFamily> subFamily = itemSubFamilyRepository.findByCode(subFamilyCode);
            if (subFamily.isPresent()) {
                item.setItemSubFamily(subFamily.get());
            } else {
                log.warn("Item row {}: subfamily code '{}' not found, skipping subfamily assignment", i + 1, subFamilyCode);
            }
        }

        if (created) {
            item.setCreatedAt(LocalDateTime.now());
            item.setCreatedBy("Import");
            item.setShowInPos(true);
        }
        item.setUpdatedAt(LocalDateTime.now());
        item.setUpdatedBy("Import");
        Item saved = itemRepository.save(item);
        if (stockRead && created) {
            stockMovementService.recordOpeningStock(saved.getId(), saved.getStockQuantity(), IMPORT_NOTE, "Import");
        } else if (stockRead) {
            BigDecimal before = stockBefore == null ? BigDecimal.ZERO : stockBefore;
            stockMovementService.recordAdjustment(saved.getId(), saved.getStockQuantity().subtract(before), IMPORT_NOTE);
        }
        if (created) {
            costHistoryService.record(saved.getId(), null, saved.getCostPrice(), saved.getStockQuantity(),
                    saved.getCostPrice(), CostHistoryService.Origin.of(CostChangeSource.EXCEL_IMPORT, "Import"));
        } else if (initialCost) {
            costHistoryService.record(saved.getId(), null, saved.getCostPrice(), null, saved.getCostPrice(),
                    CostHistoryService.Origin.of(CostChangeSource.EXCEL_IMPORT, "Import"));
        }
        return saved.getItemCode();
    }

    /**
     * 3.1.2: the initial cost of an existing item, or null when the cell is not a number: the row imports as before 3.1.2
     * (the cell was not read for an existing item), a warning logged.
     */
    private Double initialCostOf(String value, int i) {
        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (NumberFormatException e) {
            log.warn("Item row {}: cost '{}' is not a number, no initial cost set", i + 1, value);
            return null;
        }
    }

    // ─── Vendors ──────────────────────────────────────────────────────────────

    private ImportResultDTO importVendors(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    String vendorCode = getRequiredValue(row, formatter, colIndex, fieldMap, "vendorCode", i);
                    String name = getRequiredValue(row, formatter, colIndex, fieldMap, "name", i);
                    String phone = getRequiredValue(row, formatter, colIndex, fieldMap, "phone", i);

                    Vendor vendor = vendorRepository.findByVendorCode(vendorCode).orElse(new Vendor());
                    vendor.setVendorCode(vendorCode);
                    vendor.setName(name);
                    vendor.setPhone(phone);
                    vendor.setEmail(getOptionalValue(row, formatter, colIndex, fieldMap, "email"));
                    vendor.setAddress(getOptionalValue(row, formatter, colIndex, fieldMap, "address"));
                    vendor.setCity(getOptionalValue(row, formatter, colIndex, fieldMap, "city"));
                    vendor.setCountry(getOptionalValue(row, formatter, colIndex, fieldMap, "country"));
                    vendor.setTaxId(getOptionalValue(row, formatter, colIndex, fieldMap, "taxId"));
                    vendor.setNotes(getOptionalValue(row, formatter, colIndex, fieldMap, "notes"));

                    if (vendor.getId() == null) {
                        vendor.setCreatedAt(LocalDateTime.now());
                        vendor.setCreatedBy("Import");
                    }
                    vendor.setUpdatedAt(LocalDateTime.now());
                    vendor.setUpdatedBy("Import");
                    vendorRepository.save(vendor);
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("Vendor import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Customers ────────────────────────────────────────────────────────────

    private ImportResultDTO importCustomers(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    String customerCode = getRequiredValue(row, formatter, colIndex, fieldMap, "customerCode", i);
                    String name = getRequiredValue(row, formatter, colIndex, fieldMap, "name", i);
                    String phone = getRequiredValue(row, formatter, colIndex, fieldMap, "phone", i);

                    Customer customer = customerRepository.findByCustomerCode(customerCode).orElse(new Customer());
                    customer.setCustomerCode(customerCode);
                    customer.setName(name);
                    customer.setPhone(phone);
                    customer.setEmail(getOptionalValue(row, formatter, colIndex, fieldMap, "email"));
                    customer.setAddress(getOptionalValue(row, formatter, colIndex, fieldMap, "address"));
                    customer.setCity(getOptionalValue(row, formatter, colIndex, fieldMap, "city"));
                    customer.setCountry(getOptionalValue(row, formatter, colIndex, fieldMap, "country"));
                    customer.setTaxId(getOptionalValue(row, formatter, colIndex, fieldMap, "taxId"));
                    customer.setNotes(getOptionalValue(row, formatter, colIndex, fieldMap, "notes"));
                    String creditStr = getOptionalValue(row, formatter, colIndex, fieldMap, "creditLimit");
                    if (creditStr != null && !creditStr.isEmpty()) {
                        customer.setCreditLimit(parseDouble(creditStr));
                    }

                    if (customer.getId() == null) {
                        customer.setCreatedAt(LocalDateTime.now());
                        customer.setCreatedBy("Import");
                        customer.setIsDefault(false);
                    }
                    customer.setUpdatedAt(LocalDateTime.now());
                    customer.setUpdatedBy("Import");
                    customerRepository.save(customer);
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("Customer import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Locations ────────────────────────────────────────────────────────────

    private ImportResultDTO importLocations(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    String locationCode = getRequiredValue(row, formatter, colIndex, fieldMap, "locationCode", i);
                    String name = getRequiredValue(row, formatter, colIndex, fieldMap, "name", i);

                    Location location = locationRepository.findByLocationCode(locationCode).orElse(new Location());
                    location.setLocationCode(locationCode);
                    location.setName(name);
                    location.setDescription(getOptionalValue(row, formatter, colIndex, fieldMap, "description"));
                    location.setAddress(getOptionalValue(row, formatter, colIndex, fieldMap, "address"));
                    location.setCity(getOptionalValue(row, formatter, colIndex, fieldMap, "city"));
                    location.setState(getOptionalValue(row, formatter, colIndex, fieldMap, "state"));
                    location.setCountry(getOptionalValue(row, formatter, colIndex, fieldMap, "country"));
                    location.setPostalCode(getOptionalValue(row, formatter, colIndex, fieldMap, "postalCode"));
                    location.setPhone(getOptionalValue(row, formatter, colIndex, fieldMap, "phone"));
                    location.setEmail(getOptionalValue(row, formatter, colIndex, fieldMap, "email"));
                    location.setContactPerson(getOptionalValue(row, formatter, colIndex, fieldMap, "contactPerson"));
                    location.setNotes(getOptionalValue(row, formatter, colIndex, fieldMap, "notes"));
                    location.setResponsibilityCenter(getOptionalValue(row, formatter, colIndex, fieldMap, "responsibilityCenter"));

                    if (location.getId() == null) {
                        location.setCreatedAt(LocalDateTime.now());
                        location.setCreatedBy("Import");
                        if (location.getIsDefault() == null) {
                            location.setIsDefault(false);
                        }
                    }
                    location.setUpdatedAt(LocalDateTime.now());
                    location.setUpdatedBy("Import");
                    locationRepository.save(location);
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("Location import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Barcodes ─────────────────────────────────────────────────────────────

    private ImportResultDTO importBarcodes(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        List<String> imported = new ArrayList<>(); // step 6: sent to the stores by a head office
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    String barcodeValue = getRequiredValue(row, formatter, colIndex, fieldMap, "barcode", i);
                    String itemCode = getRequiredValue(row, formatter, colIndex, fieldMap, "itemCode", i);

                    Item item = itemRepository.findByItemCode(itemCode)
                            .orElseThrow(() -> new RuntimeException("Item not found: " + itemCode));

                    ItemBarcode barcode = itemBarcodeRepository.findByBarcode(barcodeValue)
                            .orElse(new ItemBarcode());

                    if (barcode.getId() != null && barcode.getItem() != null
                            && !barcode.getItem().getId().equals(item.getId())) {
                        throw new RuntimeException("Barcode '" + barcodeValue + "' already assigned to a different item");
                    }

                    barcode.setBarcode(barcodeValue);
                    barcode.setItem(item);
                    barcode.setDescription(getOptionalValue(row, formatter, colIndex, fieldMap, "description"));

                    String isPrimaryStr = getOptionalValue(row, formatter, colIndex, fieldMap, "isPrimary");
                    if (isPrimaryStr != null) {
                        barcode.setIsPrimary("true".equalsIgnoreCase(isPrimaryStr) || "1".equals(isPrimaryStr) || "yes".equalsIgnoreCase(isPrimaryStr));
                    }

                    if (barcode.getId() == null) {
                        barcode.setCreatedAt(LocalDateTime.now());
                        barcode.setCreatedBy("Import");
                    }
                    barcode.setUpdatedAt(LocalDateTime.now());
                    barcode.setUpdatedBy("Import");
                    itemBarcodeRepository.save(barcode);
                    imported.add(barcode.getBarcode());
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("Barcode import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        afterImport(CatalogueKind.BARCODE, imported);
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Sales Prices ─────────────────────────────────────────────────────────
    private ImportResultDTO importSalesPrices(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    String itemNo = getRequiredValue(row, formatter, colIndex, fieldMap, "itemNo", i);
                    SalesPriceType salesType = parseSalesPriceType(
                            getRequiredValue(row, formatter, colIndex, fieldMap, "salesType", i), i);
                    String salesCode = getRequiredValue(row, formatter, colIndex, fieldMap, "salesCode", i);
                    String responsibilityCenter = getRequiredValue(row, formatter, colIndex, fieldMap, "responsibilityCenter", i);
                    LocalDate startingDate = parseLocalDate(
                            getRequiredValue(row, formatter, colIndex, fieldMap, "startingDate", i), "startingDate", i);
                    String currencyCode = getRequiredValue(row, formatter, colIndex, fieldMap, "currencyCode", i);
                    String variantCode = getRequiredValue(row, formatter, colIndex, fieldMap, "variantCode", i);
                    String unitOfMeasureCode = getRequiredValue(row, formatter, colIndex, fieldMap, "unitOfMeasureCode", i);
                    Double minimumQuantity = parseDouble(
                            getRequiredValue(row, formatter, colIndex, fieldMap, "minimumQuantity", i));

                    SalesPrice salesPrice = salesPriceRepository
                            .findByItemNoAndSalesTypeAndSalesCodeAndResponsibilityCenterAndStartingDateAndCurrencyCodeAndVariantCodeAndUnitOfMeasureCodeAndMinimumQuantity(
                                    itemNo, salesType, salesCode, responsibilityCenter, startingDate, currencyCode,
                                    variantCode, unitOfMeasureCode, minimumQuantity)
                            .orElse(new SalesPrice());

                    salesPrice.setErpExternalId(getOptionalValue(row, formatter, colIndex, fieldMap, "erpExternalId"));
                    salesPrice.setItemNo(itemNo);
                    salesPrice.setSalesType(salesType);
                    salesPrice.setSalesCode(salesCode);
                    salesPrice.setResponsibilityCenter(responsibilityCenter);
                    salesPrice.setStartingDate(startingDate);
                    salesPrice.setCurrencyCode(currencyCode);
                    salesPrice.setVariantCode(variantCode);
                    salesPrice.setUnitOfMeasureCode(unitOfMeasureCode);
                    salesPrice.setMinimumQuantity(minimumQuantity);

                    String unitPrice = getOptionalValue(row, formatter, colIndex, fieldMap, "unitPrice");
                    salesPrice.setUnitPrice(unitPrice == null ? null : parseDouble(unitPrice));

                    String priceIncludesVat = getOptionalValue(row, formatter, colIndex, fieldMap, "priceIncludesVat");
                    salesPrice.setPriceIncludesVat(priceIncludesVat == null ? null : parseBoolean(priceIncludesVat));

                    salesPrice.setResponsibilityCenterType(
                            getOptionalValue(row, formatter, colIndex, fieldMap, "responsibilityCenterType"));

                    String endingDate = getOptionalValue(row, formatter, colIndex, fieldMap, "endingDate");
                    salesPrice.setEndingDate(endingDate == null ? null : parseLocalDate(endingDate, "endingDate", i));

                    if (salesPrice.getId() == null) {
                        salesPrice.setCreatedAt(LocalDateTime.now());
                        salesPrice.setCreatedBy("Import");
                    }
                    salesPrice.setUpdatedAt(LocalDateTime.now());
                    salesPrice.setUpdatedBy("Import");
                    salesPriceRepository.save(salesPrice);
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("SalesPrice import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Sales Discounts ──────────────────────────────────────────────────────
    private ImportResultDTO importSalesDiscounts(MultipartFile file, List<ImportFieldMappingDTO> mapping)
            throws IOException {
        List<RowError> errors = new ArrayList<>();
        int successCount = 0;
        int totalRows = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> colIndex = buildColumnIndex(sheet, formatter);
            Map<String, String> fieldMap = buildFieldMap(mapping);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row, formatter)) continue;
                totalRows++;

                try {
                    SalesDiscountType type = parseSalesDiscountType(
                            getRequiredValue(row, formatter, colIndex, fieldMap, "type", i), i);
                    String code = getRequiredValue(row, formatter, colIndex, fieldMap, "code", i);
                    SalesDiscountSalesType salesType = parseSalesDiscountSalesType(
                            getRequiredValue(row, formatter, colIndex, fieldMap, "salesType", i), i);
                    String salesCode = getRequiredValue(row, formatter, colIndex, fieldMap, "salesCode", i);
                    String responsibilityCenter = getRequiredValue(row, formatter, colIndex, fieldMap, "responsibilityCenter", i);
                    LocalDate startingDate = parseLocalDate(
                            getRequiredValue(row, formatter, colIndex, fieldMap, "startingDate", i), "startingDate", i);
                    String auxiliaryIndex1 = getRequiredValue(row, formatter, colIndex, fieldMap, "auxiliaryIndex1", i);
                    String auxiliaryIndex2 = getRequiredValue(row, formatter, colIndex, fieldMap, "auxiliaryIndex2", i);
                    String auxiliaryIndex3 = getRequiredValue(row, formatter, colIndex, fieldMap, "auxiliaryIndex3", i);
                    Integer auxiliaryIndex4 = parseInteger(
                            getRequiredValue(row, formatter, colIndex, fieldMap, "auxiliaryIndex4", i));

                    SalesDiscount salesDiscount = salesDiscountRepository
                            .findByTypeAndCodeAndSalesTypeAndSalesCodeAndResponsibilityCenterAndStartingDateAndAuxiliaryIndex1AndAuxiliaryIndex2AndAuxiliaryIndex3AndAuxiliaryIndex4(
                                    type, code, salesType, salesCode, responsibilityCenter, startingDate,
                                    auxiliaryIndex1, auxiliaryIndex2, auxiliaryIndex3, auxiliaryIndex4)
                            .orElse(new SalesDiscount());

                    salesDiscount.setErpExternalId(getOptionalValue(row, formatter, colIndex, fieldMap, "erpExternalId"));
                    salesDiscount.setType(type);
                    salesDiscount.setCode(code);
                    salesDiscount.setSalesType(salesType);
                    salesDiscount.setSalesCode(salesCode);
                    salesDiscount.setResponsibilityCenter(responsibilityCenter);
                    salesDiscount.setStartingDate(startingDate);
                    salesDiscount.setAuxiliaryIndex1(auxiliaryIndex1);
                    salesDiscount.setAuxiliaryIndex2(auxiliaryIndex2);
                    salesDiscount.setAuxiliaryIndex3(auxiliaryIndex3);
                    salesDiscount.setAuxiliaryIndex4(auxiliaryIndex4);

                    salesDiscount.setResponsibilityCenterType(
                            getOptionalValue(row, formatter, colIndex, fieldMap, "responsibilityCenterType"));

                    String endingDate = getOptionalValue(row, formatter, colIndex, fieldMap, "endingDate");
                    salesDiscount.setEndingDate(endingDate == null ? null : parseLocalDate(endingDate, "endingDate", i));

                    String lineDiscount = getOptionalValue(row, formatter, colIndex, fieldMap, "lineDiscount");
                    salesDiscount.setLineDiscount(lineDiscount == null ? null : parseDouble(lineDiscount));

                    if (salesDiscount.getId() == null) {
                        salesDiscount.setCreatedAt(LocalDateTime.now());
                        salesDiscount.setCreatedBy("Import");
                    }
                    salesDiscount.setUpdatedAt(LocalDateTime.now());
                    salesDiscount.setUpdatedBy("Import");
                    salesDiscountRepository.save(salesDiscount);
                    successCount++;
                } catch (Exception e) {
                    errors.add(new RowError(i + 1, e.getMessage()));
                    log.warn("SalesDiscount import row {} error: {}", i + 1, e.getMessage());
                }
            }
        }
        return new ImportResultDTO(totalRows, successCount, errors.size(), errors);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Integer> buildColumnIndex(Sheet sheet, DataFormatter formatter) {
        Map<String, Integer> index = new HashMap<>();
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return index;
        for (Cell cell : headerRow) {
            String name = formatter.formatCellValue(cell).trim();
            if (!name.isEmpty()) {
                index.put(name, cell.getColumnIndex());
            }
        }
        return index;
    }

    private Map<String, String> buildFieldMap(List<ImportFieldMappingDTO> mapping) {
        Map<String, String> map = new HashMap<>();
        for (ImportFieldMappingDTO m : mapping) {
            if (m.getExcelColumn() != null && !m.getExcelColumn().isEmpty()) {
                map.put(m.getDbField(), m.getExcelColumn());
            }
        }
        return map;
    }

    private Cell getCell(Row row, Map<String, Integer> colIndex, Map<String, String> fieldMap, String dbField) {
        String excelCol = fieldMap.get(dbField);
        if (excelCol == null) return null;
        Integer idx = colIndex.get(excelCol);
        if (idx == null) return null;
        return row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    /** A text read as a whole number with thousands separators, as {@link #parseInteger} reads it: 1.000, 12,500. */
    private static final Pattern THOUSANDS = Pattern.compile("-?[1-9]\\d{0,2}([.,])\\d{3}(\\1\\d{3})*");

    /**
     * 2.2.1, step 5: the stock of the item import stays whole, and a cell that is not whole is refused (the row is not
     * imported, its number is in the errors) instead of being read without its separator (a number cell 0.25 was read
     * as 25, 1.5 as 15; a text cell 0.250 as 250). A number cell is refused when its value has decimals; a text cell
     * when it has a separator and is not a whole number written with thousands separators (1.000 and 12,500 are still
     * read as 1000 and 12500, as in 2.2.0). Whole numbers are read as before.
     */
    static void refuseDecimalStock(Cell cell, String text) {
        boolean decimal;
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            decimal = value != Math.floor(value) || Double.isInfinite(value);
        } else {
            String trimmed = text.trim();
            decimal = (trimmed.contains(".") || trimmed.contains(",")) && !THOUSANDS.matcher(trimmed).matches();
        }
        if (decimal) {
            throw new RuntimeException("Stock quantity '" + text.trim() + "' is not a whole number: decimal stock"
                    + " quantities are not supported in the item import yet, the row is not imported.");
        }
    }

    private String getCellValue(Row row, DataFormatter formatter, Map<String, Integer> colIndex,
                                 Map<String, String> fieldMap, String dbField) {
        Cell cell = getCell(row, colIndex, fieldMap, dbField);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            double val = cell.getNumericCellValue();
            if (val == Math.floor(val)) {
                return String.valueOf((long) val);
            }
            return String.valueOf(val);
        }
        return formatter.formatCellValue(cell).trim();
    }

    private String getRequiredValue(Row row, DataFormatter formatter, Map<String, Integer> colIndex,
                                     Map<String, String> fieldMap, String dbField, int rowNum) {
        String value = getCellValue(row, formatter, colIndex, fieldMap, dbField);
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) {
            throw new RuntimeException("Required field '" + dbField + "' is missing or empty at row " + (rowNum + 1));
        }
        return value;
    }

    private String getOptionalValue(Row row, DataFormatter formatter, Map<String, Integer> colIndex,
                                     Map<String, String> fieldMap, String dbField) {
        String value = getCellValue(row, formatter, colIndex, fieldMap, dbField);
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) {
            return null;
        }
        return value;
    }

    private boolean isRowEmpty(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private Double parseDouble(String value) {
        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number format: '" + value + "'");
        }
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value.replace(",", "").replace(".", "").trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid integer format: '" + value + "'");
        }
    }

    private LocalDate parseLocalDate(String value, String fieldName, int rowNum) {
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new RuntimeException("Invalid date format for '" + fieldName + "' at row " + (rowNum + 1)
                    + ". Expected ISO format yyyy-MM-dd, got: '" + value + "'");
        }
    }

    private Boolean parseBoolean(String value) {
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        throw new RuntimeException("Invalid boolean format: '" + value + "' (accepted: true/false, 1/0, yes/no)");
    }

    private SalesPriceType parseSalesPriceType(String value, int rowNum) {
        SalesPriceType type = SalesPriceType.fromString(value);
        if (type == null) {
            throw new RuntimeException("Invalid salesType at row " + (rowNum + 1) + ": '" + value
                    + "'. Accepted values: CUSTOMER, CUSTOMER_PRICE_GROUP, ALL_CUSTOMERS");
        }
        return type;
    }

    private SalesDiscountType parseSalesDiscountType(String value, int rowNum) {
        SalesDiscountType type = SalesDiscountType.fromString(value);
        if (type == null) {
            throw new RuntimeException("Invalid type at row " + (rowNum + 1) + ": '" + value
                    + "'. Accepted values: ITEM, ITEM_DISC_GROUP");
        }
        return type;
    }

    private SalesDiscountSalesType parseSalesDiscountSalesType(String value, int rowNum) {
        SalesDiscountSalesType type = SalesDiscountSalesType.fromString(value);
        if (type == null) {
            throw new RuntimeException("Invalid salesType at row " + (rowNum + 1) + ": '" + value
                    + "'. Accepted values: CUSTOMER, CUSTOMER_DISC_GROUP, ALL_CUSTOMERS");
        }
        return type;
    }
}
