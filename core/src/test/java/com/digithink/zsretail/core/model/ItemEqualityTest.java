package com.digithink.zsretail.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.ProxyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 3.1.0, promotions for variants step 3: an Item is its row. Equal by id (read through getId(), so a Hibernate proxy
 * equals its item), an item without an id equal only to itself, one hash for every item. The Lombok equality of before
 * left the id out: items sent as { id } only were all equal and a set kept one (Emtop 1.12.1, a group saved with one
 * item). Uses Hibernate's real ByteBuddy proxy, as ItemJsonProxyTest (no session, no database).
 */
class ItemEqualityTest {

	@Test
	@DisplayName("Same id: equal whatever the other fields; another id: not equal; the hash never depends on them")
	void byId() {
		Item a = item(7L, "ART-7", "Chemise");
		Item b = item(7L, "OTHER", "Another name");
		assertEquals(a, b);
		assertEquals(b, a);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, item(8L, "ART-7", "Chemise"), "same fields, another row");
		assertNotEquals(a, null);
		assertNotEquals(a, "ART-7");
	}

	@Test
	@DisplayName("Items sent as { id } only stay distinct in a set (the group items of a promotion)")
	void idsOnlyInASet() {
		Set<Item> set = new HashSet<>(Arrays.asList(idOnly(1L), idOnly(2L), idOnly(3L), idOnly(2L)));
		assertEquals(3, set.size(), "Lombok's equality kept one");
		Promotion group = new Promotion();
		group.getGroupItems().addAll(Arrays.asList(idOnly(4L), idOnly(5L), idOnly(4L)));
		assertEquals(2, group.getGroupItems().size());
	}

	@Test
	@DisplayName("No id (not saved yet): equal only to itself, even with every field the same")
	void withoutId() {
		Item a = item(null, "NEW", "New item");
		Item b = item(null, "NEW", "New item");
		assertEquals(a, a);
		assertNotEquals(a, b);
		assertNotEquals(a, item(9L, "NEW", "New item"));
		assertNotEquals(item(9L, "NEW", "New item"), a);
	}

	@Test
	@DisplayName("An item that gets its id at save stays found in the set holding it")
	void hashStableAtSave() {
		Item item = item(null, "NEW", "New item");
		Set<Item> set = new HashSet<>(Collections.singleton(item));
		item.setId(12L);
		item.setName("Renamed");
		assertTrue(set.contains(item));
		assertTrue(set.contains(idOnly(12L)));
	}

	@Test
	@DisplayName("A Hibernate proxy equals its loaded item both ways; an item equals a proxy not loaded without loading it")
	void proxy() throws Exception {
		Item loaded = item(7L, "ART-7", "Chemise");
		Item proxy = initializedProxy(loaded);
		assertTrue(proxy instanceof HibernateProxy);
		assertEquals(loaded, proxy);
		assertEquals(proxy, loaded);
		assertEquals(proxy, item(7L, "ART-7", "Chemise"));
		assertEquals(loaded.hashCode(), proxy.hashCode());
		assertNotEquals(proxy, item(8L, "ART-8", "Pull"));

		Item notLoaded = uninitializedProxy(7L);
		assertEquals(loaded, notLoaded, "reads the proxy's id only");
		assertTrue(((HibernateProxy) notLoaded).getHibernateLazyInitializer().isUninitialized(), "still not loaded");
		assertEquals(2, new HashSet<>(Arrays.asList(loaded, proxy, item(8L, "ART-8", "Pull"))).size());
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private static Item item(Long id, String code, String name) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName(name);
		return item;
	}

	/** An item as a request body sends it in a group: { id } only. */
	private static Item idOnly(long id) {
		Item item = new Item();
		item.setId(id);
		return item;
	}

	private static ProxyFactory proxyFactory() throws Exception {
		ProxyFactory factory = new BytecodeProviderImpl().getProxyFactoryFactory().buildProxyFactory(null);
		factory.postInstantiate(Item.class.getName(), Item.class, Collections.<Class>singleton(HibernateProxy.class),
				Item.class.getMethod("getId"), Item.class.getMethod("setId", Long.class), null);
		return factory;
	}

	/** The proxy Hibernate stores in a lazy Item field, once loaded. */
	private static Item initializedProxy(Item loaded) throws Exception {
		HibernateProxy proxy = proxyFactory().getProxy(loaded.getId(), null);
		proxy.getHibernateLazyInitializer().setImplementation(loaded);
		return (Item) proxy;
	}

	/** A lazy Item field not loaded yet (no session: loading it would fail). */
	private static Item uninitializedProxy(long id) throws Exception {
		return (Item) proxyFactory().getProxy(id, null);
	}
}
