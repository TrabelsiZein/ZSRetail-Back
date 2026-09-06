# Overview

- Point-of-sale suite with Vue.js frontend (`POS_Front`) and Spring Boot backend (`POS_Back`).
- Key domains implemented: sales receipts with CODE128 barcodes, returns management, cashier sessions, locations, general setup parameters, item families & subfamilies, dynamic pricing (SalesPrice/SalesDiscount), promotion engine (item-level + cart-level with promo codes), loyalty program, franchise mode, on-prem licensing.
- Role-based navigation (ADMIN, RESPONSIBLE, POS_USER) enforced across router, navigation, and backend APIs.
