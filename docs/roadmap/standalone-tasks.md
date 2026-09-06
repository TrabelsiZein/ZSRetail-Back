# Standalone POS – Implementation Plan (Small Tasks)

This plan breaks down standalone-version needs into small, actionable tasks. Order reflects suggested priority; dependencies are noted.

---

## 1. Inventory (stock) – link to sales and purchases

| # | Task | Notes |
|---|------|------|
| 1.1 | **Decrement stock on sale** – When a ticket is completed, reduce `stockQuantity` (or equivalent) for each item by line quantity. | Backend: e.g. in ticket completion flow or in SalesLine save. Handle negative stock (allow or block by config). |
| 1.2 | **Increment stock on return** – When a return is processed, add back quantity to item stock. | Backend: return processing flow. |
| 1.3 | **Increment stock on purchase** – When a purchase is created (process-purchase), add line quantities to each item’s stock. | Backend: PurchaseHeaderService.processPurchase. |
| 1.4 | **Stock adjustment API** – Add endpoint (e.g. POST `/item/{id}/adjust-stock` or dedicated “inventory count” flow) with: quantity delta, reason (e.g. count, correction, damage). | Backend + optional small admin UI. |
| 1.5 | **Low-stock indicator** – In item list (and optionally in POS), show a badge or icon when `stockQuantity <= minStockLevel`. | Frontend: ItemManagement + optional POS item list. |
| 1.6 | **Prevent sale when out of stock (optional)** – Configurable: block or warn when adding an item with stock ≤ 0. | Backend + frontend POS. |

---

## 2. Purchasing – enhancements

| # | Task | Notes |
|---|------|------|
| 2.1 | **Purchase → stock** | Covered in 1.3. |
| 2.2 | **Paid status on purchase** – Add optional `paidAmount` and/or `paidDate` (or status “Paid”/“Unpaid”) on PurchaseHeader. | Backend: entity + migration/ddl, API to set paid. |
| 2.3 | **Vendor balance / AP summary** – Simple report or screen: per vendor, total purchased (and optionally total paid, unpaid). | Backend: query; Frontend: simple table or report page. |
| 2.4 | **Purchase report by period** – List or export purchases in a date range (e.g. CSV). | Backend: endpoint; Frontend: filters + export button. |

---

## 3. Reporting & export

| # | Task | Notes |
|---|------|------|
| 3.1 | **Sales by period** – Report: sales (and optionally returns) between two dates, totals by day or by period. | Backend: query (you have tickets); Frontend: report page with date range + table. |
| 3.2 | **Sales by payment method** – Same period, breakdown by payment method. | Backend: aggregate; Frontend: same report page or second tab. |
| 3.3 | **Sales by item / category** – Totals by item (and by category if you have it) for a period. | Backend: aggregate from ticket lines; Frontend: report page. |
| 3.4 | **Export to CSV/Excel** – For each report above, add “Export CSV” (and optionally Excel). | Frontend: reuse existing export pattern if any; else simple CSV download. |
| 3.5 | **Stock list report** – List items with current stock, optional: cost, value. Export CSV. | Backend: list items with stock; Frontend: report page + export. |
| 3.6 | **Session (Z) report** – Summary for a session: sales, returns, payments, cash expected vs counted. | Backend: you likely have session data; Frontend: session detail or report page. |

---

## 4. Standalone dashboard

| # | Task | Notes |
|---|------|------|
| 4.1 | **Dashboard route + page** – “Standalone dashboard” or reuse existing admin dashboard; show only when `standalone === true`. | Frontend: route, guard, simple layout. |
| 4.2 | **Today’s sales & returns** – Cards or summary: today’s total sales, total returns. | Backend: optional small API or use existing ticket APIs; Frontend: widgets. |
| 4.3 | **Top N items (today or period)** – e.g. Top 5 or Top 10 items by quantity or amount. | Backend: aggregate; Frontend: small table or list. |
| 4.4 | **Low-stock list** – List items where `stockQuantity <= minStockLevel` (or &lt; 1). | Backend: list/filter; Frontend: widget or link to filtered item list. |
| 4.5 | **Quick links** – Links to New sale, New purchase, Reports, Item list, etc. | Frontend only. |

---

## 5. Customers & loyalty (optional but valuable)

| # | Task | Notes |
|---|------|------|
| 5.1 | **Customer on ticket** – Ensure customer can be attached to ticket and shown on receipt. | You may have this; verify and document. |
| 5.2 | **Customer history** – Simple list of tickets (or total spend) per customer. | Backend: by customerId; Frontend: customer detail or history tab. |
| 5.3 | **Loyalty points or balance (optional)** – Field on customer: points or balance; increment/decrement by rules (e.g. spend 1 TND = 1 point). | Backend: entity + service; Frontend: show in POS and customer UI. |

---

## 6. Cash & sessions

| # | Task | Notes |
|---|------|------|
| 6.1 | **Session summary (Z-report)** | Same as 3.6. |
| 6.2 | **Starting cash + closing count** – Ensure open/close session captures starting cash and counted cash; store and show in session view. | Backend/Frontend: verify existing flow. |
| 6.3 | **Discrepancy handling** – If counted ≠ expected, allow note and store; show in session report. | Backend/Frontend: verify and document. |

---

## 7. User roles & permissions

| # | Task | Notes |
|---|------|------|
| 7.1 | **Permission matrix** – Document: Admin / Manager / Cashier can do what (e.g. discount, cancel ticket, return, adjust stock, see reports). | Doc or config. |
| 7.2 | **Enforce in backend** – Critical actions (e.g. void ticket, adjust stock) check role or permission. | Backend: use existing auth/roles. |
| 7.3 | **Hide UI by permission** – Buttons/menus (e.g. “Prepare invoice” in standalone, reports, item edit) hidden when user lacks permission. | Frontend: you have ACL; extend where needed. |

---

## 8. Backup & data

| # | Task | Notes |
|---|------|------|
| 8.1 | **Scheduled DB backup** – e.g. daily backup of DB (file or dump) to a local folder or configurable path. | Backend: scheduler + DB dump script or tool. |
| 8.2 | **Manual “Download backup”** – Admin action to trigger and download a backup (or get a link). | Backend: endpoint (auth); Frontend: button in settings/admin. |
| 8.3 | **Restore instructions** – Short doc: “How to restore from backup” for the used DB. | Documentation. |

---

## 9. Hardware & receipts

| # | Task | Notes |
|---|------|------|
| 9.1 | **Receipt layout** – Logo, header/footer, tax ID, legal text configurable (you have receipt components; ensure they’re configurable). | Frontend/config. |
| 9.2 | **Printer / drawer / scanner** – Document supported hardware and how to configure; fix any known issues. | Test + doc. |
| 9.3 | **Customer display (optional)** – If needed, add support for second screen for customer. | Optional. |

---

## 10. Tax & compliance

| # | Task | Notes |
|---|------|------|
| 10.1 | **Tax configuration** – Single or multiple VAT rates; tax-inclusive vs tax-exclusive pricing. | Backend: config or GeneralSetup; Frontend: settings UI. |
| 10.2 | **Legal/fiscal footer on receipt** – Configurable text (e.g. tax ID, company name, address). | Frontend: receipt template. |
| 10.3 | **Fiscal printer / legal compliance** – If required in target country, list as future phase. | Roadmap. |

---

## 11. Standalone-only UI cleanup

| # | Task | Notes |
|---|------|------|
| 11.1 | **Hide all ERP-only features when standalone** – Review: ERP menu, sync status columns, “Prepare invoice”, ERP logs, etc. | Frontend: consistent use of `isStandalone`. |
| 11.2 | **Standalone settings** – One place (e.g. Settings or General setup) for: backup path, receipt text, tax defaults, low-stock alert on/off. | Frontend: one settings page or section. |

---

## Suggested order (first 2–3 weeks)

1. **Inventory**
   - 1.3 (purchase → stock)  
   - 1.1 (sale → stock)  
   - 1.2 (return → stock)  
   - 1.4 (stock adjustment)  
   - 1.5 (low-stock indicator)

2. **Reporting**
   - 3.1 Sales by period  
   - 3.4 Export CSV (for that report)  
   - 3.5 Stock list report  
   - 3.6 Session Z-report

3. **Dashboard**
   - 4.1–4.5 (standalone dashboard with today’s sales, top items, low-stock, links)

4. **Purchasing**
   - 2.2 Paid status  
   - 2.3 Vendor balance  
   - 2.4 Purchase report

5. **Cleanup & hardening**
   - 11.1 Standalone UI cleanup  
   - 8.1–8.3 Backup

You can copy these tasks into your issue tracker or project board and tick them off as you go. If you tell me your stack (e.g. “we use Spring + Vue”), I can turn any task into step-by-step implementation notes.
