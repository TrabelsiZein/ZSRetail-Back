# Ticket templates ("Modèles de tickets") — 3.5.0 design

Agreed with Zein on 2026-11-26 (analysis on `release/3.5.0`, no code yet). The customers find the ticket too long (gaps,
paddings, margins) and want to choose what is printed. This file is the design and the order of the steps; when a step
is built, what it did goes to its module doc (to be created: `docs/modules/ticket-templates.md`) and to both CHANGELOGs.

## The rules that frame everything

- **The old print code stays untouched.** Zein has no ticket printer to test with. The new renderer sits behind ONE
  General setup switch, old code by default, for every ticket. The old code is removed later, only when Zein says so.
- **Default = today.** A store that changes nothing (no template row) prints what it prints today, with either code.
- **Old bugs are fixed in the new renderer only**, each as its own CHANGELOG Fixes line, in the step of its ticket.
- **Phase 2** (not in 3.5.0): direct printing from the back end (printer commands), reading the same template, behind a
  switch per POS machine; browser print stays the default. The model below must not block it.

## Today: what is printed on the ticket printer

Every ticket is HTML built in the front, written into a popup (`window.open`) and printed by the browser, 80 mm
(`@page size: 80mm auto`, content `max-width: 76mm`). Three code paths:

| Family | Where | Tickets | Data |
|---|---|---|---|
| A | `ZSRetail-Front/src/components/ReceiptTemplate.vue`, mounted off screen; its `outerHTML` with **only** `receiptPrintStyles.js` | sale (`Payment.vue`, split bill too), duplicate (`TicketsHistory.vue`), return voucher (`ReturnProducts.vue`), voucher reprint (`ReturnsManagement.vue`), cash refund slip (`ReturnProducts.vue`), warranty and its duplicate (`WarrantyCreate.vue`, `WarrantyList.vue`), tombola slip (end of a sale ticket) | sale: the map of `POST /sales-header/process-sale` / `complete-pending/{id}`; duplicate: `GET /sales-header/{id}/details` (payments under `payments`); return, refund, warranty: **rebuilt in the front** as a fake sale |
| B | `ZSRetail-Front/src/views/admin/session/printSessionReport.js`, HTML strings, one `STYLE` | session ticket X / Z (`SessionReportTicketDTO`), store ticket X / Z (`StoreReportTicketDTO`), cash movement slip (`CashMovementDTO`), closing withdrawal slip (`ClosingWithdrawalDTO`) | `GET /cashier-session/{id}/report-ticket`, `/store-report-ticket/x|z`, `POST /cash-movement`, `/cashier-session/{id}/details` |
| C | `ZSRetail-Front/src/views/pos/ItemSelection.vue` `printSessionCloseTicket`, inline CSS (a third copy) | the cashier's closing ticket when `CASHIER_CLOSING_TICKET=DECLARED` | `sessionCloseTicket` of `POST /cashier-session/close` |

Not tickets (A4 or other printers): the day journal (`printDayJournal.js`), invoices (`InvoiceTemplate.vue`,
`invoicePrintStyles.js`), purchase and supply invoices, deliveries, the reports (`window.print`), `List.vue`, product
labels (`productLabelsPdfService.js`).

### The sale ticket's blocks, in print order (family A)

Logo · store name · address (`receiptPrintAddress`) · ━ · title (REÇU DE VENTE / GARANTIE / BON DE RETOUR /
REMBOURSEMENT) · DUPLICATA badge · number badge (voucher, warranty, refund) · ━ · number, [warranty: ticket, item,
start, end], [voucher / refund: original ticket], date, [voucher: valid until], cashier, customer code · ━ · column
header Article / Qté / Total · ━ · per line: "name — variant", quantity, line total TTC (no unit price), then the discount
row ("Remise *promotion* : -x (y %)"), then the "TVA : n%" row; the timbre fiscal is one of these lines (item `TAX_STAMP`,
quantity 1, VAT 0, `SalesHeaderService.addTaxStampLineIfEnabled`) · ━ · Remise totale, Total TVA (one amount, no
breakdown by rate), TOTAL · ━ PAIEMENTS ━, one row per method and its reference, ━, Total payé, Rendu · ━ ★ FIDÉLITÉ ━,
card, holder, points earned (`TICKET_SHOW_LOYALTY_EARNED`), used, deduction, balance (`TICKET_SHOW_LOYALTY_BALANCE`), ━
· ━ CODE128 barcode and its number ━ · thank-you text (or the voucher / warranty / refund text) · ═ · website, e-mail,
phone, fax, MF, footer note (`receiptFooterText`, else `invoiceFooterNote`) · "ZS Retail" · tombola slip.

### What makes it long

| What | Value (family A, `receiptPrintStyles.js`) |
|---|---|
| Text | 12 px, `line-height: 1.4` (a row is 16.8 px), everything weight 600 |
| Dividers | a line of ━ characters: one text row + `margin: 8px 0`, about 25 px (6.6 mm) each; 11 on a cash sale, 14 with a loyalty member, two in a row between the loyalty and the barcode |
| Rows | `.receipt-row` `margin-bottom: 4px`; `.total` 14 px, `margin-top: 8px` |
| Each sold line | name row + discount and VAT rows (10 px, `margin-bottom: 6px`) + `.receipt-item` `margin-bottom: 8px`: about 11 mm |
| Blocks | `.receipt-header` `margin-bottom: 15px`; h2 / h3 `margin: 5px 0`; blocks `margin-bottom: 10px`; `.payment-entry` 8px; `.receipt-section-header` `margin: 8px 0` |
| Badges | `.duplicate-badge` 18 px, padding 10, margin 10, border 3, red background (about 18 mm); `.voucher-badge` 14 px, padding 8, margin 10, border 2 |
| Barcode | `margin: 15px 0`, container `margin: 10px 0`, SVG 50 px, text `margin-top: 5px` |
| Footer | `margin-top: 20px` |
| End | `body` `padding-bottom: 10mm` + `.receipt-template` `padding-bottom: 10mm`: 20 mm of blank paper |

Estimate (CSS at 96 dpi, not measured on paper): about 27 cm for a 3-line cash sale, of which ~7 cm dividers and 2 cm
blank. Families B and C: dividers are a 1 px dashed border with `margin: 6px 0`; `.row` 4px, `.total` 6px, `.footer`
16px, `.signature` 28px; at the end an empty `<div style="margin-top:20px">` and the same 2 × 10 mm. Their length is
mostly content (every row printed, zeros included).

**"Today" is what `receiptPrintStyles.js` produces.** The component's scoped CSS and Bootstrap classes never reach the
popup: the tombola prints without its page break, border or sizes, the footer note is not in italics, `mt-1` / `mt-2`
do nothing. The new renderer reproduces the printed result, not the component's CSS.

### Old bugs (fixed in the new renderer only, step 3)

- Voucher reprint: its Total TVA can differ from the original (`ReturnsManagement` does not apply the voucher's share
  that `ReturnProducts` applies); it prints no DUPLICATA (the template hides the badge on a voucher although the
  caller asks for it); it prints "Caissier : System".
- Warranty: "Total TVA : 0,000" (it passes `taxAmount: 0`).
- Seen, not bugs to fix here: labels hard-coded in French (families B and C use i18n), `receiptBranding.js` unused, the
  warranty data builder copied in two files, the voucher's VAT computed by two copies of the same code.

### Settings that change a ticket today

- Company Information (per installation, never copied down): name, logo, MF, address fields, phone, fax, e-mail, website,
  `invoiceFooterNote` (also on the A4 invoices), and the 2.2.2 ticket fields `receiptPrintAddress`, `receiptShowThankYou`,
  `receiptThankYouText`, `receiptShowSoftwareLabel`, `receiptFooterText`.
- General setup: `TICKET_SHOW_LOYALTY_BALANCE`, `TICKET_SHOW_LOYALTY_EARNED` (sent as `showLoyalty*` in the sale
  responses), `TOMBOLA_ENABLED`, `CASHIER_CLOSING_TICKET` (Z / DECLARED), `ENABLE_TAX_STAMP` +
  `TAX_STAMP_VALUE_MILLIMES`, `RETURN_VOUCHER_VALIDITY_DAYS` (the date on the voucher). `POS_AMOUNTS_3_DECIMALS` changes
  nothing printed. Property `warranty.enabled`. No setting of width, copies or automatic print exists.

### Shared with the A4 documents (do not break)

`appConfig/companyInfo` and the Company Information page (the invoices read name, logo, MF, address, phone,
`invoiceFooterNote`); the helpers `itemLabel`, `formatCurrency`; the i18n keys `admin.sessionReport` and
`admin.storeTickets` (the A4 day journal uses them); the DTOs of family B feed screens too (StoreDay, the session
details). None of them is renamed or changed by this topic.

## Decisions (2026-11-26)

1. **Ownership**: a new domain, LOCAL by default (no change for any installation). Set to HEAD_OFFICE, the stores
   receive the templates read-only, by copies down. Last step.
2. **Paper width**: a default in the template (58 or 80 mm); a POS machine can override it (the printer belongs to the
   machine). No POS machine known (an admin reprint): the template's width.
3. **Internal tickets** (families B and C): width, size, compact, and one switch "hide the rows at zero". No block
   switches.
4. **Settings that move to the template page**: `receiptPrintAddress`, `receiptShowThankYou`,
   `receiptShowSoftwareLabel` (Company Information) and `TICKET_SHOW_LOYALTY_BALANCE`, `TICKET_SHOW_LOYALTY_EARNED`
   (General setup). With no template row, the default reads them as today; the first save of a template takes them
   over. The texts (thank-you, footer, identity) stay in Company Information. `TOMBOLA_ENABLED`,
   `CASHIER_CLOSING_TICKET`, `ENABLE_TAX_STAMP` stay where they are.
5. **Locked blocks** (Zein confirms the legal ones with his accountant; the work does not wait):
   - legal, in our opinion: store name, MF, ticket number, date and time, the item lines (name, quantity, amount), VAT
     (the line's rate and the total), total TTC, the timbre line (a sold line: no block hides it), DUPLICATA on every
     reprint;
   - functional: the voucher's number, barcode, amount and expiry date; the warranty's number, item and dates; the
     payments of a sale. The sale's barcode is not locked but switching it off warns (returns scan it);
   - to check with the accountant: the address, the unit price, an RNE identifier (Company Information has no such
     field today).

## The model

### Table `ticket_template` (core: both applications have it; db/3.5.0)

One row per document type; **no row = the built-in default** (so a new or untouched installation prints today's ticket,
and "Reset" deletes the row).

| Column | Meaning |
|---|---|
| `document_type` | unique: `SALE` (its duplicate too), `RETURN_VOUCHER` (its reprint too), `CASH_REFUND`, `WARRANTY`, `SESSION_REPORT` (X / Z), `STORE_REPORT`, `CLOSING_DECLARED`, `CASH_MOVEMENT_SLIP`, `CLOSING_WITHDRAWAL_SLIP` |
| `paper_width` | 58 or 80 |
| `font_size` | a level, `SMALL` / `NORMAL` / `LARGE` (`NORMAL` = today's 12 px) |
| `compact` | removes the gaps (the renderer's spacing, not the template's) |
| `hide_zero_rows` | internal tickets only (never the totals, the net or the control lines) |
| `blocks` | JSON, the blocks in print order: `[{ "code": "LOGO", "on": true, "options": {} }, ...]`; customer tickets only |
| `format_version` | the JSON's version, so a later block or option reads an older row |
| `origin` | LOCAL or HEAD_OFFICE (a copy received, read-only) |

`pos_machine.paper_width` (nullable): the machine's printer; null = the template's width.

**No CSS value in the template**: only levels (size, compact) and the width in mm (58 → 32 characters, 80 → 48 in
printer terms). That is what keeps phase 2 open: the same template drives ESC/POS (fonts A / B, double size, native
CODE128, raster logo).

**The block catalogue is the back end's** (one enum: per document type its blocks, which are locked, their options),
sent with `GET /ticket-templates`. The server refuses a locked block switched off and an unknown code. The front only
renders.

The template holds the layout; the texts and the identity stay in Company Information. A head office that sends its
templates sends no store's name or MF.

### The renderer (front), two layers

1. **One builder per document type**: API data + template → a printer-neutral list of lines: text (alignment, bold,
   size level), a row of 2 or 3 columns, a divider, a gap, a barcode, an image, a cut. The template is applied here;
   the voucher's VAT and the other front rebuilding of families A / C move here, once.
2. **One HTML renderer** of that list (width, size, compact), used for the print popup and the live preview.

Phase 2 adds a second renderer of the same list on the back end (ESC/POS). Note for phase 2: a server reaches only a
network printer (port 9100); a USB printer on a till needs a small local agent. The printer's address would be a
setting of the POS machine.

### The switch

One General setup row (store), for example `TICKET_RENDERER` = `OLD` (default) / `NEW`, sent in `GET /config`. Every
print call site asks it: `OLD` runs today's code untouched; `NEW` runs the new renderer for the tickets that already
have their builder, the others stay on the old code until their step. The template page applies only with `NEW` (the
page says so while the switch is `OLD`).

The old fields are **never cleared**: when a template takes over `receiptShowThankYou` and the others, the old code
still reads them, so switching back to `OLD` gives back exactly the old ticket.

### Other rules

- API: `GET /ticket-templates` (the catalogue and the rows), `PUT /ticket-templates/{type}`, `DELETE` = reset. The
  writes check their right themselves (`write:admin-ticket-templates`, joining `API_CHECKED_ADMIN_PERMISSIONS`); the
  page `read:admin-ticket-templates`.
- Event log: a template saved or reset is an administrator's gesture (`AdminEvents`, a new code, the type and the
  blocks changed), when `eventlog.enabled` is on.
- The live preview uses fixed sample data (a sale with a discount, a promotion, two VAT rates, a loyalty member,
  the timbre), at the chosen width.
- Fixture check: `tools/ticket-render-check.mjs` in the front, as the other tools: fixtures of each ticket's data and
  the expected lines, run on the builders. It comes on top of the switch, not instead of it.

## Steps

| # | What | Both repos? |
|---|---|---|
| 1 | Builder + HTML renderer for the sale ticket and its duplicate, **tombola included**, behind the switch; same output as today (no table yet: the default only, reading today's settings) | back: the switch row and `GET /config`; front: the renderer |
| 2 | Table, API and page "Modèles de tickets" with live preview, for the sale ticket: switches, order up / down, width, size, compact; new blocks off by default: unit price on the line, VAT by rate; `pos_machine.paper_width` | both |
| 3 | Return voucher, voucher reprint, refund slip, warranty, and the tombola's own look; the old bugs, one Fixes line each | front mostly |
| 4 | Internal tickets (families B and C): width, size, compact, hide the rows at zero | front mostly |
| 5 | Copies down from the head office (`DataDomain` + `ownership.<x>`, a `DownDomainProvider` calling `CopiesDownFeed.recordChange`, a store `DownHandler`, a copy DTO in `core.link.dto`); the store's page read-only when HEAD_OFFICE | both |

### Notes on the order and the cuts

- **The tombola belongs to step 1, not 3.** It is printed at the end of the sale ticket: with the switch on, a sale
  without it would not be "the same output as today". Step 1 reproduces it as it prints today (unstyled); step 3 may
  give it its intended look (the page break, the border), as an Enhancement.
- **The VAT by rate of step 2 needs the back end.** The sale response sends only the total VAT; the per-line amounts
  do not add up to it when a ticket discount applies (I-48 recomputes the header's VAT by rate without storing it). Step
  2 adds the breakdown to the sale responses (`process-sale`, `complete-pending`, `details`), as `SessionReportTicketDTO`
  has `vatLines`.
- **`pos_machine.paper_width` goes in step 2** (with the table, one db/3.5.0 script), not later: the sale ticket is the
  first printed with a known machine (the session's).
- **Step 2 also changes Company Information and General setup screens**: the three switches and the two loyalty rows
  show, on those pages, "now set in Modèles de tickets" once a template exists (the values kept, read by the old code).
- **Order 3 before 4 is right**: family A's other tickets reuse step 1's builder pieces; families B and C are a
  separate builder with a smaller template (no blocks).
- The head office has no till: before step 5 the page exists on a store only.
