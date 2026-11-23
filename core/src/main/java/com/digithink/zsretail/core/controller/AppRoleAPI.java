package com.digithink.zsretail.core.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.AppRoleRequestDTO;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.AppRoleService;

import lombok.extern.log4j.Log4j2;

/**
 * The roles. 3.2.0: the writes behind the Roles page's permissions (without them any logged user, a cashier included,
 * created, changed or deleted a role, and so gave their own role every permission): POST and PUT need write:admin-roles,
 * DELETE delete:admin-roles; on a head office its page's one key, read:admin-headoffice-roles, for all three. The reads
 * stay open to any logged user (the Users pages list the roles).
 */
@RestController
@RequestMapping("app-roles")
@Log4j2
public class AppRoleAPI {

    static final String WRITE_ROLES = "write:admin-roles";
    static final String DELETE_ROLES = "delete:admin-roles";

    /** The head office's Roles page has this one key, for reading and for its actions. */
    static final String HEAD_OFFICE_ROLES = "read:admin-headoffice-roles";

    @Autowired
    private AppRoleService appRoleService;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    @Autowired
    private ApplicationModeService applicationModeService;

    /** The 403 of a logged user whose role lacks the key; null when it holds it. */
    private ResponseEntity<?> refusedWithout(String key) {
        if (currentUserProvider.currentUserHasPermission(key) || (applicationModeService.isHeadOffice()
                && currentUserProvider.currentUserHasPermission(HEAD_OFFICE_ROLES))) {
            return null;
        }
        log.warn("AppRoleAPI: refused without the right {}", key);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "The roles need the right " + key + "."));
    }

    // All available permission keys — single source of truth for the frontend UI
    private static final List<String> ALL_PERMISSIONS = Arrays.asList(
        // Admin panel — menus
        "read:home",
        "read:admin-users", "write:admin-users", "delete:admin-users",
        "read:admin-sessions-history",
        // 3.2.0: the Journal (days, day journal, store tickets X / Z), its own right, never masked
        "read:day-journal",
        "read:tickets-history",
        "read:admin-item-barcodes",
        "read:admin-item-management",
        "read:admin-print-product-labels",
        "read:admin-item-families",
        "read:admin-item-subfamilies",
        "read:admin-customers", "write:admin-customers", "delete:admin-customers",
        "read:admin-vendors", "write:admin-vendors",
        "read:admin-locations",
        "read:admin-sales-prices",
        "read:admin-sales-discounts",
        "read:admin-promotions",
        "read:admin-payment-methods", "write:admin-payment-methods",
        "read:admin-general-setup",
        "read:admin-sales",
        "read:admin-returns",
        "read:admin-statistics",
        "read:admin-purchases",
        "read:purchase-history",
        "read:purchase-new", "write:purchase-new",
        "read:admin-purchase-invoices",
        // Inventory count (a store that keeps its own stock)
        "read:admin-inventory-counts", "write:admin-inventory-counts",
        // "Ajuster le CMP" on the item card, the line costs of the ticket detail (an installation that keeps its own stock)
        "write:admin-item-cost",
        "read:admin-margins",
        // Orders to the head office (a store whose goods come from the head office, 3.1.1)
        "read:admin-store-orders", "write:admin-store-orders",
        "read:admin-warranty", "write:admin-warranty",
        "read:admin-erp-jobs",
        "read:admin-erp-communications",
        "read:admin-invoices",
        "read:admin-badge-scan-history",
        // 3.2.0, step 7: the scanned meal tickets and gift cheques taken (a store)
        "read:admin-scanned-tickets",
        "read:admin-loyalty-members", "write:admin-loyalty-members",
        "read:admin-loyalty-programs", "write:admin-loyalty-programs",
        "read:admin-loyalty-transactions",
        "read:admin-loyalty-member-functions", "write:admin-loyalty-member-functions",
        "read:admin-data-import", "write:admin-data-import",
        "read:admin-report-sales",
        "read:admin-report-purchases",
        "read:admin-report-stock",
        "read:admin-report-stock-movements",
        "read:admin-report-loyalty",
        "read:admin-report-sessions",
        "read:admin-report-promotions",
        "read:admin-company-information", "write:admin-company-information",
        "read:admin-license",
        // 3.2.0: the POS machines page, every store (the key keeps its 3.1.0 name)
        "read:admin-rfid-pos-machines",
        // RFID (a store with rfid.enabled=true)
        "read:admin-rfid-readers", "read:admin-rfid-tags", "read:admin-rfid-assignment",
        // Role management
        "read:admin-roles", "write:admin-roles", "delete:admin-roles",
        // Feature-level actions
        "read:change-payment-method",
        "read:view-session-amounts",
        "read:verify-session",
        "read:prepare-invoice",
        // POS cashier interface
        "read:cashier-interface"
    );

    /** Step C (3.1.0): with the five till rights of the role (BadgePermission.key(), "till:..."), defined once there. */
    private static final List<String> ALL_PERMISSIONS_WITH_TILL;
    static {
        List<String> all = new ArrayList<>(ALL_PERMISSIONS);
        for (BadgePermission action : BadgePermission.values()) {
            all.add(action.key());
        }
        ALL_PERMISSIONS_WITH_TILL = Collections.unmodifiableList(all);
    }

    @GetMapping
    public List<AppRole> getAll() {
        return appRoleService.findAll();
    }

    @GetMapping("/permissions")
    public List<String> getAllPermissions() {
        return ALL_PERMISSIONS_WITH_TILL;
    }

    @GetMapping("/{id}")
    public AppRole getById(@PathVariable Long id) {
        return appRoleService.findById(id);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AppRoleRequestDTO dto) {
        ResponseEntity<?> refused = refusedWithout(WRITE_ROLES);
        if (refused != null) {
            return refused;
        }
        String actor = currentUserProvider.getCurrentUser().getUsername();
        return ResponseEntity.ok(appRoleService.create(
            dto.getName(), dto.getLabel(),
            Boolean.TRUE.equals(dto.getIsPosRole()),
            dto.getPermissions(), actor
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody AppRoleRequestDTO dto) {
        ResponseEntity<?> refused = refusedWithout(WRITE_ROLES);
        if (refused != null) {
            return refused;
        }
        String actor = currentUserProvider.getCurrentUser().getUsername();
        return ResponseEntity.ok(appRoleService.update(
            id, dto.getLabel(),
            Boolean.TRUE.equals(dto.getIsPosRole()),
            dto.getPermissions(), actor
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        ResponseEntity<?> refused = refusedWithout(DELETE_ROLES);
        if (refused != null) {
            return refused;
        }
        appRoleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
