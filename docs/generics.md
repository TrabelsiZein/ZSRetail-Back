# Generic API & Service System - Documentation

## Overview
The POS backend uses a powerful generic system for creating CRUD operations quickly. This system eliminates boilerplate code and ensures consistency across all entities.

## Architecture

```
_BaseEntity (Model)
    ↓
_BaseRepository (Data Access)
    ↓
__BaseService (Interface)
    ↓
_BaseService (Implementation)
    ↓
_BaseController (API Endpoints)
```

## Components

### 1. _BaseEntity
Base model class that all entities extend.

**Fields:**
- `id` - Primary key (Long)
- `createdAt` - Timestamp when created
- `updatedAt` - Timestamp when updated
- `createdBy` - Username who created
- `updatedBy` - Username who updated
- `active` - Boolean flag for soft delete

**Example:**
```java
@Entity
public class Product extends _BaseEntity {
    @Column(nullable = false)
    private String name;
    
    private Double price;
    // ... other fields
}
```

### 2. _BaseRepository
Base repository interface extending JpaRepository and JpaSpecificationExecutor.

**Methods:**
- `findAllByOrderByUpdatedAtDesc()` - Get all, newest first
- `findAllByOrderByCreatedAtDesc()` - Get all, newest created first
- `findByActiveTrue()` - Get only active records
- `findByActiveFalse()` - Get only inactive records
- `findByIdAndActiveTrue(id)` - Find by ID if active
- `countByActiveTrue()` - Count active records
- `countByActiveFalse()` - Count inactive records
- `findByCreatedBy(createdBy)` - Find by creator
- `findByUpdatedBy(updatedBy)` - Find by updater
- `findByCreatedAtAfter(date)` - Find created after date
- `findByCreatedAtBefore(date)` - Find created before date

**Example:**
```java
public interface ProductRepository extends _BaseRepository<Product, Long> {
    // Add specific methods if needed
}
```

### 3. __BaseService (Interface)
Service interface defining common operations.

**Methods:**
- `findAll()` - Get all records
- `findById(id)` - Get by ID
- `findByField(fieldName, operation, value)` - Advanced search
- `save(entity)` - Create or update
- `deleteById(id)` - Delete by ID
- `count()` - Count all records

**Example:**
```java
public interface ProductService extends __BaseService<Product, Long> {
    // Add specific methods if needed
}
```

### 4. _BaseService (Implementation)
Abstract service class implementing common business logic.

**Features:**
- Automatic audit trail (created/updated by and timestamps)
- Advanced field-based search with multiple operators
- Transaction management

**Supported Search Operators:**
- `=` - Equals
- `>` - Greater than
- `>=` - Greater than or equal
- `<` - Less than
- `<=` - Less than or equal
- `!=` or `<>` - Not equal
- `LIKE` - Contains (case-sensitive)

**Example:**
```java
@Service
public class ProductService extends _BaseService<Product, Long> {
    @Override
    protected _BaseRepository<Product, Long> getRepository() {
        return productRepository;
    }
    
    // Add specific business logic if needed
}
```

### 5. _BaseController
Base REST controller providing common HTTP endpoints.

**Endpoints:**
```
GET    /entity              - Get all entities
GET    /entity/{id}         - Get by ID
GET    /entity/findByField  - Search by field
POST   /entity              - Create new entity
PUT    /entity/{id}         - Update entity
DELETE /entity/{id}         - Delete entity
GET    /entity/{id}/exists  - Check if exists
GET    /entity/count        - Get count
```

**Example:**
```java
@RestController
@RequestMapping("product")
public class ProductAPI extends _BaseController<Product, Long, ProductService> {
    // All CRUD operations are available!
    
    // Add custom endpoints if needed
    @GetMapping("/available")
    public ResponseEntity<?> getAvailableProducts() {
        // Custom logic
    }
}
```

## Creating a New Entity (Quick Guide)

### Step 1: Create Model
```java
@Entity
@Data
public class Product extends _BaseEntity {
    @Column(nullable = false)
    private String name;
    
    private Double price;
    
    private Integer stock;
}
```

### Step 2: Create Repository
```java
public interface ProductRepository extends _BaseRepository<Product, Long> {
    // Add custom queries if needed
    List<Product> findByNameContainingIgnoreCase(String name);
}
```

### Step 3: Create Service
```java
@Service
public class ProductService extends _BaseService<Product, Long> {
    @Autowired
    private ProductRepository productRepository;
    
    @Override
    protected _BaseRepository<Product, Long> getRepository() {
        return productRepository;
    }
    
    // Add custom business logic if needed
}
```

### Step 4: Create Controller
```java
@RestController
@RequestMapping("product")
public class ProductAPI extends _BaseController<Product, Long, ProductService> {
    // Done! All CRUD operations are available automatically
}
```

**That's it!** Your entity now has:
- ✅ Full CRUD operations
- ✅ Advanced search capabilities
- ✅ Audit trail
- ✅ RESTful API
- ✅ Error handling
- ✅ Logging

## API Usage Examples

### Get All Products
```bash
GET /zsretail/api/product
```

### Get Product by ID
```bash
GET /zsretail/api/product/1
```

### Create Product
```bash
POST /zsretail/api/product
Content-Type: application/json

{
  "name": "Laptop",
  "price": 999.99,
  "stock": 50
}
```

### Update Product
```bash
PUT /zsretail/api/product/1
Content-Type: application/json

{
  "id": 1,
  "name": "Gaming Laptop",
  "price": 1299.99,
  "stock": 30
}
```

### Delete Product
```bash
DELETE /zsretail/api/product/1
```

### Search by Field
```bash
GET /zsretail/api/product/findByField?fieldName=price&operation=>&value=500
```

### Check if Exists
```bash
GET /zsretail/api/product/1/exists
Response: true
```

### Get Count
```bash
GET /zsretail/api/product/count
Response: 42
```

## Repository Query Methods

When you extend `_BaseRepository`, you get access to Spring Data JPA magic methods:

```java
// Automatic query methods based on field names
List<Product> findByName(String name);
List<Product> findByNameContaining(String substring);
List<Product> findByPriceGreaterThan(Double price);
List<Product> findByStockGreaterThan(Integer stock);
Optional<Product> findByIdAndActiveTrue(Long id);
```

## Search Operations

The `findByField` endpoint supports multiple operators:

```bash
# Exact match
GET /entity/findByField?fieldName=name&operation==&value=Product

# Greater than
GET /entity/findByField?fieldName=price&operation=>&value=100

# Less than
GET /entity/findByField?fieldName=stock&operation=<&value=10

# Not equal
GET /entity/findByField?fieldName=name&operation=!=&value=Deleted

# Contains (LIKE)
GET /entity/findByField?fieldName=name&operation=LIKE&value=laptop

# Greater than or equal
GET /entity/findByField?fieldName=price&operation=>=&value=50

# Less than or equal
GET /entity/findByField?fieldName=stock&operation=<=&value=100
```

## Benefits

### 1. Consistency
All entities follow the same patterns and conventions.

### 2. DRY Principle
No code duplication - write once, use everywhere.

### 3. Maintainability
Changes to base classes benefit all entities.

### 4. Speed
Create a complete CRUD API in minutes.

### 5. Safety
Built-in error handling, logging, and validation.

### 6. Flexibility
Easy to extend with custom methods when needed.

## Customization

You can override methods or add custom endpoints:

```java
@RestController
@RequestMapping("product")
public class ProductAPI extends _BaseController<Product, Long, ProductService> {
    
    @Autowired
    private ProductService productService;
    
    // Override default getAll
    @Override
    @GetMapping
    public ResponseEntity<?> getAll() {
        // Custom logic - e.g., only return active products
        return ResponseEntity.ok(productService.findActiveProducts());
    }
    
    // Add custom endpoint
    @GetMapping("/low-stock")
    public ResponseEntity<?> getLowStockProducts() {
        return ResponseEntity.ok(productService.findLowStockProducts());
    }
    
    @GetMapping("/search/{keyword}")
    public ResponseEntity<?> searchProducts(@PathVariable String keyword) {
        return ResponseEntity.ok(productService.searchByKeyword(keyword));
    }
}
```

## Advanced Example: Order Management

```java
// 1. Model
@Entity
public class Order extends _BaseEntity {
    @Column(nullable = false)
    private String orderNumber;
    
    private Double total;
    
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    
    private LocalDateTime orderDate;
}

// 2. Repository
public interface OrderRepository extends _BaseRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);
    List<Order> findByStatus(OrderStatus status);
    List<Order> findByOrderDateBetween(LocalDateTime start, LocalDateTime end);
}

// 3. Service
@Service
public class OrderService extends _BaseService<Order, Long> {
    @Autowired
    private OrderRepository orderRepository;
    
    @Override
    protected _BaseRepository<Order, Long> getRepository() {
        return orderRepository;
    }
    
    public Order createOrder(Order order) throws Exception {
        // Custom logic before saving
        order.setOrderNumber(generateOrderNumber());
        order.setStatus(OrderStatus.PENDING);
        return save(order);
    }
}

// 4. Controller
@RestController
@RequestMapping("order")
public class OrderAPI extends _BaseController<Order, Long, OrderService> {
    
    @PostMapping("/create")
    public ResponseEntity<?> createOrder(@RequestBody Order order) throws Exception {
        Order createdOrder = service.createOrder(order);
        return ResponseEntity.ok(createdOrder);
    }
}
```

## Best Practices

### 1. Always Use @Transactional
```java
@Transactional
public void processOrder(Order order) {
    // Multiple database operations
}
```

### 2. Override Only When Necessary
Use inheritance wisely - don't override default methods unless you need custom behavior.

### 3. Add Domain-Specific Methods
Keep generic methods in base classes, add domain logic in concrete classes.

### 4. Use DTOs for Complex Responses
```java
public class ProductDTO {
    private Long id;
    private String name;
    private Double price;
    private Long categoryId;
    private String categoryName;
}
```

### 5. Error Handling
```java
try {
    Product product = service.findById(id)
        .orElseThrow(() -> new ProductNotFoundException("Product not found"));
} catch (Exception e) {
    return ResponseEntity.status(404).body(e.getMessage());
}
```

## Summary

The generic system provides:
- ✅ Zero boilerplate code
- ✅ Consistent API structure
- ✅ Built-in audit trail
- ✅ Advanced search capabilities
- ✅ Error handling
- ✅ Complete CRUD operations
- ✅ Easy to extend and customize

**Start building your POS entities now!** 🚀

