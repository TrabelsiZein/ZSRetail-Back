# Overview

- Point-of-sale suite with Vue.js frontend (`ZSRetail-Front`) and Spring Boot backend (`ZSRetail-Back`).
- Key domains implemented: sales receipts with CODE128 barcodes, returns management, cashier sessions, locations, general setup parameters, item families & subfamilies, dynamic pricing (SalesPrice/SalesDiscount), promotion engine (item-level + cart-level with promo codes), loyalty program, head office and stores (a franchise network runs on this model; the franchise profiles were removed at head office plan step 9), on-prem licensing.
- Role-based navigation (ADMIN, RESPONSIBLE, POS_USER) enforced across router, navigation, and backend APIs.
