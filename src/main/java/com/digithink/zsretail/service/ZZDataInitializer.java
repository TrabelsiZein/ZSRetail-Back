package com.digithink.zsretail.service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.erp.service.ErpSyncCheckpointService;
import com.digithink.zsretail.model.AppReleaseNote;
import com.digithink.zsretail.model.AppRole;
import com.digithink.zsretail.model.AppVersion;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.ConfigType;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.model.enumeration.Role;
import com.digithink.zsretail.repository.AppReleaseNoteRepository;
import com.digithink.zsretail.repository.AppRoleRepository;
import com.digithink.zsretail.repository.AppVersionRepository;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository.LocationRepository;
import com.digithink.zsretail.repository.PaymentMethodRepository;
import com.digithink.zsretail.repository.UserAccountRepository;
import com.digithink.zsretail.repository.VendorRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Component
public class ZZDataInitializer {

	@Autowired
	private UserAccountRepository userRepository;
	@Autowired
	private AppRoleRepository appRoleRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;
	@Autowired
	private PaymentMethodRepository paymentMethodRepository;
	@Autowired
	private CustomerRepository customerRepository;
	@Autowired
	private ItemRepository itemRepository;
	@Autowired
	private ItemFamilyRepository itemFamilyRepository;
	@Autowired
	private ItemSubFamilyRepository itemSubFamilyRepository;
	@Autowired
	private ItemBarcodeRepository itemBarcodeRepository;
	@Autowired
	private LocationRepository locationRepository;
	@Autowired
	private GeneralSetupRepository generalSetupRepository;
	@Autowired
	private VendorRepository vendorRepository;
	@Autowired
	private ErpSyncJobRepository erpSyncJobRepository;
	@Autowired
	private ApplicationModeService applicationModeService;
	@Autowired
	private CompanyInformationService companyInformationService;
	@Autowired
	private AppVersionRepository appVersionRepository;
	@Autowired
	private AppReleaseNoteRepository appReleaseNoteRepository;

	@Value("${app.version:unknown}")
	private String appVersion;

	@PostConstruct
	public void init() {
		// Always run — idempotent, seeds default roles if they don't exist yet
		ensureDefaultRoles();

		if (userRepository.count() == 0) {
			initUsers();
		}

		if (paymentMethodRepository.count() == 0) {
			paymentMethodRepository.deleteAll();
			initPaymentMethods();
		}

		// Always run on startup — idempotent (each key guarded by findByCode check)
		ensureAllGeneralSetupConfigs();

		// Standalone first-run: auto-create passenger customer and wire its code in
		// config
		if (applicationModeService.isStandalone() && customerRepository.count() == 0) {
			ensurePassengerCustomer();
		}

		// ERP-related configs and sync jobs only when not in standalone mode
		if (!applicationModeService.isStandalone()) {
			ensureErpSyncCheckpointConfigs();
			if (erpSyncJobRepository.count() == 0) {
				initErpSyncJobs();
			}
		}

		// Franchise client: ensure vendor seed exists (config keys are in
		// ensureAllGeneralSetupConfigs)
		if (applicationModeService.isFranchiseClient()) {
			ensureFranchiseClientSetup();
		}

		// Ensure tax stamp item exists (idempotent)
		ensureTaxStampItem();

		// Ensure singleton company information record exists (idempotent)
		companyInformationService.ensureExists();

		// Ensure APP_VERSION row exists (fresh install only — guard skips when empty)
		ensureAppVersion();
	}

	/**
	 * Ensures the three built-in AppRoles exist and migrates existing users. Runs
	 * on every startup — idempotent.
	 */
	private void ensureDefaultRoles() {
		AppRole adminRole = ensureRole("ADMIN", "Administrateur", false, ADMIN_PERMISSIONS);
		AppRole responsibleRole = ensureRole("RESPONSIBLE", "Responsable", false, RESPONSIBLE_PERMISSIONS);
		ensureRole("POS_USER", "Caissier", true, POS_PERMISSIONS);

		// Migrate existing users that have no appRole yet
		userRepository.findAll().forEach(user -> {
			if (user.getAppRole() == null && user.getRole() != null) {
				switch (user.getRole()) {
				case ADMIN:
					user.setAppRole(adminRole);
					break;
				case RESPONSIBLE:
					user.setAppRole(responsibleRole);
					break;
				case POS_USER:
					appRoleRepository.findByName("POS_USER").ifPresent(user::setAppRole);
					break;
				}
				user.setUpdatedBy("System");
				userRepository.save(user);
			}
		});
	}

	private AppRole ensureRole(String name, String label, boolean isPosRole, java.util.Set<String> permissions) {
		return appRoleRepository.findByName(name).orElseGet(() -> {
			AppRole role = new AppRole(name, label, isPosRole);
			role.setPermissions(permissions);
			role.setCreatedBy("System");
			role.setUpdatedBy("System");
			return appRoleRepository.save(role);
		});
	}

	// ── Default permission sets ────────────────────────────────────────────────

	private static final Set<String> ADMIN_PERMISSIONS = new HashSet<>(Arrays.asList("read:home",
			"read:admin-users", "write:admin-users", "delete:admin-users", "read:admin-sessions",
			"read:admin-sessions-history", "read:tickets-history", "read:admin-item-barcodes",
			"read:admin-item-management", "read:admin-print-product-labels", "read:admin-item-families",
			"read:admin-item-subfamilies", "read:admin-customers", "write:admin-customers", "delete:admin-customers",
			"read:admin-vendors", "write:admin-vendors", "read:admin-locations", "read:admin-sales-prices",
			"read:admin-sales-discounts", "read:admin-promotions", "read:admin-payment-methods",
			"write:admin-payment-methods", "read:admin-general-setup", "read:admin-sales", "read:admin-returns",
			"read:admin-statistics", "read:admin-purchases", "read:purchase-history", "read:purchase-new",
			"write:purchase-new", "read:vendor-balance", "read:admin-purchase-invoices", "read:admin-warranty",
			"write:admin-warranty", "read:admin-erp-jobs", "read:admin-erp-communications", "read:admin-invoices",
			"read:admin-badge-scan-history", "read:admin-loyalty-members", "write:admin-loyalty-members",
			"read:admin-loyalty-programs", "write:admin-loyalty-programs", "read:admin-loyalty-transactions",
			"read:admin-loyalty-member-functions", "write:admin-loyalty-member-functions", "read:admin-data-import",
			"write:admin-data-import", "read:admin-report-sales", "read:admin-report-purchases",
			"read:admin-report-stock", "read:admin-report-stock-movements", "read:admin-report-loyalty",
			"read:admin-report-sessions", "read:admin-report-promotions", "read:admin-franchise",
			"read:admin-franchise-sales-tracking", "read:admin-franchise-sync-dashboard",
			"read:admin-company-information", "write:admin-company-information", "read:admin-roles",
			"write:admin-roles", "delete:admin-roles", "read:change-payment-method", "read:view-session-amounts",
			"read:verify-session", "read:prepare-invoice"));

	private static final java.util.Set<String> RESPONSIBLE_PERMISSIONS = new java.util.HashSet<>(
			java.util.Arrays.asList("read:home", "read:responsible-sessions", "write:responsible-sessions",
					"read:admin-sessions-history", "read:tickets-history", "read:admin-item-barcodes",
					"read:admin-item-management", "read:admin-item-families", "read:admin-item-subfamilies",
					"read:admin-print-product-labels", "read:admin-sales-prices", "read:admin-sales-discounts",
					"read:admin-promotions", "read:admin-customers", "write:admin-customers", "delete:admin-customers",
					"read:admin-vendors", "write:admin-vendors", "read:admin-sales", "read:admin-returns",
					"read:admin-statistics", "read:admin-purchases", "read:purchase-history", "read:purchase-new",
					"write:purchase-new", "read:vendor-balance", "read:admin-purchase-invoices", "read:admin-warranty",
					"write:admin-warranty", "read:admin-badge-scan-history", "read:admin-loyalty-members",
					"write:admin-loyalty-members", "read:admin-loyalty-programs", "write:admin-loyalty-programs",
					"read:admin-loyalty-transactions", "read:admin-loyalty-member-functions",
					"write:admin-loyalty-member-functions", "read:admin-report-sales", "read:admin-report-loyalty",
					"read:admin-report-sessions", "read:admin-report-promotions", "read:admin-franchise",
					"read:admin-franchise-sales-tracking", "read:admin-franchise-sync-dashboard",
					"read:verify-session"));

	private static final java.util.Set<String> POS_PERMISSIONS = new java.util.HashSet<>(
			java.util.Arrays.asList("read:cashier-interface"));

	/**
	 * Create initial users: Admin, Responsible, POS User
	 */
	private void initUsers() {
		AppRole adminRole = appRoleRepository.findByName("ADMIN").orElse(null);
		AppRole responsibleRole = appRoleRepository.findByName("RESPONSIBLE").orElse(null);
		AppRole posRole = appRoleRepository.findByName("POS_USER").orElse(null);

		// System Administrator
		UserAccount admin = new UserAccount();
		admin.setUsername("admin");
		admin.setFullName("System Administrator");
		admin.setPassword(passwordEncoder.encode("P@ssw0rd"));
		admin.setEmail("admin@zsretail.tn");
		admin.setActive(true);
		admin.setRole(Role.ADMIN);
		admin.setAppRole(adminRole);
		admin.setCreatedBy("System");
		admin.setUpdatedBy("System");
		userRepository.save(admin);

		// Responsible
		UserAccount responsible = new UserAccount();
		responsible.setUsername("responsible");
		responsible.setFullName("Responsible Manager");
		responsible.setPassword(passwordEncoder.encode("123.0"));
		responsible.setEmail("responsible@zsretail.tn");
		responsible.setActive(true);
		responsible.setRole(Role.RESPONSIBLE);
		responsible.setAppRole(responsibleRole);
		responsible.setBadgeCode("RESP-BADGE-001");
		responsible.setBadgePermissions("CONSULT_CUSTOMER_LIST,MAKE_RETURN,APPLY_LINE_DISCOUNT,APPLY_TOTAL_DISCOUNT");
		responsible.setBadgeExpirationDate(LocalDateTime.now().plusYears(10));
		responsible.setBadgeRevoked(false);
		responsible.setCreatedBy("System");
		responsible.setUpdatedBy("System");
		userRepository.save(responsible);

		// POS User / Cashier
		UserAccount posUser = new UserAccount();
		posUser.setUsername("cashier");
		posUser.setFullName("Cashier User");
		posUser.setPassword(passwordEncoder.encode("cashier"));
		posUser.setEmail("123");
		posUser.setActive(true);
		posUser.setRole(Role.POS_USER);
		posUser.setAppRole(posRole);
		posUser.setCreatedBy("System");
		posUser.setUpdatedBy("System");
		userRepository.save(posUser);
	}

	/**
	 * Create initial payment methods for Tunisia
	 */
	private void initPaymentMethods() {
		// Client Espèce - Order 1
		PaymentMethod clientCash = new PaymentMethod();
		clientCash.setCode("CLIENT_ESPECES");
		clientCash.setName("Client Espèce");
		clientCash.setType(PaymentMethodType.CLIENT_ESPECES);
		clientCash.setDescription("Paiement en espèces du client");
		clientCash.setActive(true);
		clientCash.setDisplayOrder(1);
		clientCash.setCreatedBy("System");
		clientCash.setUpdatedBy("System");
		paymentMethodRepository.save(clientCash);

		// Client TPE (Terminal de paiement électronique) - Order 2
		PaymentMethod clientTpe = new PaymentMethod();
		clientTpe.setCode("CLIENT_TPE");
		clientTpe.setName("Client TPE");
		clientTpe.setType(PaymentMethodType.CLIENT_TPE);
		clientTpe.setDescription("Paiement via terminal électronique (TPE)");
		clientTpe.setActive(true);
		clientTpe.setDisplayOrder(2);
		clientTpe.setCreatedBy("System");
		clientTpe.setUpdatedBy("System");
		paymentMethodRepository.save(clientTpe);

		// Ticket Restaurant - Order 3
		PaymentMethod mealTicket = new PaymentMethod();
		mealTicket.setCode("TICKET_RESTAURANT");
		mealTicket.setName("Ticket Restaurant");
		mealTicket.setType(PaymentMethodType.TICKET_RESTAURANT);
		mealTicket.setDescription("Paiement via ticket restaurant");
		mealTicket.setRequireTitleNumber(true);
		mealTicket.setActive(true);
		mealTicket.setDisplayOrder(3);
		mealTicket.setCreatedBy("System");
		mealTicket.setUpdatedBy("System");
		paymentMethodRepository.save(mealTicket);

		// Chèque Cadeau - Order 4
		PaymentMethod giftCheck = new PaymentMethod();
		giftCheck.setCode("CHEQUE_CADEAU");
		giftCheck.setName("Chèque Cadeau");
		giftCheck.setType(PaymentMethodType.CHEQUE_CADEAU);
		giftCheck.setDescription("Paiement par chèque cadeau");
		giftCheck.setRequireTitleNumber(true);
		giftCheck.setActive(true);
		giftCheck.setDisplayOrder(4);
		giftCheck.setCreatedBy("System");
		giftCheck.setUpdatedBy("System");
		paymentMethodRepository.save(giftCheck);

		// Client Chèque - Order 5
		PaymentMethod clientCheque = new PaymentMethod();
		clientCheque.setCode("CLIENT_CHEQUE");
		clientCheque.setName("Client Chèque");
		clientCheque.setType(PaymentMethodType.CLIENT_CHEQUE);
		clientCheque.setDescription("Paiement par chèque client");
		clientCheque.setRequireTitleNumber(true);
		clientCheque.setRequireDrawerName(true);
		clientCheque.setRequireIssuingBank(true);
		clientCheque.setActive(true);
		clientCheque.setDisplayOrder(5);
		clientCheque.setCreatedBy("System");
		clientCheque.setUpdatedBy("System");
		paymentMethodRepository.save(clientCheque);

		// Client Traite - Order 6
		PaymentMethod clientTraite = new PaymentMethod();
		clientTraite.setCode("CLIENT_TRAITE");
		clientTraite.setName("Client Traite");
		clientTraite.setType(PaymentMethodType.CLIENT_TRAITE);
		clientTraite.setDescription("Paiement par traite client");
		clientTraite.setRequireTitleNumber(true);
		clientTraite.setRequireDueDate(true);
		clientTraite.setRequireDrawerName(true);
		clientTraite.setRequireIssuingBank(true);
		clientTraite.setActive(true);
		clientTraite.setDisplayOrder(6);
		clientTraite.setCreatedBy("System");
		clientTraite.setUpdatedBy("System");
		paymentMethodRepository.save(clientTraite);

		// Dépôt en banque - Order 7
		PaymentMethod bankDeposit = new PaymentMethod();
		bankDeposit.setCode("DEPOT_BANQUE");
		bankDeposit.setName("Dépôt en banque");
		bankDeposit.setType(PaymentMethodType.DEPOT_BANQUE);
		bankDeposit.setDescription("Dépôt des fonds en banque");
		bankDeposit.setActive(true);
		bankDeposit.setDisplayOrder(7);
		bankDeposit.setCreatedBy("System");
		bankDeposit.setUpdatedBy("System");
		paymentMethodRepository.save(bankDeposit);

		// Return Voucher - Order 8
		PaymentMethod returnVoucher = new PaymentMethod();
		returnVoucher.setCode("RETURN_VOUCHER");
		returnVoucher.setName("Bon de Retour");
		returnVoucher.setType(PaymentMethodType.RETURN_VOUCHER);
		returnVoucher.setDescription("Bon de retour émis pour un retour de produit");
		returnVoucher.setActive(true);
		returnVoucher.setDisplayOrder(8);
		returnVoucher.setCreatedBy("System");
		returnVoucher.setUpdatedBy("System");
		paymentMethodRepository.save(returnVoucher);

		// Virement Bancaire - Order 9
		PaymentMethod virementBancaire = new PaymentMethod();
		virementBancaire.setCode("VIREMENT_BANCAIRE");
		virementBancaire.setName("Virement Bancaire");
		virementBancaire.setType(PaymentMethodType.VIREMENT_BANCAIRE);
		virementBancaire.setDescription("Paiement par virement bancaire");
		virementBancaire.setActive(true);
		virementBancaire.setDisplayOrder(9);
		virementBancaire.setCreatedBy("System");
		virementBancaire.setUpdatedBy("System");
		paymentMethodRepository.save(virementBancaire);
	}

	/**
	 * Create initial customers for Hammai Group Tunisia
	 */
//	private void initDefaultCustomer() {
//		// Passenger customer (for POS tickets when no customer selected)
//		Customer passengerCustomer = new Customer();
//		passengerCustomer.setCustomerCode("PASSENGER");
//		passengerCustomer.setName("Passenger Customer");
//		passengerCustomer.setEmail("passenger@pos.local");
//		passengerCustomer.setPhone("0000000000"); // Required field, use placeholder
//		passengerCustomer.setAddress("");
//		passengerCustomer.setCity("");
//		passengerCustomer.setCountry("");
//		passengerCustomer.setActive(true);
//		passengerCustomer.setCreatedBy("System");
//		passengerCustomer.setUpdatedBy("System");
//		customerRepository.save(passengerCustomer);
//	}
//
//	/**
//	 * Create initial item families
//	 */
//	private void initItemFamilies() {
//		ItemFamily electronics = new ItemFamily();
//		electronics.setCode("FAM_ELECTRONICS");
//		electronics.setName("Électronique");
//		electronics.setDescription("Produits électroniques et accessoires");
//		electronics.setDisplayOrder(1);
//		electronics.setCreatedBy("System");
//		electronics.setUpdatedBy("System");
//		itemFamilyRepository.save(electronics);
//
//		ItemFamily services = new ItemFamily();
//		services.setCode("FAM_SERVICES");
//		services.setName("Services");
//		services.setDescription("Prestations de service");
//		services.setDisplayOrder(2);
//		services.setCreatedBy("System");
//		services.setUpdatedBy("System");
//		itemFamilyRepository.save(services);
//
//		ItemFamily promotions = new ItemFamily();
//		promotions.setCode("FAM_PROMOTIONS");
//		promotions.setName("Promotions");
//		promotions.setDescription("Offres promotionnelles et packs");
//		promotions.setDisplayOrder(3);
//		promotions.setCreatedBy("System");
//		promotions.setUpdatedBy("System");
//		itemFamilyRepository.save(promotions);
//	}
//
//	/**
//	 * Create initial item sub-families
//	 */
//	private void initItemSubFamilies() {
//		ItemFamily electronics = itemFamilyRepository.findByCode("FAM_ELECTRONICS").orElse(null);
//		ItemFamily services = itemFamilyRepository.findByCode("FAM_SERVICES").orElse(null);
//		ItemFamily promotions = itemFamilyRepository.findByCode("FAM_PROMOTIONS").orElse(null);
//
//		if (electronics != null) {
//			ItemSubFamily smartphones = new ItemSubFamily();
//			smartphones.setCode("SUB_ELECTRONICS_MOBILE");
//			smartphones.setName("Smartphones");
//			smartphones.setDescription("Téléphones intelligents et mobiles");
//			smartphones.setDisplayOrder(1);
//			smartphones.setItemFamily(electronics);
//			smartphones.setCreatedBy("System");
//			smartphones.setUpdatedBy("System");
//			itemSubFamilyRepository.save(smartphones);
//
//			ItemSubFamily accessories = new ItemSubFamily();
//			accessories.setCode("SUB_ELECTRONICS_ACCESSORIES");
//			accessories.setName("Accessoires");
//			accessories.setDescription("Accessoires électroniques divers");
//			accessories.setDisplayOrder(2);
//			accessories.setItemFamily(electronics);
//			accessories.setCreatedBy("System");
//			accessories.setUpdatedBy("System");
//			itemSubFamilyRepository.save(accessories);
//		}
//
//		if (services != null) {
//			ItemSubFamily installations = new ItemSubFamily();
//			installations.setCode("SUB_SERVICES_INSTALL");
//			installations.setName("Installation");
//			installations.setDescription("Services d'installation et configuration");
//			installations.setDisplayOrder(1);
//			installations.setItemFamily(services);
//			installations.setCreatedBy("System");
//			installations.setUpdatedBy("System");
//			itemSubFamilyRepository.save(installations);
//		}
//
//		if (promotions != null) {
//			ItemSubFamily bundles = new ItemSubFamily();
//			bundles.setCode("SUB_PROMOTIONS_BUNDLES");
//			bundles.setName("Packs Promotionnels");
//			bundles.setDescription("Packs combinant plusieurs produits");
//			bundles.setDisplayOrder(1);
//			bundles.setItemFamily(promotions);
//			bundles.setCreatedBy("System");
//			bundles.setUpdatedBy("System");
//			itemSubFamilyRepository.save(bundles);
//		}
//	}
//
//	/**
//	 * Create initial items/products for Hammai Group Tunisia
//	 */
//	private void initItems() {
//		ItemFamily electronics = itemFamilyRepository.findByCode("FAM_ELECTRONICS").orElse(null);
//		ItemFamily servicesFamily = itemFamilyRepository.findByCode("FAM_SERVICES").orElse(null);
//		ItemFamily promotionsFamily = itemFamilyRepository.findByCode("FAM_PROMOTIONS").orElse(null);
//
//		ItemSubFamily smartphones = itemSubFamilyRepository.findByCode("SUB_ELECTRONICS_MOBILE").orElse(null);
//		ItemSubFamily accessories = itemSubFamilyRepository.findByCode("SUB_ELECTRONICS_ACCESSORIES").orElse(null);
//		ItemSubFamily installations = itemSubFamilyRepository.findByCode("SUB_SERVICES_INSTALL").orElse(null);
//		ItemSubFamily bundles = itemSubFamilyRepository.findByCode("SUB_PROMOTIONS_BUNDLES").orElse(null);
//
//		// Product 1
//		Item item1 = new Item();
//		item1.setItemCode("PROD001");
//		item1.setName("Produit Premium");
//		item1.setDescription("Produit de haute qualité premium");
//		item1.setType(ItemType.PRODUCT);
//		item1.setUnitPrice(250.00);
//		item1.setCostPrice(180.00);
//		item1.setStockQuantity(150);
//		item1.setMinStockLevel(20);
//		item1.setBarcode("1234567890123");
//		item1.setTaxable(true);
//		item1.setTaxRate(0.19); // 19% VAT in Tunisia
//		item1.setUnitOfMeasure("PIECE");
//		item1.setCategory("Électronique");
//		item1.setBrand("Hammai Brand");
//		item1.setItemFamily(electronics);
//		item1.setItemSubFamily(smartphones);
//		item1.setActive(true);
//		item1.setCreatedBy("System");
//		item1.setUpdatedBy("System");
//		itemRepository.save(item1);
//
//		// Product 2
//		Item item2 = new Item();
//		item2.setItemCode("PROD002");
//		item2.setName("Produit Standard");
//		item2.setDescription("Produit standard de qualité");
//		item2.setType(ItemType.PRODUCT);
//		item2.setUnitPrice(150.00);
//		item2.setCostPrice(100.00);
//		item2.setStockQuantity(300);
//		item2.setMinStockLevel(50);
//		item2.setBarcode("1234567890124");
//		item2.setTaxable(true);
//		item2.setTaxRate(0.19);
//		item2.setUnitOfMeasure("PIECE");
//		item2.setCategory("Électronique");
//		item2.setBrand("Hammai Brand");
//		item2.setItemFamily(electronics);
//		item2.setItemSubFamily(accessories);
//		item2.setActive(true);
//		item2.setCreatedBy("System");
//		item2.setUpdatedBy("System");
//		itemRepository.save(item2);
//
//		// Product 3
//		Item item3 = new Item();
//		item3.setItemCode("PROD003");
//		item3.setName("Produit Économique");
//		item3.setDescription("Produit économique pour tous");
//		item3.setType(ItemType.PRODUCT);
//		item3.setUnitPrice(75.00);
//		item3.setCostPrice(50.00);
//		item3.setStockQuantity(500);
//		item3.setMinStockLevel(100);
//		item3.setBarcode("1234567890125");
//		item3.setTaxable(true);
//		item3.setTaxRate(0.19);
//		item3.setUnitOfMeasure("PIECE");
//		item3.setCategory("Électronique");
//		item3.setBrand("Hammai Brand");
//		item3.setItemFamily(electronics);
//		item3.setItemSubFamily(accessories);
//		item3.setActive(true);
//		item3.setCreatedBy("System");
//		item3.setUpdatedBy("System");
//		itemRepository.save(item3);
//
//		// Service
//		Item service = new Item();
//		service.setItemCode("SERV001");
//		service.setName("Service Installation");
//		service.setDescription("Service d'installation professionnel");
//		service.setType(ItemType.SERVICE);
//		service.setUnitPrice(350.00);
//		service.setCostPrice(200.00);
//		service.setStockQuantity(999999); // Unlimited for services
//		service.setTaxable(true);
//		service.setTaxRate(0.19);
//		service.setUnitOfMeasure("SERVICE");
//		service.setCategory("Services");
//		service.setBrand("Hammai Services");
//		service.setItemFamily(servicesFamily);
//		service.setItemSubFamily(installations);
//		service.setActive(true);
//		service.setCreatedBy("System");
//		service.setUpdatedBy("System");
//		itemRepository.save(service);
//
//		// Package Deal
//		Item packageDeal = new Item();
//		packageDeal.setItemCode("PKG001");
//		packageDeal.setName("Pack Promo");
//		packageDeal.setDescription("Pack promotionnel spécial");
//		packageDeal.setType(ItemType.PACKAGE);
//		packageDeal.setUnitPrice(800.00);
//		packageDeal.setCostPrice(600.00);
//		packageDeal.setStockQuantity(50);
//		packageDeal.setMinStockLevel(10);
//		packageDeal.setTaxable(true);
//		packageDeal.setTaxRate(0.19);
//		packageDeal.setUnitOfMeasure("PACK");
//		packageDeal.setCategory("Offres Promotionnelles");
//		packageDeal.setBrand("Hammai Promo");
//		packageDeal.setItemFamily(promotionsFamily);
//		packageDeal.setItemSubFamily(bundles);
//		packageDeal.setActive(true);
//		packageDeal.setCreatedBy("System");
//		packageDeal.setUpdatedBy("System");
//		itemRepository.save(packageDeal);
//	}
//
//	/**
//	 * Create initial item barcodes
//	 */
//	private void initItemBarcodes() {
//		// Get items
//		Item item1 = itemRepository.findByItemCode("PROD001").orElse(null);
//		Item item2 = itemRepository.findByItemCode("PROD002").orElse(null);
//		Item item3 = itemRepository.findByItemCode("PROD003").orElse(null);
//		Item packageDeal = itemRepository.findByItemCode("PKG001").orElse(null);
//
//		if (item1 != null) {
//			// Primary barcode for item1
//			ItemBarcode barcode1 = new ItemBarcode();
//			barcode1.setItem(item1);
//			barcode1.setBarcode("1234567890123");
//			barcode1.setDescription("Primary barcode");
//			barcode1.setIsPrimary(true);
//			barcode1.setActive(true);
//			barcode1.setCreatedBy("System");
//			barcode1.setUpdatedBy("System");
//			itemBarcodeRepository.save(barcode1);
//
//			// Additional barcode for item1
//			ItemBarcode barcode1a = new ItemBarcode();
//			barcode1a.setItem(item1);
//			barcode1a.setBarcode("1234567890123-ALT");
//			barcode1a.setDescription("Alternative barcode");
//			barcode1a.setIsPrimary(false);
//			barcode1a.setActive(true);
//			barcode1a.setCreatedBy("System");
//			barcode1a.setUpdatedBy("System");
//			itemBarcodeRepository.save(barcode1a);
//		}
//
//		if (item2 != null) {
//			// Primary barcode for item2
//			ItemBarcode barcode2 = new ItemBarcode();
//			barcode2.setItem(item2);
//			barcode2.setBarcode("1234567890124");
//			barcode2.setDescription("Primary barcode");
//			barcode2.setIsPrimary(true);
//			barcode2.setActive(true);
//			barcode2.setCreatedBy("System");
//			barcode2.setUpdatedBy("System");
//			itemBarcodeRepository.save(barcode2);
//
//			// Additional barcodes for item2
//			ItemBarcode barcode2a = new ItemBarcode();
//			barcode2a.setItem(item2);
//			barcode2a.setBarcode("1234567890124-A");
//			barcode2a.setDescription("Alternative barcode A");
//			barcode2a.setIsPrimary(false);
//			barcode2a.setActive(true);
//			barcode2a.setCreatedBy("System");
//			barcode2a.setUpdatedBy("System");
//			itemBarcodeRepository.save(barcode2a);
//
//			ItemBarcode barcode2b = new ItemBarcode();
//			barcode2b.setItem(item2);
//			barcode2b.setBarcode("1234567890124-B");
//			barcode2b.setDescription("Alternative barcode B");
//			barcode2b.setIsPrimary(false);
//			barcode2b.setActive(true);
//			barcode2b.setCreatedBy("System");
//			barcode2b.setUpdatedBy("System");
//			itemBarcodeRepository.save(barcode2b);
//		}
//
//		if (item3 != null) {
//			// Primary barcode for item3
//			ItemBarcode barcode3 = new ItemBarcode();
//			barcode3.setItem(item3);
//			barcode3.setBarcode("1234567890125");
//			barcode3.setDescription("Primary barcode");
//			barcode3.setIsPrimary(true);
//			barcode3.setActive(true);
//			barcode3.setCreatedBy("System");
//			barcode3.setUpdatedBy("System");
//			itemBarcodeRepository.save(barcode3);
//		}
//
//		if (packageDeal != null) {
//			// Barcode for package deal
//			ItemBarcode pkgBarcode = new ItemBarcode();
//			pkgBarcode.setItem(packageDeal);
//			pkgBarcode.setBarcode("9876543210987");
//			pkgBarcode.setDescription("Package barcode");
//			pkgBarcode.setIsPrimary(true);
//			pkgBarcode.setActive(true);
//			pkgBarcode.setCreatedBy("System");
//			pkgBarcode.setUpdatedBy("System");
//			itemBarcodeRepository.save(pkgBarcode);
//		}
//	}

//	/**
//	 * Create initial locations/stores
//	 */
//	private void initLocations() {
//		// Main Store / Headquarters
//		Location mainStore = new Location();
//		mainStore.setLocationCode("LOC001");
//		mainStore.setName("Magasin Principal");
//		mainStore.setDescription("Magasin principal et siège social");
//		mainStore.setAddress("Zone Industrielle");
//		mainStore.setCity("Tunis");
//		mainStore.setState("Tunis");
//		mainStore.setCountry("Tunisie");
//		mainStore.setPostalCode("1000");
//		mainStore.setPhone("+216 71 234 567");
//		mainStore.setEmail("store1@hammai-group.tn");
//		mainStore.setContactPerson("Manager Principal");
//		mainStore.setIsDefault(true);
//		mainStore.setActive(true);
//		mainStore.setCreatedBy("System");
//		mainStore.setUpdatedBy("System");
//		locationRepository.save(mainStore);
//
//		// Branch Store 1
//		Location branch1 = new Location();
//		branch1.setLocationCode("LOC002");
//		branch1.setName("Succursale Sfax");
//		branch1.setDescription("Succursale dans la ville de Sfax");
//		branch1.setAddress("Avenue Habib Bourguiba");
//		branch1.setCity("Sfax");
//		branch1.setState("Sfax");
//		branch1.setCountry("Tunisie");
//		branch1.setPostalCode("3000");
//		branch1.setPhone("+216 74 345 678");
//		branch1.setEmail("store2@hammai-group.tn");
//		branch1.setContactPerson("Manager Sfax");
//		branch1.setIsDefault(false);
//		branch1.setActive(true);
//		branch1.setCreatedBy("System");
//		branch1.setUpdatedBy("System");
//		locationRepository.save(branch1);
//
//		// Branch Store 2
//		Location branch2 = new Location();
//		branch2.setLocationCode("LOC003");
//		branch2.setName("Succursale Ariana");
//		branch2.setDescription("Succursale dans la région d'Ariana");
//		branch2.setAddress("Avenue de la République");
//		branch2.setCity("Ariana");
//		branch2.setState("Ariana");
//		branch2.setCountry("Tunisie");
//		branch2.setPostalCode("2080");
//		branch2.setPhone("+216 71 456 789");
//		branch2.setEmail("store3@hammai-group.tn");
//		branch2.setContactPerson("Manager Ariana");
//		branch2.setIsDefault(false);
//		branch2.setActive(true);
//		branch2.setCreatedBy("System");
//		branch2.setUpdatedBy("System");
//		locationRepository.save(branch2);
//
//		// Warehouse
//		Location warehouse = new Location();
//		warehouse.setLocationCode("LOC004");
//		warehouse.setName("Entrepôt Central");
//		warehouse.setDescription("Entrepôt central pour stockage");
//		warehouse.setAddress("Zone Logistique");
//		warehouse.setCity("Ben Arous");
//		warehouse.setState("Ben Arous");
//		warehouse.setCountry("Tunisie");
//		warehouse.setPostalCode("2013");
//		warehouse.setPhone("+216 71 567 890");
//		warehouse.setEmail("warehouse@hammai-group.tn");
//		warehouse.setContactPerson("Responsable Entrepôt");
//		warehouse.setIsDefault(false);
//		warehouse.setActive(true);
//		warehouse.setCreatedBy("System");
//		warehouse.setUpdatedBy("System");
//		locationRepository.save(warehouse);
//	}

	/**
	 * Standalone first-run: creates the "Passenger Customer" and writes its code
	 * into the PASSENGER_CUSTOMER general-setup entry so the POS can use it
	 * immediately without any manual configuration.
	 */
	private void ensurePassengerCustomer() {
		final String code = "PASSENGER";
		if (customerRepository.findByCustomerCode(code).isPresent()) {
			return;
		}
		com.digithink.zsretail.model.Customer passenger = new com.digithink.zsretail.model.Customer();
		passenger.setCustomerCode(code);
		passenger.setName("Passenger Customer");
		passenger.setPhone("00000000");
		passenger.setActive(true);
		passenger.setCreatedBy("System");
		passenger.setUpdatedBy("System");
		customerRepository.save(passenger);

		// Wire the code into PASSENGER_CUSTOMER config (override the empty default)
		generalSetupRepository.findByCode("PASSENGER_CUSTOMER").ifPresent(cfg -> {
			cfg.setValeur(code);
			cfg.setUpdatedBy("System");
			generalSetupRepository.save(cfg);
		});
	}

	// ─────────────────────────────────────────────────────────────────────────
	// General Setup — idempotent ensure (runs on every startup)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Ensures every known GeneralSetup config key exists in the database. Called
	 * unconditionally on every startup — each key is individually guarded by a
	 * findByCode check, so already-existing entries are never touched.
	 * Mode-specific keys are only inserted when the matching mode is active.
	 */
	private void ensureAllGeneralSetupConfigs() {

		// ── POS / Location ────────────────────────────────────────────────────
		ensureConfig("DEFAULT_LOCATION", "", "Default location code for the system", false, ConfigType.STRING);
		ensureConfig("PASSENGER_CUSTOMER", "", "Passenger customer code for POS tickets when no customer is selected",
				false, ConfigType.STRING);
		ensureConfig("ALWAYS_SHOW_BADGE_SCAN_POPUP", "false",
				"If true, always show badge scan popup for restricted functionalities, even if the current user has permission. If false, only show if current user lacks permission.",
				false, ConfigType.BOOLEAN);
		ensureConfig("POS_SHOW_IMAGES", "true",
				"Show product/family/subfamily images in POS cashier screen. Set to false to disable images if the system is slow.",
				false, ConfigType.BOOLEAN);
		ensureConfig("POS_SHOW_STOCK", "false",
				"Show stock quantity on item cards in the POS cashier screen. Only relevant in standalone/franchise mode.",
				false, ConfigType.BOOLEAN);

		// ── Table management ──────────────────────────────────────────────────
		ensureConfig("TABLE_MANAGEMENT_ENABLED", "false", "Enable table management mode in POS", false,
				ConfigType.BOOLEAN);
		ensureConfig("TABLE_MANAGEMENT_TABLE_COUNT", "10", "Number of tables to display in the table selection grid",
				false, ConfigType.NUMBER);

		// ── Returns ───────────────────────────────────────────────────────────
		ensureConfig("MAX_DAYS_FOR_RETURN", "10", "Maximum number of days allowed for product returns", false,
				ConfigType.NUMBER);
		ensureConfig("ENABLE_SIMPLE_RETURN", "true", "Enable simple return (cash refund without voucher)", false,
				ConfigType.BOOLEAN);
		ensureConfig("RETURN_VOUCHER_VALIDITY_DAYS", "30", "Number of days a return voucher remains valid", false,
				ConfigType.NUMBER);
		ensureConfig("BLOCK_RETURN_FOR_PROMOTION", "false",
				"Block returns for items sold via a promotion. When enabled: lines with discount source PROMOTION cannot be returned; tickets with a cart-level promotion are fully blocked.",
				false, ConfigType.BOOLEAN);

		// ── Payment methods ───────────────────────────────────────────────────
		ensureConfig("AUTO_ADD_CASH_PAYMENT_ON_PAYMENT_PAGE", "true",
				"If true, automatically add empty \"Client Espèce\" payment method when opening payment page. If false, keep payment page empty with no selected payment method.",
				false, ConfigType.BOOLEAN);
		ensureConfig("ENABLE_CASH_DISCREPANCY_CHECK", "false",
				"Enable cash discrepancy check when closing session. If true, system validates closing amount matches expected real cash.",
				false, ConfigType.BOOLEAN);
		ensureConfig("ENABLE_PAYMENT_METHOD_CHANGE", "false",
				"Allow an admin to change a ticket's payment method (without changing amounts) from Ticket History, while the ticket's session is not yet synchronized with NAV. Disabled by default.",
				false, ConfigType.BOOLEAN);
		ensureConfig("PLAFOND_ESPECE", "",
				"Plafond espèce (TND). Empty = no limit. When set (e.g. 1000), maximum cash amount allowed per sale in TND.",
				false, ConfigType.STRING);
		ensureConfig("PAYMENT_METHOD_CLIENT_CHEQUE_TITLE_NUMBER_LENGTH", "7",
				"Required length for title number (N° Titre) for CLIENT_CHEQUE payment method. Must be exactly this number of characters.",
				false, ConfigType.NUMBER);
		ensureConfig("PAYMENT_METHOD_TICKET_RESTAURANT_TITLE_NUMBER_LENGTH", "7",
				"Required length for title number (N° Titre) for TICKET_RESTAURANT payment method. Must be exactly this number of characters.",
				false, ConfigType.NUMBER);
		ensureConfig("PAYMENT_METHOD_CHEQUE_CADEAU_TITLE_NUMBER_LENGTH", "7",
				"Required length for title number (N° Titre) for CHEQUE_CADEAU payment method. Must be exactly this number of characters.",
				false, ConfigType.NUMBER);
		ensureConfig("PAYMENT_METHOD_CLIENT_TRAITE_TITLE_NUMBER_LENGTH", "7",
				"Required length for title number (N° Titre) for CLIENT_TRAITE payment method. Must be exactly this number of characters.",
				false, ConfigType.NUMBER);

		// ── Tax stamp (timbre fiscal) ─────────────────────────────────────────
		ensureConfig("ENABLE_TAX_STAMP", "false",
				"Enable tax stamp (timbre fiscal) per receipt. When true, adds configured amount (e.g. 100 millimes in Tunisia) as a line per sale.",
				false, ConfigType.BOOLEAN);
		ensureConfig("TAX_STAMP_VALUE_MILLIMES", "100",
				"Tax stamp amount in millimes (e.g. 100 = 0.100 TND per receipt).", false, ConfigType.NUMBER);
		ensureConfig("TAX_STAMP_ERP_ITEM_CODE", "",
				"ERP item code for the tax stamp line. Used when exporting ticket lines to ERP. Leave empty if not configured.",
				false, ConfigType.STRING);

		// ── Loyalty ───────────────────────────────────────────────────────────
		ensureConfig("LOYALTY_ENABLED", "false",
				"Enable the loyalty (fidélité) program. When true, cashiers can attach loyalty cards to sales and customers earn/redeem points. Configure rates in Loyalty Programs admin page.",
				false, ConfigType.BOOLEAN);
		ensureConfig("TICKET_SHOW_LOYALTY_BALANCE", "true",
				"Show the loyalty points balance (Solde points) on the printed sales ticket when a loyalty member is attached.",
				false, ConfigType.BOOLEAN);
		ensureConfig("TICKET_SHOW_LOYALTY_EARNED", "true",
				"Show the points earned from the current purchase (Points gagnés) on the printed sales ticket.", false,
				ConfigType.BOOLEAN);
		ensureConfig("TOMBOLA_ENABLED", "false",
				"When enabled, a small tombola slip (ticket number + barcode + customer name + phone) is automatically printed alongside the main receipt for tickets attached to a loyalty member.",
				false, ConfigType.BOOLEAN);

		// ── Stock ─────────────────────────────────────────────────────────────
		ensureConfig("ALLOW_NEGATIVE_STOCK", "true",
				"Allow stock to go negative during sales. Applies only in standalone/franchise mode. When false, sale is blocked if stock is insufficient.",
				false, ConfigType.BOOLEAN);

		// ── ERP-only configs ──────────────────────────────────────────────────
		if (!applicationModeService.isStandalone()) {
			ensureConfigWithOptions("ERP_SYNC_TRACKING_LEVEL", "ALL",
					"ERP communication tracking level (ERRORS_ONLY | ERRORS_AND_WARNINGS | ALL)", false,
					ConfigType.SELECT, "ERRORS_ONLY,ERRORS_AND_WARNINGS,ALL");
			ensureConfig("ERP_SKIP_CHEQUE_PAYMENTS", "false",
					"When enabled, cheque payments (CLIENT_CHEQUE) are excluded from ERP session synchronization. Payment headers and lines for cheques will not be sent to NAV.",
					false, ConfigType.BOOLEAN);
		}

		// ── Franchise client configs ──────────────────────────────────────────
		if (applicationModeService.isFranchiseClient()) {
			ensureConfig("FRANCHISE_LAST_ITEM_SYNC", "",
					"Timestamp of last successful item sync from franchise admin (ISO-8601). Empty = full sync on next run.",
					true, ConfigType.DATETIME);
			ensureConfig("FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC", "",
					"Timestamp of last supply reception check from franchise admin (ISO-8601). Empty = never checked.",
					true, ConfigType.DATETIME);
		}
	}

	/**
	 * Inserts a GeneralSetup entry if the code does not yet exist.
	 */
	private void ensureConfig(String code, String defaultValue, String description, boolean readOnly, ConfigType type) {
		ensureConfigWithOptions(code, defaultValue, description, readOnly, type, null);
	}

	/**
	 * Inserts a GeneralSetup entry if the code does not yet exist (SELECT variant
	 * with comma-separated options).
	 */
	private void ensureConfigWithOptions(String code, String defaultValue, String description, boolean readOnly,
			ConfigType type, String options) {
		if (generalSetupRepository.findByCode(code).isPresent()) {
			return;
		}
		GeneralSetup setup = new GeneralSetup();
		setup.setCode(code);
		setup.setValeur(defaultValue);
		setup.setDescription(description);
		setup.setReadOnly(readOnly);
		setup.setConfigType(type);
		setup.setConfigOptions(options);
		setup.setActive(true);
		setup.setCreatedBy("System");
		setup.setUpdatedBy("System");
		generalSetupRepository.save(setup);
	}

	/**
	 * Ensures the system Tax Stamp item exists (hidden in POS, used for timbre
	 * fiscal line). Run on every startup so new deployments get the item even if
	 * migration already ran.
	 */
	private void ensureTaxStampItem() {
		if (itemRepository.findByItemCode("TAX_STAMP").isPresent()) {
			return;
		}
		Item taxStamp = new Item();
		taxStamp.setItemCode("TAX_STAMP");
		taxStamp.setName("Timbre Fiscal");
		taxStamp.setDescription(
				"Tax stamp (timbre fiscal) added automatically per sale when ENABLE_TAX_STAMP is true.");
		taxStamp.setType(ItemType.SERVICE);
		taxStamp.setUnitPrice(0.0);
		taxStamp.setDefaultVAT(0);
		taxStamp.setShowInPos(false);
		taxStamp.setActive(true);
		taxStamp.setCreatedBy("System");
		taxStamp.setUpdatedBy("System");
		itemRepository.save(taxStamp);
	}

	private void ensureErpSyncCheckpointConfigs() {
		ErpSyncCheckpointService.getCheckpointDescriptions().forEach((code, description) -> {
			if (generalSetupRepository.findByCode(code).isPresent()) {
				return;
			}
			GeneralSetup checkpoint = new GeneralSetup();
			checkpoint.setCode(code);
			checkpoint.setValeur("");
			checkpoint.setDescription(description);
			checkpoint.setReadOnly(true);
			checkpoint.setConfigType(ConfigType.DATETIME);
			checkpoint.setActive(true);
			checkpoint.setCreatedBy("System");
			checkpoint.setUpdatedBy("System");
			generalSetupRepository.save(checkpoint);
		});
	}

	private void initErpSyncJobs() {
		createErpJob("0 0 2 * * *", ErpSyncJobType.IMPORT_ITEM_FAMILIES, "Daily import of item families", false);
		createErpJob("0 10 2 * * *", ErpSyncJobType.IMPORT_ITEM_SUBFAMILIES, "Daily import of item subfamilies", false);
		createErpJob("0 0 * * * *", ErpSyncJobType.IMPORT_ITEMS, "Hourly import of items", false);
		createErpJob("0 10 * * * *", ErpSyncJobType.IMPORT_ITEM_BARCODES, "Hourly import of item barcodes", false);
		createErpJob("0 20 2 * * *", ErpSyncJobType.IMPORT_LOCATIONS, "Daily import of locations", false);
		createErpJob("0 30 * * * *", ErpSyncJobType.IMPORT_CUSTOMERS, "Hourly import of customers", false);
		createErpJob("0 40 * * * *", ErpSyncJobType.IMPORT_SALES_PRICES_AND_DISCOUNTS,
				"Hourly import of sales prices and discounts", false);
		createErpJob("0 50 * * * *", ErpSyncJobType.SYNC_ERP_DELETIONS,
				"Sync deletions from ERP Log (Sales Price, Sales Discount)", false);
		createErpJob("0 0 0 1 1 *", ErpSyncJobType.EXPORT_CUSTOMERS,
				"Template job for exporting customers (disabled by default)", false);
		createErpJob("0 0 0 1 1 *", ErpSyncJobType.EXPORT_TICKETS,
				"Template job for exporting tickets (disabled by default)", false);
		createErpJob("0 0 * * * *", ErpSyncJobType.EXPORT_RETURNS, "Export returns to ERP (runs every 1 hour)", true);
		createErpJob("0 0 * * * *", ErpSyncJobType.EXPORT_SESSIONS, "Export sessions to ERP (runs every 1 hour)", true);
	}

	/**
	 * Franchise client: ensures the FRANCHISE_ADMIN vendor is seeded. Config keys
	 * (FRANCHISE_LAST_ITEM_SYNC, etc.) are handled by ensureAllGeneralSetupConfigs.
	 */
	private void ensureFranchiseClientSetup() {
		if (!vendorRepository.findByVendorCode("FRANCHISE_ADMIN").isPresent()) {
			Vendor franchiseVendor = new Vendor();
			franchiseVendor.setVendorCode("FRANCHISE_ADMIN");
			franchiseVendor.setName("Franchise Admin (HQ)");
			franchiseVendor.setPhone("N/A");
			franchiseVendor.setActive(true);
			franchiseVendor.setCreatedBy("System");
			franchiseVendor.setUpdatedBy("System");
			vendorRepository.save(franchiseVendor);
		}
	}

	/**
	 * Seeds APP_VERSION and a single setup record on fresh install. On existing
	 * installs the row already exists — nothing is written. Detailed release notes
	 * are managed via db/X.Y.Z/update.sql on upgrades.
	 */
	private void ensureAppVersion() {
		if (appVersionRepository.count() > 0) {
			return;
		}
		appVersionRepository.save(new AppVersion(appVersion));
		appReleaseNoteRepository.save(new AppReleaseNote(appVersion, "SETUP", "Initial installation"));
	}

	private void createErpJob(String cron, ErpSyncJobType type, String description, boolean enabled) {
		if (erpSyncJobRepository.findByJobType(type).isPresent()) {
			return;
		}
		ErpSyncJob job = new ErpSyncJob();
		job.setJobType(type);
		job.setCronExpression(cron);
		job.setDescription(description);
		job.setEnabled(enabled);
		job.setCreatedBy("System");
		job.setUpdatedBy("System");
		erpSyncJobRepository.save(job);
	}
}
