package com.kaching;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileItem;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.http.api.item.ItemPrice;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the plugin's accounting core with synthetic event sequences against a
 * mocked Client. Game-server behavior (event ordering, projectile timing) is
 * assumed as documented in the plugin; these tests pin the reconciliation logic.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class KaChingAccountingTest
{
	private static final int AMMO_SLOT = EquipmentInventorySlot.AMMO.getSlotIdx();
	private static final int WEAPON_SLOT = EquipmentInventorySlot.WEAPON.getSlotIdx();
	private static final int ARROW = 892; // rune arrow
	private static final int SHARK = 385;
	private static final int PRAYER_POTION_3 = 139;
	private static final int PRAYER_POTION_2 = 141;
	private static final int PRAYER_POTION_1 = 143;
	private static final int DAGANNOTH_BONES = 6729;
	private static final int CAKE = 1891;
	private static final int TWO_THIRDS_CAKE = 1893;
	private static final int SLICE_OF_CAKE = 1895;
	private static final int CHOCOLATE_CAKE = 1897;
	private static final int CHOCOLATE_SLICE = 1901;
	private static final int MEAT_PIZZA = 2293;
	private static final int HALF_MEAT_PIZZA = 2295;
	private static final int APPLE_PIE = 2323;
	private static final int HALF_APPLE_PIE = 2335;

	// Distinct-slot items for modelling a gravestone strip (each its own slot)
	private static final int GEAR_A = 6001;
	private static final int GEAR_B = 6002;
	private static final int GEAR_C = 6003;
	private static final int GEAR_D = 6004;
	private static final int GEAR_E = 6005;
	private static final int GEAR_PRICE = 1000;

	// Deliberately arbitrary tier styling so the tests prove the plugin reads
	// the values from config rather than hardcoding the defaults
	private static final Color LOW_COLOR = new Color(1, 2, 3);
	private static final Color MEDIUM_COLOR = new Color(4, 5, 6);
	private static final Color HIGH_COLOR = new Color(7, 8, 9);
	private static final int LOW_HOLD_SEC = 11;
	private static final int MEDIUM_HOLD_SEC = 22;
	private static final int HIGH_HOLD_SEC = 33;
	private static final int MEDIUM_THRESHOLD = 100_000;
	private static final int HIGH_THRESHOLD = 10_000_000;

	private static final int DEATH_PRICE = 200;
	private static final int CHAOS_PRICE = 80;
	private static final int FIRE_PRICE = 5;
	private static final int ARROW_PRICE = 150;
	private static final int CANNONBALL_PRICE = 180;
	private static final int BLOOD_PRICE = 500;
	private static final int VIAL_PRICE = 10_000;
	private static final int SCALE_PRICE = 300;

	@Mock
	private Client client;
	@Mock
	private ItemManager itemManager;
	@Mock
	private OverlayManager overlayManager;
	@Mock
	private KaChingOverlay overlay;
	@Mock
	private KaChingConfig config;
	@Mock
	private ConfigManager configManager;
	@Mock
	private Player localPlayer;
	@Mock
	private ItemContainer inventory;
	@Mock
	private ItemContainer equipment;
	@Mock
	private EnumComposition runePouchEnum;

	private KaChingPlugin plugin;
	private int tick = 100;
	private int hp = 10; // current hitpoints: >0 alive, 0 dead (until respawn restores it)
	private final Map<Integer, Item> equipSlots = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		plugin = new KaChingPlugin();
		inject("client", client);
		inject("itemManager", itemManager);
		inject("overlayManager", overlayManager);
		inject("overlay", overlay);
		inject("config", config);
		inject("configManager", configManager);

		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runePouchEnum);
		when(client.getVarbitValue(anyInt())).thenReturn(0);
		when(client.getVarpValue(anyInt())).thenReturn(0);
		when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenAnswer(i -> hp);
		when(client.getWidget(anyInt(), anyInt())).thenReturn(null);
		when(equipment.getItem(anyInt())).thenAnswer(inv -> equipSlots.get(inv.<Integer>getArgument(0)));
		when(equipment.getItems()).thenAnswer(inv -> equipSlots.values().toArray(new Item[0]));
		when(itemManager.search(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
		setInventory();

		when(config.trackSpells()).thenReturn(true);
		when(config.trackAmmo()).thenReturn(true);
		when(config.trackChargedWeapons()).thenReturn(true);
		when(config.trackCannon()).thenReturn(true);
		when(config.trackConsumables()).thenReturn(true);
		when(config.trackDeathLoss()).thenReturn(true);
		when(config.playSound()).thenReturn(false);
		when(config.minValue()).thenReturn(1);
		when(config.avasDevice()).thenReturn(KaChingConfig.AvasDevice.AUTO_DETECT);
		when(config.deathLowColor()).thenReturn(LOW_COLOR);
		when(config.deathMediumColor()).thenReturn(MEDIUM_COLOR);
		when(config.deathHighColor()).thenReturn(HIGH_COLOR);
		when(config.deathLowHold()).thenReturn(LOW_HOLD_SEC);
		when(config.deathMediumHold()).thenReturn(MEDIUM_HOLD_SEC);
		when(config.deathHighHold()).thenReturn(HIGH_HOLD_SEC);
		when(config.deathMediumThreshold()).thenReturn(MEDIUM_THRESHOLD);
		when(config.deathHighThreshold()).thenReturn(HIGH_THRESHOLD);

		price(ItemID.DEATHRUNE, DEATH_PRICE);
		price(ItemID.CHAOSRUNE, CHAOS_PRICE);
		price(ItemID.FIRERUNE, FIRE_PRICE);
		price(ARROW, ARROW_PRICE);
		price(ItemID.MCANNONBALL, CANNONBALL_PRICE);
		price(ItemID.BLOODRUNE, BLOOD_PRICE);
		price(ItemID.VIAL_BLOOD, VIAL_PRICE);
		price(ItemID.SNAKEBOSS_SCALE, SCALE_PRICE);
		for (int gear : new int[]{GEAR_A, GEAR_B, GEAR_C, GEAR_D, GEAR_E})
		{
			price(gear, GEAR_PRICE);
		}

		plugin.startUp();
		tick(); // first tick only syncs snapshots
	}

	// ---- spells ----

	@Test
	public void castConsumingRunesIsBilled()
	{
		setInventory(new Item(ItemID.DEATHRUNE, 100));
		tick();
		setInventory(new Item(ItemID.DEATHRUNE, 96));
		tick();
		verify(overlay).add(4L * DEATH_PRICE);
	}

	@Test
	public void ownDroppedRunesAreForgiven()
	{
		setInventory(new Item(ItemID.DEATHRUNE, 100));
		tick();
		setInventory(new Item(ItemID.DEATHRUNE, 96));
		spawnGroundItem(ItemID.DEATHRUNE, 4, TileItem.OWNERSHIP_SELF);
		tick();
		verify(overlay, never()).add(anyLong());
	}

	@Test
	public void anotherPlayersDropDoesNotForgive()
	{
		setInventory(new Item(ItemID.DEATHRUNE, 100));
		tick();
		setInventory(new Item(ItemID.DEATHRUNE, 96));
		spawnGroundItem(ItemID.DEATHRUNE, 4, TileItem.OWNERSHIP_OTHER);
		tick();
		verify(overlay).add(4L * DEATH_PRICE);
	}

	@Test
	public void depositAfterClosingBankIsNotBilled()
	{
		setInventory(new Item(ItemID.DEATHRUNE, 100));
		tick();
		Widget bank = mock(Widget.class);
		when(client.getWidget(InterfaceID.BANKMAIN, 0)).thenReturn(bank);
		tick(); // bank open
		when(client.getWidget(InterfaceID.BANKMAIN, 0)).thenReturn(null);
		setInventory(); // deposit processed the tick after the bank closed
		tick();
		tick(); // still within the close grace
		verify(overlay, never()).add(anyLong());

		// after the grace, normal casting counts again
		setInventory(new Item(ItemID.DEATHRUNE, 50));
		tick();
		setInventory(new Item(ItemID.DEATHRUNE, 46));
		tick();
		verify(overlay).add(4L * DEATH_PRICE);
	}

	@Test
	public void rechargeShapedRemovalIsNotBilled()
	{
		// exactly 5 trident charges: 5 death + 5 chaos + 25 fire
		setInventory(new Item(ItemID.DEATHRUNE, 50), new Item(ItemID.CHAOSRUNE, 50), new Item(ItemID.FIRERUNE, 250));
		tick();
		setInventory(new Item(ItemID.DEATHRUNE, 45), new Item(ItemID.CHAOSRUNE, 45), new Item(ItemID.FIRERUNE, 225));
		tick();
		verify(overlay, never()).add(anyLong());
	}

	@Test
	public void singleCastMatchingRecipeShapeIsStillBilled()
	{
		// 1 death + 1 chaos + 5 fire is one trident charge — far below the
		// recharge threshold, so it must be billed as a cast
		setInventory(new Item(ItemID.DEATHRUNE, 50), new Item(ItemID.CHAOSRUNE, 50), new Item(ItemID.FIRERUNE, 250));
		tick();
		setInventory(new Item(ItemID.DEATHRUNE, 49), new Item(ItemID.CHAOSRUNE, 49), new Item(ItemID.FIRERUNE, 245));
		tick();
		verify(overlay).add((long) DEATH_PRICE + CHAOS_PRICE + 5L * FIRE_PRICE);
	}

	@Test
	public void chargeMessageSuppressesRuneLoss()
	{
		setInventory(new Item(ItemID.DEATHRUNE, 100));
		tick();
		ChatMessage message = new ChatMessage();
		message.setType(ChatMessageType.MESBOX);
		message.setMessage("You add 100 charges to the weapon. New total: 2,000");
		plugin.onChatMessage(message);
		setInventory(new Item(ItemID.DEATHRUNE, 0));
		tick();
		verify(overlay, never()).add(anyLong());
	}

	// ---- ranged ammo ----

	@Test
	public void brokenAmmoIsBilledAfterGraceWindow()
	{
		setEquip(AMMO_SLOT, new Item(ARROW, 100));
		tick();
		setEquip(AMMO_SLOT, new Item(ARROW, 99));
		tick();
		verify(overlay, never()).add(anyLong()); // grace window still open
		ticks(5);
		verify(overlay).add((long) ARROW_PRICE);
	}

	@Test
	public void ammoLandingOnGroundIsForgiven()
	{
		setEquip(AMMO_SLOT, new Item(ARROW, 100));
		tick();
		setEquip(AMMO_SLOT, new Item(ARROW, 99));
		tick();
		spawnGroundItem(ARROW, 1, TileItem.OWNERSHIP_SELF); // projectile lands two ticks later
		tick();
		ticks(5);
		verify(overlay, never()).add(anyLong());
	}

	@Test
	public void unequippingAmmoIsForgiven()
	{
		setEquip(AMMO_SLOT, new Item(ARROW, 100));
		tick();
		equipSlots.remove(AMMO_SLOT);
		setInventory(new Item(ARROW, 100));
		tick();
		ticks(6);
		verify(overlay, never()).add(anyLong());
	}

	@Test
	public void reenablingAmmoTrackingDoesNotBillTheGap()
	{
		when(config.trackAmmo()).thenReturn(false);
		setEquip(AMMO_SLOT, new Item(ARROW, 100));
		tick();
		setEquip(AMMO_SLOT, new Item(ARROW, 50)); // fired while tracking was off
		tick();
		when(config.trackAmmo()).thenReturn(true);
		ticks(6);
		verify(overlay, never()).add(anyLong());
	}

	// ---- cannon ----

	@Test
	public void cannonShotsAreBilled()
	{
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(30);
		tick(); // varp snapshot
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(28);
		tick();
		verify(overlay).add(2L * CANNONBALL_PRICE);
	}

	@Test
	public void cannonPickupRefundIsForgiven()
	{
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(20);
		tick();
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(0);
		setInventory(new Item(ItemID.MCANNONBALL, 20));
		tick();
		verify(overlay, never()).add(anyLong());
	}

	@Test
	public void withdrawingCannonballsDoesNotHideShots()
	{
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(10);
		tick();
		// a shot fires on the same tick a fresh stack is withdrawn from the bank
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(9);
		setInventory(new Item(ItemID.MCANNONBALL, 500));
		tick();
		verify(overlay).add((long) CANNONBALL_PRICE);
	}

	// ---- charged weapons ----

	@Test
	public void scytheSwingWithoutDamageConsumesNothing()
	{
		setEquip(WEAPON_SLOT, new Item(ItemID.SCYTHE_OF_VITUR, 1));
		tick();
		beginTick();
		animate(8056); // SCYTHE_OF_VITUR_ATTACK
		endTick();
		verify(overlay, never()).add(anyLong());
	}

	@Test
	public void scytheSwingWithDamageIsBilled()
	{
		setEquip(WEAPON_SLOT, new Item(ItemID.SCYTHE_OF_VITUR, 1));
		tick();
		beginTick();
		animate(8056);
		hitsplatOnEnemy(3);
		endTick();
		verify(overlay).add(2L * BLOOD_PRICE + VIAL_PRICE / 100);
	}

	@Test
	public void blowpipeCountsOncePerFrameReset()
	{
		setEquip(WEAPON_SLOT, new Item(ItemID.TOXIC_BLOWPIPE_LOADED, 1));
		when(localPlayer.getAnimation()).thenReturn(5061); // SNAKEBOSS_BLOWPIPE_ATTACK
		when(localPlayer.getAnimationFrame()).thenReturn(0);
		tick(); // shot: frame reset observed
		when(localPlayer.getAnimationFrame()).thenReturn(3);
		tick(); // same animation mid-flight: no new shot
		// dart type unknown -> scales-only expected value, rounded
		verify(overlay, times(1)).add(Math.round(SCALE_PRICE * (2.0 / 3)));
	}

	@Test
	public void sameTickCostsSumIntoOnePop()
	{
		setInventory(new Item(ItemID.DEATHRUNE, 10));
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(30);
		tick();
		// a cast and a cannon shot land on the same tick
		setInventory(new Item(ItemID.DEATHRUNE, 9));
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(29);
		tick();
		verify(overlay).add((long) DEATH_PRICE + CANNONBALL_PRICE);
	}

	// ---- food & potions ----

	@Test
	public void eatingFoodIsBilled()
	{
		nameOf(SHARK, "Shark");
		price(SHARK, 800);
		setInventory(new Item(SHARK, 5));
		tick();
		clickConsume("Eat", SHARK);
		setInventory(new Item(SHARK, 4));
		tick();
		verify(overlay).add(800L);
	}

	@Test
	public void droppingFoodWithoutEatingIsSilent()
	{
		nameOf(SHARK, "Shark");
		price(SHARK, 800);
		setInventory(new Item(SHARK, 5));
		tick();
		setInventory(new Item(SHARK, 4)); // no Eat click — dropped/destroyed
		tick();
		verify(overlay, never()).add(anyLong());
	}

	@Test
	public void potionSipCostsThePriceStepToTheLowerDose()
	{
		nameOf(PRAYER_POTION_3, "Prayer potion(3)");
		nameOf(PRAYER_POTION_2, "Prayer potion(2)");
		price(PRAYER_POTION_3, 9_000);
		price(PRAYER_POTION_2, 6_500); // dose prices are not linear
		setInventory(new Item(PRAYER_POTION_3, 1));
		tick();
		clickConsume("Drink", PRAYER_POTION_3);
		setInventory(new Item(PRAYER_POTION_2, 1)); // the (2) appears on the sip tick
		tick();
		verify(overlay).add(2_500L);
	}

	@Test
	public void lastDoseCreditsTheEmptyVial()
	{
		nameOf(PRAYER_POTION_1, "Prayer potion(1)");
		price(PRAYER_POTION_1, 2_600);
		price(ItemID.VIAL_EMPTY, 3);
		setInventory(new Item(PRAYER_POTION_1, 1));
		tick();
		clickConsume("Drink", PRAYER_POTION_1);
		setInventory(new Item(ItemID.VIAL_EMPTY, 1));
		tick();
		verify(overlay).add(2_597L);
	}

	@Test
	public void smashedVialIsNotCredited()
	{
		nameOf(PRAYER_POTION_1, "Prayer potion(1)");
		price(PRAYER_POTION_1, 2_600);
		price(ItemID.VIAL_EMPTY, 3);
		setInventory(new Item(PRAYER_POTION_1, 1));
		tick();
		clickConsume("Drink", PRAYER_POTION_1);
		setInventory(); // vial smashing: no vial appears
		tick();
		verify(overlay).add(2_600L);
	}

	@Test
	public void buryingBonesIsBilled()
	{
		nameOf(DAGANNOTH_BONES, "Dagannoth bones");
		price(DAGANNOTH_BONES, 4_200);
		setInventory(new Item(DAGANNOTH_BONES, 3));
		tick();
		clickConsume("Bury", DAGANNOTH_BONES);
		setInventory(new Item(DAGANNOTH_BONES, 2));
		tick();
		verify(overlay).add(4_200L);
	}

	@Test
	public void firstBiteProRatesWhenPartialIsUntradeable()
	{
		nameOf(MEAT_PIZZA, "Meat pizza");
		nameOf(HALF_MEAT_PIZZA, "1/2 meat pizza");
		price(MEAT_PIZZA, 400);
		price(HALF_MEAT_PIZZA, 0); // partials are not GE-tradeable
		setInventory(new Item(MEAT_PIZZA, 1));
		tick();
		clickConsume("Eat", MEAT_PIZZA);
		setInventory(new Item(HALF_MEAT_PIZZA, 1));
		tick();
		verify(overlay).add(200L);
	}

	@Test
	public void cakeBiteBillsAThird()
	{
		nameOf(CAKE, "Cake");
		nameOf(TWO_THIRDS_CAKE, "2/3 cake");
		price(CAKE, 120);
		price(TWO_THIRDS_CAKE, 0);
		setInventory(new Item(CAKE, 1));
		tick();
		clickConsume("Eat", CAKE);
		setInventory(new Item(TWO_THIRDS_CAKE, 1));
		tick();
		verify(overlay).add(40L);
	}

	@Test
	public void sliceOfCakePricesAThirdOffTheCake()
	{
		nameOf(SLICE_OF_CAKE, "Slice of cake");
		price(SLICE_OF_CAKE, 0);
		price(CAKE, 120);
		ItemPrice base = mock(ItemPrice.class);
		when(base.getName()).thenReturn("Cake");
		when(base.getId()).thenReturn(CAKE);
		when(itemManager.search("cake")).thenReturn(List.of(base));
		setInventory(new Item(SLICE_OF_CAKE, 1));
		tick();
		clickConsume("Eat", SLICE_OF_CAKE);
		setInventory();
		tick();
		verify(overlay).add(40L);
	}

	@Test
	public void chocolateSlicePricesAThirdOffChocolateCake()
	{
		nameOf(CHOCOLATE_SLICE, "Chocolate slice");
		price(CHOCOLATE_SLICE, 0);
		price(CHOCOLATE_CAKE, 150);
		ItemPrice base = mock(ItemPrice.class);
		when(base.getName()).thenReturn("Chocolate cake");
		when(base.getId()).thenReturn(CHOCOLATE_CAKE);
		when(itemManager.search("Chocolate cake")).thenReturn(List.of(base));
		setInventory(new Item(CHOCOLATE_SLICE, 1));
		tick();
		clickConsume("Eat", CHOCOLATE_SLICE);
		setInventory();
		tick();
		verify(overlay).add(50L);
	}

	@Test
	public void eatingAnUntradeablePartialPricesOffTheBase()
	{
		nameOf(HALF_MEAT_PIZZA, "1/2 meat pizza");
		price(HALF_MEAT_PIZZA, 0);
		price(MEAT_PIZZA, 400);
		ItemPrice base = mock(ItemPrice.class);
		when(base.getName()).thenReturn("Meat pizza");
		when(base.getId()).thenReturn(MEAT_PIZZA);
		when(itemManager.search("meat pizza")).thenReturn(List.of(base));
		setInventory(new Item(HALF_MEAT_PIZZA, 1));
		tick();
		clickConsume("Eat", HALF_MEAT_PIZZA);
		setInventory();
		tick();
		verify(overlay).add(200L);
	}

	@Test
	public void halfAnPieResidueMatchesVowelPrefix()
	{
		nameOf(APPLE_PIE, "Apple pie");
		nameOf(HALF_APPLE_PIE, "Half an apple pie");
		price(APPLE_PIE, 700);
		price(HALF_APPLE_PIE, 320);
		setInventory(new Item(APPLE_PIE, 1));
		tick();
		clickConsume("Eat", APPLE_PIE);
		setInventory(new Item(HALF_APPLE_PIE, 1));
		tick();
		verify(overlay).add(380L);
	}

	@Test
	public void sipFallsBackToLinearProRatingWithoutResidue()
	{
		nameOf(PRAYER_POTION_3, "Prayer potion(3)");
		price(PRAYER_POTION_3, 9_000);
		setInventory(new Item(PRAYER_POTION_3, 1));
		tick();
		clickConsume("Drink", PRAYER_POTION_3);
		setInventory(); // lower dose not identifiable this tick
		tick();
		verify(overlay).add(3_000L);
	}

	@Test
	public void expiredConsumeClickDoesNotBillLaterDecrease()
	{
		nameOf(SHARK, "Shark");
		price(SHARK, 800);
		setInventory(new Item(SHARK, 5));
		tick();
		clickConsume("Eat", SHARK);
		ticks(3); // click expires unconsumed
		setInventory(new Item(SHARK, 4)); // later decrease (e.g. dropped)
		tick();
		verify(overlay, never()).add(anyLong());
	}

	// ---- death losses ----

	@Test
	public void deathLossRollsInAfterRespawn()
	{
		// Full loadout, keep one item on respawn: the gravestone strips four items
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1),
			new Item(GEAR_D, 1), new Item(GEAR_E, 1));
		tick();
		die();
		tick(); // death animation: still carrying the full loadout, no respawn yet
		verify(overlay, never()).showDeathLoss(anyLong(), any(), anyInt());
		respawn();
		setInventory(new Item(GEAR_E, 1)); // gravestone strips four items on the respawn tick
		tick();
		verify(overlay).showDeathLoss(4L * GEAR_PRICE, LOW_COLOR, LOW_HOLD_SEC * 1000);
	}

	@Test
	public void lowSlotDeathLosingMostOfYourValueIsShown()
	{
		// Two items, lose the valuable one and keep the cheap one: measured across
		// the respawn, so it needs no slot heuristic (the real-world case)
		price(GEAR_A, 2037);
		price(GEAR_B, 5);
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1));
		tick();
		die();
		tick(); // death animation
		respawn();
		setInventory(new Item(GEAR_B, 1)); // kept only the 5 gp item
		tick();
		verify(overlay).showDeathLoss(2037L, LOW_COLOR, LOW_HOLD_SEC * 1000);
	}

	@Test
	public void consumptionWhileDyingIsNotCountedAsDeathLoss()
	{
		// The log case: a valuable item is eaten on the death tick, then respawn
		// keeps the cheap one. The 2037 was consumed, not sent to the grave, so the
		// pre-strip baseline tracks down past it and the death shows nothing.
		price(GEAR_A, 2037);
		price(GEAR_B, 5);
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1));
		tick();
		die();
		setInventory(new Item(GEAR_B, 1)); // ate the 2037 item as you died
		tick(); // dead: baseline falls to the post-consumption 5 gp
		respawn(); // gravestone has nothing left to take
		ticks(5);
		verify(overlay, never()).showDeathLoss(anyLong(), any(), anyInt());
	}

	@Test
	public void lostEquipmentCountsTowardDeathLoss()
	{
		setEquip(WEAPON_SLOT, new Item(GEAR_A, 1));
		setEquip(AMMO_SLOT, new Item(ARROW, 100));
		setInventory(new Item(GEAR_B, 1), new Item(GEAR_C, 1));
		tick();
		die();
		tick(); // death animation
		respawn();
		equipSlots.clear();
		setInventory(); // strip empties two equipment + two inventory slots
		tick();
		verify(overlay).showDeathLoss((long) GEAR_PRICE + 100L * ARROW_PRICE + 2L * GEAR_PRICE,
			LOW_COLOR, LOW_HOLD_SEC * 1000);
		verify(overlay, never()).add(anyLong()); // not double-billed as broken ammo
	}

	@Test
	public void safeDeathStaysSilent()
	{
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1), new Item(GEAR_D, 1));
		tick();
		die();
		tick(); // death animation
		respawn(); // everything kept — no gravestone strip
		ticks(4);
		verify(overlay, never()).showDeathLoss(anyLong(), any(), anyInt());
	}

	@Test
	public void lateRespawnStillShowsTheLoss()
	{
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1),
			new Item(GEAR_D, 1), new Item(GEAR_E, 1));
		tick();
		die();
		ticks(8); // a long death animation — still dead, containers untouched
		verify(overlay, never()).showDeathLoss(anyLong(), any(), anyInt());
		respawn();
		setInventory(new Item(GEAR_E, 1)); // strip lands on the (late) respawn tick
		tick();
		verify(overlay).showDeathLoss(4L * GEAR_PRICE, LOW_COLOR, LOW_HOLD_SEC * 1000);
	}

	@Test
	public void bankOpenedAfterDeathCancelsTheCheck()
	{
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1), new Item(GEAR_D, 1));
		tick();
		die();
		Widget bank = mock(Widget.class);
		when(client.getWidget(InterfaceID.BANKMAIN, 0)).thenReturn(bank);
		tick(); // bank open the tick after death
		respawn();
		setInventory(); // a big deposit empties slots, but it is not a gravestone strip
		ticks(10);
		verify(overlay, never()).showDeathLoss(anyLong(), any(), anyInt());
	}

	@Test
	public void deathLossToggleOffStaysSilent()
	{
		when(config.trackDeathLoss()).thenReturn(false);
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1), new Item(GEAR_D, 1));
		tick();
		die();
		tick(); // death animation
		respawn();
		setInventory();
		ticks(2);
		verify(overlay, never()).showDeathLoss(anyLong(), any(), anyInt());
	}

	@Test
	public void anotherActorsDeathDoesNotArm()
	{
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1), new Item(GEAR_D, 1));
		tick();
		plugin.onActorDeath(new ActorDeath(mock(Actor.class)));
		setInventory(); // slots empty, but no local death was armed
		ticks(6);
		verify(overlay, never()).showDeathLoss(anyLong(), any(), anyInt());
	}

	// ---- death loss tiers ----

	/** Dies keeping a 5 gp item, so the loss across the respawn is exactly {@code value}. */
	private void dieLosing(int value)
	{
		price(GEAR_A, value);
		price(GEAR_B, 5);
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1));
		tick();
		die();
		tick(); // death animation
		respawn();
		setInventory(new Item(GEAR_B, 1)); // gravestone takes GEAR_A
		tick();
	}

	@Test
	public void lossBelowMediumThresholdIsLowTier()
	{
		dieLosing(MEDIUM_THRESHOLD - 1);
		verify(overlay).showDeathLoss(MEDIUM_THRESHOLD - 1L, LOW_COLOR, LOW_HOLD_SEC * 1000);
	}

	@Test
	public void lossExactlyAtMediumThresholdIsMediumTier()
	{
		// the higher tier owns the boundary: equality goes up
		dieLosing(MEDIUM_THRESHOLD);
		verify(overlay).showDeathLoss((long) MEDIUM_THRESHOLD, MEDIUM_COLOR, MEDIUM_HOLD_SEC * 1000);
	}

	@Test
	public void lossExactlyAtHighThresholdIsHighTier()
	{
		dieLosing(HIGH_THRESHOLD);
		verify(overlay).showDeathLoss((long) HIGH_THRESHOLD, HIGH_COLOR, HIGH_HOLD_SEC * 1000);
	}

	@Test
	public void lossAboveHighThresholdIsHighTier()
	{
		dieLosing(HIGH_THRESHOLD + 1);
		verify(overlay).showDeathLoss(HIGH_THRESHOLD + 1L, HIGH_COLOR, HIGH_HOLD_SEC * 1000);
	}

	@Test
	public void tinyLossStillShowsWithoutAFloor()
	{
		// No floor: losing anything at all to the grave rolls in (low tier here)
		for (int gear : new int[]{GEAR_A, GEAR_B, GEAR_C, GEAR_D})
		{
			price(gear, 1);
		}
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1), new Item(GEAR_D, 1));
		tick();
		die();
		tick(); // death animation
		respawn();
		setInventory(); // four 1 gp items stripped — a 4 gp loss
		tick();
		verify(overlay).showDeathLoss(4L, LOW_COLOR, LOW_HOLD_SEC * 1000);
	}

	@Test
	public void tierSettingsAreReadWhenTheLossLands()
	{
		setInventory(new Item(GEAR_A, 1), new Item(GEAR_B, 1), new Item(GEAR_C, 1), new Item(GEAR_D, 1));
		tick();
		// settings changed after startup still style the next death
		when(config.deathLowColor()).thenReturn(Color.CYAN);
		when(config.deathLowHold()).thenReturn(60);
		die();
		tick(); // death animation
		respawn();
		setInventory();
		tick();
		verify(overlay).showDeathLoss(4L * GEAR_PRICE, Color.CYAN, 60_000);
	}

	@Test
	public void devCommandFiresAFakeLossAtTheChosenValue()
	{
		plugin.onCommandExecuted(new CommandExecuted("kcdeath", new String[]{"25000000"}));
		verify(overlay).showDeathLoss(25_000_000L, HIGH_COLOR, HIGH_HOLD_SEC * 1000);
	}

	// ---- harness ----

	private void inject(String field, Object value) throws Exception
	{
		Field f = KaChingPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void price(int itemId, int price)
	{
		when(itemManager.getItemPrice(itemId)).thenReturn(price);
	}

	private void setInventory(Item... items)
	{
		when(inventory.getItems()).thenReturn(items);
	}

	private void setEquip(int slotIdx, Item item)
	{
		equipSlots.put(slotIdx, item);
	}

	private void beginTick()
	{
		tick++;
	}

	private void endTick()
	{
		plugin.onGameTick(new GameTick());
	}

	private void tick()
	{
		beginTick();
		endTick();
	}

	private void ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			tick();
		}
	}

	private void die()
	{
		hp = 0; // hitpoints hit zero — dead until respawn
		plugin.onActorDeath(new ActorDeath(localPlayer));
	}

	private void respawn()
	{
		hp = 10; // hitpoints restored on respawn; the gravestone strips the same tick
	}

	private void spawnGroundItem(int itemId, int quantity, int ownership)
	{
		TileItem item = mock(TileItem.class);
		when(item.getId()).thenReturn(itemId);
		when(item.getQuantity()).thenReturn(quantity);
		when(item.getOwnership()).thenReturn(ownership);
		plugin.onItemSpawned(new ItemSpawned(null, item));
	}

	private void animate(int animationId)
	{
		when(localPlayer.getAnimation()).thenReturn(animationId);
		AnimationChanged event = new AnimationChanged();
		event.setActor(localPlayer);
		plugin.onAnimationChanged(event);
	}

	private void nameOf(int itemId, String name)
	{
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.getName()).thenReturn(name);
		when(itemManager.getItemComposition(itemId)).thenReturn(composition);
	}

	private void clickConsume(String option, int itemId)
	{
		MenuEntry entry = mock(MenuEntry.class);
		when(entry.isItemOp()).thenReturn(true);
		when(entry.getOption()).thenReturn(option);
		when(entry.getItemId()).thenReturn(itemId);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	private void hitsplatOnEnemy(int amount)
	{
		Actor enemy = mock(Actor.class);
		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.isMine()).thenReturn(true);
		when(hitsplat.getAmount()).thenReturn(amount);
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(enemy);
		event.setHitsplat(hitsplat);
		plugin.onHitsplatApplied(event);
	}
}
