# Customer Management

**Status**: ✅ Complete

**POS Customer List (`CustomerList.vue`):**
- **Server-Side Pagination**: 
  - Endpoint: `GET /customer/paginated` (page, size, searchTerm, selectedCustomerId)
  - Only returns active customers (`active = true`)
  - Default page size: 20 customers
  - Debounced search (500ms delay)
- **Visual Selection Indicator**:
  - Removed "Selected" badge text
  - Green highlighted row for selected customer (background: `#e8f5e9`, left border: 4px green)
  - Green checkmark icon in actions column
  - Info alert when selected customer not in current page: "Selected customer: [name] ([code])"
- **Navigation**: Always returns to `ItemSelection` page when customer is changed (ensures cart recalculation)
- **Removed**: Status column (only active customers shown)

**Admin Customer Management (`CustomerManagement.vue`):**
- **Server-Side Pagination**:
  - Endpoint: `GET /customer/admin/paginated` (page, size, searchTerm, statusFilter)
  - Supports status filter: all, active, inactive
  - Default page size: 20 customers
  - Debounced search (500ms delay)
- **Default Customer Protection**:
  - Frontend: Disables "Set as Default" button for inactive customers
  - Backend: Validates customer is active before setting as default
  - Error message: "Cannot set inactive customer as default. Please activate the customer first."
- **Status Column**: Shows active/inactive status with color-coded badges

**Backend Changes:**
- **CustomerService**: 
  - `findActiveCustomersPaginated()`: For POS (only active customers)
  - `findCustomersPaginated()`: For admin (with status filter)
- **CustomerAPI**:
  - `/customer/paginated`: POS endpoint with selected customer support
  - `/customer/admin/paginated`: Admin endpoint with status filter
  - `PUT /customer/{id}/set-default`: Validates customer is active before setting as default

**Translation Keys Added:**
- `pos.customerManagement.selectedCustomer`: "Selected customer" (en/fr/ar)
- `pos.customerManagement.cannotSetInactiveAsDefault`: Error message for inactive customer (en/fr/ar)
