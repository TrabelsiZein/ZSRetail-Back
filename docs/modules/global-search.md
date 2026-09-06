# Global Search Bar

**Status**: ✅ Complete (multilingual, role-based)

**Overview:**
- NavBar search icon opens a full-screen search overlay (Vuexy built-in SearchBar component, previously disabled with `v-if="false"`).
- Searches across all admin pages (38 pages) and 7 reports grouped into "Pages" and "Reports" sections.
- Role-based: visible only to ADMIN and RESPONSIBLE users.
- Multilingual: translates titles and group labels on every keypress (EN / FR / AR).

### Activation — `Navbar.vue`
- Changed `v-if="false"` → `v-if="isAdminOrResponsible"` on the `<search-bar>` component.
- Added computed property:
```js
isAdminOrResponsible() {
  const userData = getUserData()
  return userData && (userData.role === 'ADMIN' || userData.role === 'RESPONSIBLE')
}
```

### Data — `search-and-bookmark-data.js`
- Completely rewritten. Each entry has: `titleKey` (i18n key), `route` (named route object), `icon` (Feather icon name), `roles` (array of allowed roles).
- Two groups:
  - `Pages`: 38 entries covering Dashboard, Catalog, Customers, Loyalty, Purchases, Sales, Sessions, Franchise, Administration.
  - `Reports`: 7 entries (Sales, Purchases, Stock, Stock Movements, Loyalty, Sessions, Promotions).
- Entry format example:
```js
{ titleKey: 'admin.search.pages.dashboard', route: { name: 'home' }, icon: 'HomeIcon', roles: ['ADMIN', 'RESPONSIBLE'] }
```

### Component — `SearchBar.vue`
- Added `getCurrentInstance` from `@vue/composition-api` to access `vm.$t()` inside `setup()`.
- Role filtering: `const userRole = getUserData()?.role || ''` at module level; each item filtered via `.filter(item => !item.roles || item.roles.includes(userRole))`.
- Multilingual: `searchProps.data` is a **JavaScript getter** (not a plain property) so `useAutoSuggest` reads fresh translated titles on every keypress:
```js
const searchProps = {
  get data() {
    return Object.fromEntries(
      Object.entries(rawSearchData).map(([grp, cfg]) => [
        grp, {
          ...cfg,
          data: cfg.data
            .filter(item => !item.roles || item.roles.includes(userRole))
            .map(item => ({ ...item, title: vm.$t(item.titleKey) })),
        },
      ])
    )
  },
  searchLimit: 6,
}
```
- Group headers translated: `{{ $t(\`admin.search.groups.${grp_name}\`) }}`.
- Placeholder translated: `:placeholder="$t('admin.search.placeholder')"`.

### Translation Keys — `en.json` / `fr.json` / `ar.json`
```json
"admin": {
  "search": {
    "placeholder": "Search pages and reports...",
    "groups": {
      "Pages": "Pages",
      "Reports": "Reports"
    },
    "pages": {
      "dashboard": "Dashboard",
      "statistics": "Statistics",
      "items": "Items",
      "itemFamilies": "Item Families",
      "itemSubfamilies": "Item Subfamilies",
      "itemBarcodes": "Item Barcodes",
      "printLabels": "Print Labels",
      "salesPrices": "Sales Prices",
      "salesDiscounts": "Sales Discounts",
      "promotions": "Promotions",
      "customers": "Customers",
      "loyaltyMembers": "Loyalty Members",
      "loyaltyPrograms": "Loyalty Programs",
      "loyaltyTransactions": "Loyalty Transactions",
      "vendors": "Vendors",
      "purchaseHistory": "Purchase History",
      "newPurchase": "New Purchase",
      "vendorBalance": "Vendor Balance",
      "purchaseInvoices": "Purchase Invoices",
      "ticketsHistory": "Tickets History",
      "invoices": "Invoices",
      "returns": "Returns",
      "warrantyList": "Warranty List",
      "createWarranty": "Create Warranty",
      "paymentMethods": "Payment Methods",
      "locations": "Locations",
      "sessions": "Sessions",
      "sessionsHistory": "Sessions History",
      "badgeScanHistory": "Badge Scan History",
      "franchiseSalesTracking": "Franchise Sales Tracking",
      "franchiseSyncDashboard": "Franchise Sync Dashboard",
      "users": "Users",
      "generalSetup": "General Setup",
      "companyInformation": "Company Information",
      "dataImport": "Data Import",
      "erpJobs": "ERP Jobs",
      "erpCommunications": "ERP Communications"
    },
    "reports": {
      "sales": "Sales Report",
      "purchases": "Purchases Report",
      "stock": "Stock Report",
      "stockMovements": "Stock Movements Report",
      "loyalty": "Loyalty Report",
      "sessions": "Sessions Report",
      "promotions": "Promotions Report"
    }
  }
}
```

### Key Design Decision
- `useAutoSuggest` (core hook, not modified) calls `props.data` inside a `watch` callback triggered on every `searchQuery` change. Passing a getter-based object makes it re-evaluate `data` fresh every time, without modifying the hook or adding a watcher in the component.
