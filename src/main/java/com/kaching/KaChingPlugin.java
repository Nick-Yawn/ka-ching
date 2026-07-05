package com.kaching;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.SoundEffectID;
import net.runelite.api.TileItem;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Ka-Ching!",
	description = "Shows the GE price of every rune you cast and every ammo you break, with a coin jingle",
	tags = {"meme", "money", "cost", "ge", "sound", "ammo", "runes"}
)
public class KaChingPlugin extends Plugin
{
	// Ammo leaves the equipment slot when fired, but only hits the ground when
	// the projectile lands. Hold losses this many ticks before declaring them broken.
	private static final int AMMO_GRACE_TICKS = 4;
	// After dying, gear scatters everywhere; don't count anything for a while.
	private static final int DEATH_COOLDOWN_TICKS = 10;

	private static final Set<Integer> RUNE_IDS = new HashSet<>(List.of(
		ItemID.AIR_RUNE, ItemID.WATER_RUNE, ItemID.EARTH_RUNE, ItemID.FIRE_RUNE,
		ItemID.MIND_RUNE, ItemID.BODY_RUNE, ItemID.COSMIC_RUNE, ItemID.CHAOS_RUNE,
		ItemID.NATURE_RUNE, ItemID.LAW_RUNE, ItemID.DEATH_RUNE, ItemID.ASTRAL_RUNE,
		ItemID.BLOOD_RUNE, ItemID.SOUL_RUNE, ItemID.WRATH_RUNE, ItemID.SUNFIRE_RUNE,
		ItemID.MIST_RUNE, ItemID.DUST_RUNE, ItemID.MUD_RUNE,
		ItemID.SMOKE_RUNE, ItemID.STEAM_RUNE, ItemID.LAVA_RUNE
	));

	// Interfaces where items legitimately leave the inventory/equipment without being consumed
	private static final int[] BUSY_INTERFACE_GROUPS = {
		12,  // bank
		192, // deposit box
		300, // shop
		334, // trade confirm
		335, // trade
		402, // GE collection box
		465, // grand exchange
	};

	private static final int[] POUCH_RUNE_VARBITS = {
		Varbits.RUNE_POUCH_RUNE1, Varbits.RUNE_POUCH_RUNE2, Varbits.RUNE_POUCH_RUNE3,
		Varbits.RUNE_POUCH_RUNE4, Varbits.RUNE_POUCH_RUNE5, Varbits.RUNE_POUCH_RUNE6,
	};
	private static final int[] POUCH_AMOUNT_VARBITS = {
		Varbits.RUNE_POUCH_AMOUNT1, Varbits.RUNE_POUCH_AMOUNT2, Varbits.RUNE_POUCH_AMOUNT3,
		Varbits.RUNE_POUCH_AMOUNT4, Varbits.RUNE_POUCH_AMOUNT5, Varbits.RUNE_POUCH_AMOUNT6,
	};

	private static final int[] TRACKED_EQUIP_SLOTS = {
		EquipmentInventorySlot.WEAPON.getSlotIdx(), // thrown weapons (knives, darts, chins)
		EquipmentInventorySlot.AMMO.getSlotIdx(),
	};

	// Recharging a weapon (manually or via a banker) consumes runes from the
	// inventory; these messages mark the tick so the rune tracker skips it
	private static final Pattern CHARGE_MESSAGE = Pattern.compile(
		"^(You add \\S+ charges? to|The banker charges your|You charge)");

	// Right-click Check on the blowpipe: "Darts: Dragon dart x 16,000. Scales: 11,234 (69.2%)."
	private static final Pattern BLOWPIPE_CHECK = Pattern.compile(
		"Darts: (\\S+)(?: dart)? x [\\d,]+\\. Scales: [\\d,]+");

	private static final Map<String, Integer> DART_IDS = Map.of(
		"bronze", ItemID.BRONZE_DART,
		"iron", ItemID.IRON_DART,
		"steel", ItemID.STEEL_DART,
		"mithril", ItemID.MITHRIL_DART,
		"adamant", ItemID.ADAMANT_DART,
		"rune", ItemID.RUNE_DART,
		"amethyst", ItemID.AMETHYST_DART,
		"dragon", ItemID.DRAGON_DART
	);

	// Cape-slot item -> fraction of darts lost per blowpipe shot (1 - recovery rate)
	private static final Map<Integer, Double> AVAS_CAPES = Map.of(
		ItemID.AVAS_ATTRACTOR, 0.4,
		ItemID.AVAS_ACCUMULATOR, 0.28,
		ItemID.ACCUMULATOR_MAX_CAPE, 0.28,
		ItemID.AVAS_ASSEMBLER, 0.2,
		ItemID.ASSEMBLER_MAX_CAPE, 0.2,
		ItemID.MASORI_ASSEMBLER, 0.2,
		ItemID.MASORI_ASSEMBLER_MAX_CAPE, 0.2,
		ItemID.BLESSED_DIZANAS_QUIVER, 0.2,
		ItemID.DIZANAS_MAX_CAPE, 0.2
	);

	// Classic ItemID dropped CANNONBALL; Jagex's internal name is "mcannonball"
	private static final int CANNONBALL = net.runelite.api.gameval.ItemID.MCANNONBALL;
	private static final int[] CANNONBALL_IDS = {CANNONBALL, ItemID.GRANITE_CANNONBALL};

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private KaChingOverlay overlay;

	@Inject
	private KaChingConfig config;

	@Inject
	private ConfigManager configManager;

	private final Map<Integer, Integer> prevInv = new HashMap<>();
	private final Map<Integer, Integer> prevPouch = new HashMap<>();
	private final Map<Integer, Integer> groundThisTick = new HashMap<>();
	private final List<PendingAmmo> pendingAmmo = new ArrayList<>();
	private final int[] prevSlotIds = {-1, -1};
	private final int[] prevSlotQtys = {0, 0};
	private boolean synced;
	private int deathCooldown;
	private int lastAnimationId = -1;
	private int lastAnimationTick = -1;
	private int suppressRunesUntilTick = -1;
	private int prevCannonAmmo = -1;
	private int cannonBallItemId = CANNONBALL;
	private int dartItemId = -1;
	private boolean dartHintShown;
	private boolean dealtDamageThisTick;

	private static class PendingAmmo
	{
		final int itemId;
		int quantity;
		final int expiryTick;

		PendingAmmo(int itemId, int quantity, int expiryTick)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.expiryTick = expiryTick;
		}
	}

	@Provides
	KaChingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(KaChingConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		reset();
		dartHintShown = false;
		String dart = configManager.getConfiguration("kaching", "dartType");
		dartItemId = dart != null && DART_IDS.containsKey(dart) ? DART_IDS.get(dart) : -1;
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlay.clear();
		reset();
	}

	private void reset()
	{
		prevInv.clear();
		prevPouch.clear();
		groundThisTick.clear();
		pendingAmmo.clear();
		prevSlotIds[0] = prevSlotIds[1] = -1;
		prevSlotQtys[0] = prevSlotQtys[1] = 0;
		synced = false;
		deathCooldown = 0;
		lastAnimationId = -1;
		lastAnimationTick = -1;
		suppressRunesUntilTick = -1;
		prevCannonAmmo = -1;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			reset();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			deathCooldown = DEATH_COOLDOWN_TICKS;
			pendingAmmo.clear();
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		int animation = event.getActor().getAnimation();
		if (animation != -1)
		{
			lastAnimationId = animation;
			lastAnimationTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
			&& type != ChatMessageType.MESBOX && type != ChatMessageType.DIALOG)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());

		if (CHARGE_MESSAGE.matcher(message).find())
		{
			suppressRunesUntilTick = client.getTickCount() + 1;
			return;
		}

		Matcher dartMatcher = BLOWPIPE_CHECK.matcher(message);
		if (dartMatcher.find())
		{
			String dartName = dartMatcher.group(1).toLowerCase();
			Integer dartId = DART_IDS.get(dartName);
			if (dartId != null)
			{
				dartItemId = dartId;
				configManager.setConfiguration("kaching", "dartType", dartName);
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() != client.getLocalPlayer()
			&& event.getHitsplat().isMine()
			&& event.getHitsplat().getAmount() > 0)
		{
			dealtDamageThisTick = true;
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		TileItem item = event.getItem();
		groundThisTick.merge(item.getId(), item.getQuantity(), Integer::sum);
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{
		int delta = event.getNewQuantity() - event.getOldQuantity();
		if (delta > 0)
		{
			groundThisTick.merge(event.getItem().getId(), delta, Integer::sum);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			groundThisTick.clear();
			return;
		}

		Map<Integer, Integer> curInv = readInventory();
		Map<Integer, Integer> curPouch = readRunePouch();

		boolean counting = synced && deathCooldown == 0 && !isBusyInterfaceOpen();
		if (deathCooldown > 0)
		{
			deathCooldown--;
		}

		long value = 0;

		if (counting && config.trackSpells() && client.getTickCount() > suppressRunesUntilTick)
		{
			value += runesConsumedValue(curInv, curPouch);
		}

		if (config.trackAmmo())
		{
			value += trackAmmo(curInv, counting);
		}

		if (counting && config.trackChargedWeapons())
		{
			value += chargedWeaponValue();
		}

		if (config.trackCannon())
		{
			value += cannonValue(curInv);
		}

		if (value >= Math.max(1, config.minValue()))
		{
			kaching(value);
		}

		prevInv.clear();
		prevInv.putAll(curInv);
		prevPouch.clear();
		prevPouch.putAll(curPouch);
		groundThisTick.clear();
		dealtDamageThisTick = false;
		synced = true;
	}

	/**
	 * Runes that vanished from inventory + rune pouch this tick, minus any that
	 * hit the ground (dropped, not cast), priced at GE value.
	 */
	private long runesConsumedValue(Map<Integer, Integer> curInv, Map<Integer, Integer> curPouch)
	{
		long value = 0;
		for (int runeId : RUNE_IDS)
		{
			int prev = prevInv.getOrDefault(runeId, 0) + prevPouch.getOrDefault(runeId, 0);
			int cur = curInv.getOrDefault(runeId, 0) + curPouch.getOrDefault(runeId, 0);
			int consumed = prev - cur;
			if (consumed <= 0)
			{
				continue;
			}
			consumed -= takeFromGround(runeId, consumed);
			if (consumed > 0)
			{
				value += (long) itemManager.getItemPrice(runeId) * consumed;
			}
		}
		return value;
	}

	/**
	 * Ammo accounting. Equipment slot decreases become pending losses; pending
	 * losses are forgiven if the ammo shows up on the ground (recoverable drop)
	 * within the grace window, and cashed in as broken once the window expires.
	 * Ava's saves never decrement the slot, so they never enter the pipeline.
	 */
	private long trackAmmo(Map<Integer, Integer> curInv, boolean counting)
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		for (int s = 0; s < TRACKED_EQUIP_SLOTS.length; s++)
		{
			int id = -1;
			int qty = 0;
			if (equipment != null)
			{
				Item item = equipment.getItem(TRACKED_EQUIP_SLOTS[s]);
				if (item != null && item.getQuantity() > 0)
				{
					id = item.getId();
					qty = item.getQuantity();
				}
			}

			int prevId = prevSlotIds[s];
			int prevQty = prevSlotQtys[s];
			prevSlotIds[s] = id;
			prevSlotQtys[s] = qty;

			// Same item shrank, or the slot emptied (last ammo fired / unequipped)
			if (!counting || prevId == -1 || (id != prevId && id != -1))
			{
				continue;
			}
			int lost = prevQty - qty;
			// Unequipped ammo lands in the inventory the same tick; forgive that much
			lost -= Math.max(0, curInv.getOrDefault(prevId, 0) - prevInv.getOrDefault(prevId, 0));
			if (lost > 0)
			{
				pendingAmmo.add(new PendingAmmo(prevId, lost, client.getTickCount() + AMMO_GRACE_TICKS));
			}
		}

		long value = 0;
		for (Iterator<PendingAmmo> it = pendingAmmo.iterator(); it.hasNext(); )
		{
			PendingAmmo pending = it.next();
			pending.quantity -= takeFromGround(pending.itemId, pending.quantity);
			if (pending.quantity <= 0)
			{
				it.remove();
			}
			else if (client.getTickCount() >= pending.expiryTick)
			{
				value += (long) itemManager.getItemPrice(pending.itemId) * pending.quantity;
				it.remove();
			}
		}
		return value;
	}

	/**
	 * Charged weapons (tridents, sang, shadow, sceptre, tentacle, blowpipe) burn
	 * internal charges invisible to item containers, so attacks are detected
	 * directly: one AnimationChanged per attack for weapons whose animation ends
	 * between attacks, or an animation frame reset for the blowpipe, whose
	 * animation outlasts its rapid-fire cooldown and never re-fires the event.
	 */
	private long chargedWeaponValue()
	{
		Player player = client.getLocalPlayer();
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (player == null || equipment == null)
		{
			return 0;
		}
		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return 0;
		}
		ChargedWeapon charged = ChargedWeapon.forWeapon(weapon.getId());
		if (charged == null)
		{
			return 0;
		}

		boolean attacked;
		if (charged.frameReset)
		{
			attacked = charged.hasAnimation(player.getAnimation()) && player.getAnimationFrame() == 0;
		}
		else
		{
			attacked = lastAnimationTick == client.getTickCount() && charged.hasAnimation(lastAnimationId);
		}
		if (!attacked)
		{
			return 0;
		}

		// Scythe charges only burn when at least one hit deals damage; scythe is
		// melee, so its hitsplats land on the same tick as the attack animation
		if (charged == ChargedWeapon.SCYTHE_OF_VITUR && !dealtDamageThisTick)
		{
			return 0;
		}

		if (charged == ChargedWeapon.TOXIC_BLOWPIPE)
		{
			return blowpipeShotValue(equipment);
		}

		double value = 0;
		for (ChargedWeapon.ChargeCost cost : charged.costs)
		{
			value += priceOf(cost.itemId) * cost.quantity;
		}
		return Math.round(value);
	}

	/**
	 * Blowpipe costs are expected values, not observations: 2/3 scale per shot
	 * (1-in-3 save chance) plus darts lost at (1 - Ava's recovery rate). Dart
	 * type is learned from the right-click Check message.
	 */
	private long blowpipeShotValue(ItemContainer equipment)
	{
		double value = itemManager.getItemPrice(ItemID.ZULRAHS_SCALES) * (2.0 / 3);
		if (dartItemId != -1)
		{
			value += itemManager.getItemPrice(dartItemId) * dartLossRate(equipment);
		}
		else if (!dartHintShown)
		{
			dartHintShown = true;
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Ka-Ching: right-click Check your blowpipe so your darts can be priced.", null);
		}
		return Math.round(value);
	}

	private double dartLossRate(ItemContainer equipment)
	{
		KaChingConfig.AvasDevice device = config.avasDevice();
		if (device != KaChingConfig.AvasDevice.AUTO_DETECT)
		{
			return device.dartLossRate;
		}
		Item cape = equipment.getItem(EquipmentInventorySlot.CAPE.getSlotIdx());
		if (cape != null)
		{
			Double rate = AVAS_CAPES.get(cape.getId());
			if (rate != null)
			{
				return rate;
			}
		}
		return 1.0;
	}

	/**
	 * Cannon ammo is server-synced (varp 3), so a decrease is ground truth for
	 * shots fired. Picking the cannon up refunds balls to the inventory (or
	 * ground) the same tick; those are forgiven like unequipped ammo. Loading is
	 * a varp increase, used to note which ball type is in the magazine.
	 */
	private long cannonValue(Map<Integer, Integer> curInv)
	{
		int cur = client.getVarpValue(VarPlayer.CANNON_AMMO);
		int prev = prevCannonAmmo;
		prevCannonAmmo = cur;
		if (prev == -1)
		{
			return 0;
		}

		int delta = prev - cur;
		if (delta < 0)
		{
			for (int ballId : CANNONBALL_IDS)
			{
				if (prevInv.getOrDefault(ballId, 0) > curInv.getOrDefault(ballId, 0))
				{
					cannonBallItemId = ballId;
				}
			}
			return 0;
		}

		int forgiven = 0;
		for (int ballId : CANNONBALL_IDS)
		{
			forgiven += Math.max(0, curInv.getOrDefault(ballId, 0) - prevInv.getOrDefault(ballId, 0));
			forgiven += takeFromGround(ballId, delta - forgiven);
		}
		int shots = delta - forgiven;
		return shots > 0 ? (long) shots * priceOf(cannonBallItemId) : 0;
	}

	private int priceOf(int itemId)
	{
		// Coins aren't GE-tradeable; 1 gp is 1 gp
		return itemId == ChargedWeapon.COINS ? 1 : itemManager.getItemPrice(itemId);
	}

	private void kaching(long value)
	{
		overlay.add(value);
		if (config.playSound() && config.soundVolume() > 0)
		{
			client.playSoundEffect(SoundEffectID.GE_COIN_TINKLE, config.soundVolume());
		}
	}

	/** Consume up to {@code wanted} of an item from this tick's ground spawns; returns how many were taken. */
	private int takeFromGround(int itemId, int wanted)
	{
		int available = groundThisTick.getOrDefault(itemId, 0);
		if (available <= 0)
		{
			return 0;
		}
		int taken = Math.min(available, wanted);
		groundThisTick.put(itemId, available - taken);
		return taken;
	}

	private Map<Integer, Integer> readInventory()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item.getId() != -1)
				{
					counts.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return counts;
	}

	private Map<Integer, Integer> readRunePouch()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		EnumComposition runeEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		for (int i = 0; i < POUCH_RUNE_VARBITS.length; i++)
		{
			int runeVal = client.getVarbitValue(POUCH_RUNE_VARBITS[i]);
			if (runeVal <= 0)
			{
				continue;
			}
			int itemId = runeEnum.getIntValue(runeVal);
			if (itemId != -1)
			{
				counts.merge(itemId, client.getVarbitValue(POUCH_AMOUNT_VARBITS[i]), Integer::sum);
			}
		}
		return counts;
	}

	private boolean isBusyInterfaceOpen()
	{
		for (int group : BUSY_INTERFACE_GROUPS)
		{
			if (client.getWidget(group, 0) != null)
			{
				return true;
			}
		}
		return false;
	}
}
