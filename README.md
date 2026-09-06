# ZS Retail Backend Application

## Overview
Complete Point of Sale (POS) backend application with role-based access control and full POS functionality.

## Features
- **Simple Role System**: Three roles (Admin, Responsible, POS User)
- **JWT Authentication**: Secure token-based authentication
- **User Management**: Create, update, and manage users
- **Generic CRUD System**: Powerful generic API for quick entity creation
- **Complete POS System**: 9 entities with 72 REST endpoints
- **RESTful API**: Well-documented endpoints
- **Spring Boot**: Modern Java backend framework

## POS Entities Implemented
✅ **Customer** - Customer management  
✅ **Item** - Product catalog (products, services, packages)  
✅ **PaymentMethod** - Payment methods (cash, cards, mobile, etc.)  
✅ **SalesHeader & SalesLine** - Sales transactions with session tracking  
✅ **PaymentHeader** - Multiple payment methods per ticket (cash + cheque, etc.)  
✅ **CashierSession** - Cashier shift management (open/close sessions)  
✅ **CashCountDetail** - Detailed cash counting at session closure

## Technology Stack
- Java 11
- Spring Boot 2.3.9
- Spring Security with JWT
- JPA/Hibernate
- SQL Server
- Maven

## Quick Start

### Prerequisites
- Java 11
- Maven 3.6+
- SQL Server Database

### Configuration
Update database configuration in `src/main/resources/application-dev.properties`:
```properties
spring.datasource.url=jdbc:sqlserver://localhost;databaseName=pos_db
spring.datasource.username=your_username
spring.datasource.password=your_password
```

### Running the Application
```bash
mvn spring-boot:run
```

### Default Credentials

**System Admin:**
- **Username:** admin
- **Password:** Admin@123

**Responsible Manager:**
- **Username:** responsible
- **Password:** Resp@123

**Cashier / POS User:**
- **Username:** cashier
- **Password:** Cashier@123

## API Documentation
Once running, access Swagger UI at:
```
http://localhost:444/zsretail/api/swagger-ui.html
```

## Role System

### ADMIN
Full system access - can manage users and all POS operations.

### RESPONSIBLE
Management access - can view and manage inventory, products, reports, but cannot manage users.

### POS_USER
Basic POS operations - cashier access to sell products and create tickets.

## API Endpoints

### Authentication
```bash
POST /zsretail/api/login
Headers: username=admin, password=Admin@123
```

### User Management
```bash
GET  /zsretail/api/user/all                  # List all users
GET  /zsretail/api/user/detail/{id}          # Get user details
POST /zsretail/api/user/create               # Create new user
PUT  /zsretail/api/user/{id}/role            # Update user role
PUT  /zsretail/api/user/{id}/toggle-status   # Enable/disable user
DELETE /zsretail/api/user/{id}               # Delete user
```

## Example: Create a Cashier
```bash
POST /zsretail/api/user/create
Content-Type: application/json
{
  "username": "cashier01",
  "password": "Cashier123!",
  "fullName": "Jane Cashier",
  "email": "jane@store.com",
  "role": "POS_USER"
}
```

### POS Entities (All entities follow same pattern)

Each POS entity has 8 REST endpoints automatically:
```
GET    /{entity}                  # List all
GET    /{entity}/{id}             # Get by ID
GET    /{entity}/findByField      # Search with operators (=, >, >=, <, <=, !=, LIKE)
POST   /{entity}                  # Create
PUT    /{entity}/{id}             # Update
DELETE /{entity}/{id}             # Delete
GET    /{entity}/{id}/exists      # Check exists
GET    /{entity}/count            # Count total
```

**Examples:**
- `/zsretail/api/customer` - Customer management
- `/zsretail/api/item` - Product catalog
- `/zsretail/api/payment-method` - Payment methods
- `/zsretail/api/sales-header` - Sales transactions
- `/zsretail/api/payment-header` - Payment processing
- `/zsretail/api/cashier-session` - Cashier shift management
- `/zsretail/api/cash-count-detail` - Cash counting details

See [ENTITIES_IMPLEMENTATION.md](ENTITIES_IMPLEMENTATION.md) for complete details.

## Login Response
```json
{
  "role": "ADMIN",
  "fullName": "System Administrator",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "status": 200
}
```

## Documentation

- [SIMPLE_DESIGN.md](SIMPLE_DESIGN.md) - Simple role-based design details
- [GENERICS_DOCUMENTATION.md](GENERICS_DOCUMENTATION.md) - Complete guide to the generic CRUD system
- [ENHANCEMENTS_SUMMARY.md](ENHANCEMENTS_SUMMARY.md) - What was improved
- [FINAL_STATUS.md](FINAL_STATUS.md) - Project completion status
- **[ENTITIES_IMPLEMENTATION.md](ENTITIES_IMPLEMENTATION.md)** - Complete POS entities documentation
- **[SESSION_MANAGEMENT.md](SESSION_MANAGEMENT.md)** - Cashier session workflow guide
- **[DATA_INITIALIZATION.md](DATA_INITIALIZATION.md)** - Initial test data for Hammai Group Tunisia
- **[RELATIONSHIPS_DESIGN.md](RELATIONSHIPS_DESIGN.md)** - Entity relationships, fetch types, and design decisions
- **[FRONTEND_INTEGRATION.md](FRONTEND_INTEGRATION.md)** - Frontend integration guide and API configuration

## Generic CRUD System

This project includes a powerful generic system for creating CRUD APIs quickly:

**Create a complete CRUD API in 5 minutes!**

```java
// 1. Model
@Entity
public class Product extends _BaseEntity {
    private String name;
    private Double price;
}

// 2. Repository
public interface ProductRepository extends _BaseRepository<Product, Long> {}

// 3. Service
@Service
public class ProductService extends _BaseService<Product, Long> {
    @Autowired private ProductRepository repository;
    @Override protected _BaseRepository<Product, Long> getRepository() {
        return repository;
    }
}

// 4. Controller
@RestController
@RequestMapping("product")
public class ProductAPI extends _BaseController<Product, Long, ProductService> {}
```

That's it! You now have:
- ✅ 8 REST endpoints
- ✅ Advanced search
- ✅ Audit trail
- ✅ Soft delete
- ✅ Error handling

## License
Copyright (c) DigiThink
