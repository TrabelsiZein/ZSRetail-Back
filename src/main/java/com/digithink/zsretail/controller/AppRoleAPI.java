package com.digithink.zsretail.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.AppRoleRequestDTO;
import com.digithink.zsretail.model.AppRole;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.AppRoleService;

@RestController
@RequestMapping("app-roles")
public class AppRoleAPI {

    @Autowired
    private AppRoleService appRoleService;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    // All available permission keys — single source of truth for the frontend UI
    private static final List<String> ALL_PERMISSIONS = Arrays.asList(
        // Admin panel — menus
        "read:home",
        "read:admin-users", "write:admin-users", "delete:admin-users",
        "read:admin-sessions",
        "read:admin-sessions-history",
        "read:responsible-sessions", "write:responsible-sessions",
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
        "read:vendor-balance",
        "read:admin-purchase-invoices",
        "read:admin-warranty", "write:admin-warranty",
        "read:admin-erp-jobs",
        "read:admin-erp-communications",
        "read:admin-invoices",
        "read:admin-badge-scan-history",
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
        "read:admin-franchise",
        "read:admin-franchise-sales-tracking",
        "read:admin-franchise-sync-dashboard",
        "read:admin-company-information", "write:admin-company-information",
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

    @GetMapping
    public List<AppRole> getAll() {
        return appRoleService.findAll();
    }

    @GetMapping("/permissions")
    public List<String> getAllPermissions() {
        return ALL_PERMISSIONS;
    }

    @GetMapping("/{id}")
    public AppRole getById(@PathVariable Long id) {
        return appRoleService.findById(id);
    }

    @PostMapping
    public AppRole create(@RequestBody AppRoleRequestDTO dto) {
        String actor = currentUserProvider.getCurrentUser().getUsername();
        return appRoleService.create(
            dto.getName(), dto.getLabel(),
            Boolean.TRUE.equals(dto.getIsPosRole()),
            dto.getPermissions(), actor
        );
    }

    @PutMapping("/{id}")
    public AppRole update(@PathVariable Long id, @RequestBody AppRoleRequestDTO dto) {
        String actor = currentUserProvider.getCurrentUser().getUsername();
        return appRoleService.update(
            id, dto.getLabel(),
            Boolean.TRUE.equals(dto.getIsPosRole()),
            dto.getPermissions(), actor
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        appRoleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
