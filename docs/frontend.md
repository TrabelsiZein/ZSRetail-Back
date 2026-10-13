# Frontend Notes

- `ReceiptTemplate.vue`, `Payment.vue`, `ReturnProducts.vue`: unified barcode generation using `JsBarcode`; async print flows.
- Admin views: Returns, Locations, General Setup (with guarded editing), Item Families, Item Subfamilies; consistent pagination UI (per-page selector + "Showing X–Y of Z").
- Navigation (`src/navigation/vertical/index.js`) cleaned to avoid duplicate keys; new menu entries added for recent admin pages.
- ERP Jobs view now shows a compact table (no checkpoint columns) with row-click modal that surfaces full job details plus a modal "Run Now" button (with progress indicator) instead of inline buttons.
- New `ErpCommunications.vue` admin page lists ERP communication logs with search, status/operation filters, date range picker, pagination, and a detail modal for payloads.
- Item Barcodes admin view switched to a paginated table (server-driven search/filter/per-page) with expandable barcode details, mirroring the Locations view layout and offering "Has Barcode" filter options.
- POS cashier experience: `ItemSelection.vue` now tuned for kiosk usage—alphabetically sorted families/subfamilies, compact responsive cards, two-row max action panel, on-screen keyboard, and TND-only pricing with VAT-inclusive formatting throughout the cart.
- **Line-Level Discounts**: `ItemSelection.vue` now supports applying discounts to individual cart items. Users can apply discounts as a percentage or fixed amount via a modal dialog. Discounts are applied to totals including VAT, and all calculations (subtotal, tax, total) account for discounts correctly. Discount percentage is formatted to 3 decimal places (e.g., "78.463%"). Discount badge and chip display in cart items.
- **Internationalization (i18n)**: Comprehensive translation support added for all POS pages. All hardcoded strings replaced with translation keys using Vue i18n. Supported languages: English (en), Arabic (ar), French (fr). Translation files located in `src/libs/i18n/locales/`. RTL support for Arabic with proper styling adjustments. Language switcher added to login page. All pages fully translated including: Login, Open Session, Item Selection, Payment, Return Products, Customer Management, Tickets History, Returns Management, Item Barcodes, Item Families, Item Subfamilies, Session History.
- **2.2.2, POS grid**: `ItemSelection.vue` reads its families and sub-families from the POS endpoints (`/item-family/pos`, `/item-sub-family/pos/by-family/{id}`), which leave out the empty ones when the store settings "Hide empty families / sub-families in the POS screen" are on; a family's items without sub-family get one extra tile, or the family opens directly on them when it has no sub-family tile. See `modules/general-setup.md`, "POS screen: empty families and sub-families".
- **3.1.0, free pieces only when scanned** (decision of 2026-10-12, `modules/promotion.md`): a cart line's quantity is all
  its pieces, the free ones of a "buy N get M" included; `freeQuantity` comes from the back end, `paidQuantity`
  (`libs/quantity.js`) counts the amounts and the payment's line quantity (paid pieces, the free ones as
  `freeQuantity`, as before). "N free" badge, struck full price, "N free (included)", hint "N more piece(s) free"; a
  cross-product's free pieces go on Y's own line (no line added, a hint when Y is missing); a recalled pending or table
  ticket asks each line's price again (`libs/free-pieces.js`); the split bill refuses a line with free pieces.
- **3.1.0, POS search over the whole catalogue** (2026-10-13): the search box of `ItemSelection.vue` no longer filters
  the tiles on screen (families, sub-families or the open sub-family's items). From 2 characters (a watcher on
  `searchQuery`, so the on-screen keyboard counts too), 250 ms after the last keystroke, it asks `GET /item/pos/search?q=`
  and the grid shows, whatever level is open, the families found, then the sub-families found (their family's name
  under the tile), then the items, each with its place ("Family › Sub-family", `itemPlace`); a family opens as from
  the families, a sub-family opens on its items with its family in the breadcrumb (`openSearchedSubFamily`; Back: that
  family), an item goes to the cart as a grid item; an answer to an older text is dropped (`searchSeq`). The letters
  matching the search are coloured in names and codes (`libs/search-text.js`, `highlightParts`: every word, accents and
  case ignored as the server compares; plain text spans, never HTML). Each group of the results has a full-row header
  with its count ("Families (1)", `pos.itemSelection.search.groups`). Every tile that opens (family, sub-family, items
  without sub-family), in the grid and in the results, shows a folder icon, a chevron in its corner (mirrored in Arabic)
  and a light purple tint, so a cashier never takes it for an item (which shows its price). While searching, the
  breadcrumb is dimmed and a "🔍 text ✕" chip follows it (a click clears the search).
- **3.1.0, item tiles** (grid and search alike): the name is read first (larger, bold, up to 3 lines, since similar
  items differ at the end of their names; the full name as the tile's tooltip), no blank line kept under a short name,
  the price at the bottom (prices line up in a row). Without a picture, a small grey package mark instead of the large
  blue icon; with "Show images in the POS" off, no picture place at all. Compact view: 3 lines too. Tiles are never
  narrower than 140 px on a till screen (fewer columns at 1024 or 800 px, names read in full; phones keep 120 px). An
  item already in the cart shows its quantity in the tile's corner ("×2", `cartQuantities`).
- **3.1.0, payment page (`Payment.vue`), fixes**: the payments typed are kept while the cashier picks a customer (the
  Customer button opens another page): `savePaymentDraft` puts them in the store (`pos.paymentDraft`), and
  `restorePaymentDraft` takes them back once when the page opens again on the same cart (items and quantities; a
  customer's prices may change the amounts), else cash as before. A payment card left blank (a method tapped by mistake:
  no amount, nothing typed) is ignored (`filledPaymentCards`), neither blocking "Process payment" nor sent. While
  "Process payment" is greyed, its tooltip says why (`paymentBlockedReason`: no payment, "x TND still to pay",
  change without cash, or "Method: field is missing", `cardProblem` holding the former rules). On a small till the
  customer's name ends in "…" (≤1024 px). Payment
  methods: a total only when the method holds an amount, shorter rows, the cash limit line only when there is a limit,
  and a "more below" arrow while methods are hidden under the list (a tap scrolls). The method highlighted follows the
  selected card (also after a card is removed). Texts: "Cash limit", "Discount on the whole ticket" (fr "Remise sur
  tout le ticket"), the English labels of the cheque fields (they were French with broken accents), the amount's
  placeholder in the store's decimals (`amountDecimals`).
- **3.1.0, payment page, faster for the cashier**: the details header shows the number needed now, large
  (`balanceDisplay`: "To pay" in red, "Change" in green, or "Paid"); a "Ticket (n)" button (ticket row) opens the recap of
  what is being paid (each line, its paid pieces, the free ones apart, its amount, the ticket total), read only. Each
  line's amounts come from one method, `lineAmountsInclVat`, also used by "Process payment" and "Save as pending" (the
  same lines as before, moved). "Exact amount" (last row of the keypad, `addExactCash`) adds what is left to pay to the
  cash received, on the cash card (created when there is none); banknote buttons (+10, +20, +50) were tried and dropped
  (Zein, 2026-10-13). A new card of any other method than cash and return voucher starts with what is left to pay
  (`prefilled`): the first keypad key replaces it, and on a keyboard it is selected so typing replaces it. The keypad
  is the card terminal's (3 columns, 1 2 3 on top; the keys were already in that order, 4 columns read 1 2 3 4), with
  C (clears the field being typed: the amount, or a cheque's number) and "Exact amount" on its last row.
  "Total after discount" only when a discount lowered the ticket (`hasTicketReduction`). The top bar's remaining amount
  is green at 0 (half a millime of float noise showed 0.000 in red).
- **3.1.0, payment page layout** (decided with Zein, 2026-10-13): the screen follows the sale. Top bar: Back and "Save as
  pending" (the two ways out without paying, away from "Process payment"), the totals on the right. The ticket row (the
  former promo code row): the customer as a chip (a tap opens the customer page; the payments typed are kept), the
  loyalty member, Discount, the split bill (table ticket), the promo code, "Ticket (n)" at the end. Under it, the
  methods, the payment cards (the amount typed in large) and the keypad: a full-height column right of the cards from
  1200 px (keys filling it), under them on a smaller till, with "Process payment" at its end (under the keys on a wide
  till, a tall button right of them on a small one; keys kept ~68 px wide or more). No bottom bar any more. The two
  headers (payment methods, payment details) are the same height, their lines meeting. The keypad looks like a card
  terminal's: white keys with a slight bottom edge on a light panel (they sink when pressed), the special keys tinted
  (delete amber with its icon, C red, "Exact amount" green), "Process payment" the one solid colour. On a wide till the
  balance is the keypad's screen, above its keys ("To pay" / "Change" / "Paid", large), instead of the details header;
  the keys follow the screen's height (64 px at 720, 74 at 768, 90 from ~850) so everything fits the column.
- **3.1.0, pending tickets window** (`ItemSelection.vue`, decided with Zein, 2026-10-13): a compact list, newest first,
  one scroll (the window's), no footer: per ticket its number, time and "x min ago", table or customer (not the
  passenger customer), "n article(s): the first two names", the total including VAT in large, "Continue", and apart
  (gap, separator, outlined) the ✕ that cancels it. A tap opens its lines at prices including VAT, as the till shows
  them (`pendingLineInclVat`, `pendingUnitInclVat`; until 3.0 the lines showed prices excluding VAT). A search over
  number, customer, table, article name or code and total ("160" finds 160.000; `matchesWords` of
  `libs/search-text.js`), with the count. "Delete" is "Cancel ticket" everywhere, since it is: the ticket becomes
  CANCELLED and stays in the history (confirmation: "Keep" / "Cancel ticket"). After "Continue", a chip in the cart
  header ("Pending ticket HS…", the number only on a small till) while it is worked on (`pos.pendingTicketNumber`,
  cleared with `pendingTicketId`; none on a table ticket, which has its own sign). The "Pending tickets" button is
  greyed while none waits (not hidden: the other buttons keep their places); the tickets are per cashier session, so
  the count is never stale.
- **3.1.0, close session window** (`ItemSelection.vue`, decided with Zein, 2026-10-13): pending tickets are said first
  (a banner with "See the pending tickets"; "Close session" greyed meanwhile), not after the counting. Every active
  method is a row with its amount (`fillCloseCountLines`, `closeCountMethods`: the payment page's order), then the row
  without method, labelled "Fond de caisse" (the float: the server counts it as cash, `CashierSessionService`, and does
  not list it on the closing ticket; it was "Espèces", next to "Client Espèce"), apart. The cashier fills what was
  counted (Tab goes down the rows), a filled row is tinted; only the rows with an amount are sent (`countedLines`), at
  least one and none negative to close. The rows are made before the window shows (methods loaded on the button), the
  cursor in the first (`autofocus`). The total counted is in the footer, left of the buttons; notes on one line; 640
  px, fits a 768 px till without scrolling. The amounts the POS expects are never shown (blind count; the server
  compares, the cash difference check). The request is the same (one line per method counted). A first version (a
  cash line and "+ method" buttons) was dropped for this one.
  **EMTOP's "typing in the close session window writes nothing, until it is closed and opened again"**: the cash
  difference popup, closed over the window, could stay in the page as an invisible layer on top (its closing never
  finished: "transitioning", `display: block`, z-index 1041), taking the cashier's clicks; seen with
  `elementFromPoint` on the amount field. The popup is now rendered with `v-if` (removed from the page as soon as it
  closes), so no layer can stay, whatever the transition; tested: 5 runs, the "force close" path (badge cancelled),
  and real typing after "Close" of the popup. The exact trigger stays unproven (intermittent; a background browser tab
  also stops transitions, which muddied the tests).
- **3.1.0, the till's top rows**: the barcode field and the search share the row equally (one row down to 800 px, the
  RFID button between them). Back, the breadcrumb, the search chip and the view toggle stay on one line: long names end
  in "…" (`chip-text`) and, under 1200 px, the toggle shows its icon only (its name as tooltip). The grid view
  (compact or comfort) is remembered by the browser of each till (`localStorage` key `zsretail.pos.compactView`;
  storage blocked: not remembered). **Enter in the search box** adds the item of the whole barcode or code typed (the
  server's `exactId`), else the only item found, then clears the box and keeps the focus there for the next code;
  several items and none exact: nothing. Enter waits for the last keystroke's watcher and asks the server when the
  results on screen answer an older text (`searchAnsweredFor`), so a scanner's fast Enter is never lost. Under 2 characters, the
  X button, Esc or any navigation (which clears the box): the level that was open. More than 30 matches: the first 30
  and "type more words" (`pos.itemSelection.search.more`). One placeholder for every level
  (`searchPlaceholders.catalogue`; the per-level ones are gone). Server side: `backend.md`.
- **2.2.2, step 4**: the till's quantity box is a text box in both modes; with "Allow decimal quantities" off a decimal ("0,2", "0.5") is refused with a message and the box shows the line's quantity again (a number box read "0,2" as 2). General Setup: on a store linked to a head office, `DEFAULT_LOCATION` is labelled "Store code" with a help text (`SetupField` prop `help`). `public/index.html`: `translate="no"` and `<meta name="google" content="notranslate">`.
