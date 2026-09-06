# POS UI Design System

- **Design Philosophy**: Both `Payment.vue` and `ItemSelection.vue` follow a consistent touch-screen POS design pattern:
  - Full-screen layout: `position: fixed` with `100vh` height, `overflow: hidden` (no scrollbars visible)
  - Panel separation: 2px solid borders (`#e0e0e0`) between panels instead of gaps
  - Consistent spacing: Small padding (10px-15px) throughout
  - Hidden scrollbars: Scrollbars hidden via CSS but touch scrolling remains functional
  - No rounded corners on panels: `border-radius: 0` for seamless panel connections
  - No shadows on panels: Minimal design with only subtle shadows on outer container

### Payment Page Structure
- **5-Panel Layout**:
  1. Top Panel: Summary with customer info, totals, discount, paid, remaining
  2. Left Panel: Payment methods list (8 payment classes) with totals per method
  3. Right-Top Panel: Payment cards (scrollable) - displays payment details per selected method
  4. Right-Bottom Panel: Numeric keyboard (0-9, decimal, backspace)
  5. Bottom Panel: Action buttons (Complete Payment, Save as Pending, Discount, Customer)
- **Payment Methods**: Ordered by `displayOrder` field (1-8: CLIENT_ESPECES, CLIENT_TPE, TICKET_RESTAURANT, CHEQUE_CADEAU, CLIENT_CHEQUE, CLIENT_TRAITE, DEPOT_BANQUE, RETURN_VOUCHER)
- **Payment Cards Logic**: CLIENT_ESPECES has only one card (updates existing), other methods create new cards each time
- **Dynamic Required Fields**: Fields shown based on `requireTitleNumber`, `requireDueDate`, `requireDrawerName`, `requireIssuingBank` flags from backend PaymentMethod entity
- **No optional fields**: Reference and Notes fields removed from payment cards

### ItemSelection Page Structure
- **3-Panel Layout**:
  1. Left Panel: Items section with search, families grid, and actions panel at bottom
  2. Right Panel: Cart section (shopping cart with items and total)
  3. Bottom Panel: Action buttons (Payment, Customers, Return Products, Pending Tickets, Close Session)
- **Separators**: 2px solid borders between items panel and cart panel, and between actions panel and cart panel
- **Spacing**: Left padding (10px) on screen, panels use 10px internal padding
- **Action buttons**: Horizontal layout with 8px gap between buttons
