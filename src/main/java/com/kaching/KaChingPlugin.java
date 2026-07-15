package com.kaching;

import com.google.inject.Provides;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.SoundEffectID;
import net.runelite.api.TileItem;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Ka-Ching!",
	description = "Shows the GE price of every spell cast, broken ammo, weapon charge, cannonball, bite, sip and buried bone — plus what a death cost you — with a coin jingle",
	tags = {"kaching", "meme", "money", "cost", "gp", "price", "ge", "sound", "jingle", "ammo", "runes", "blowpipe", "cannon", "food", "potions", "bones", "death"}
)
public class KaChingPlugin extends Plugin
{
	// Ammo leaves the equipment slot when fired, but only hits the ground when
	// the projectile lands. Hold losses this many ticks before declaring them broken.
	private static final int AMMO_GRACE_TICKS = 5;
	// After dying, gear scatters everywhere; don't count anything for a while.
	private static final int DEATH_COOLDOWN_TICKS = 10;
	// Deposits/trades are processed by the server a tick after the interface
	// closes client-side; keep suppressing briefly so they don't count as casts.
	private static final int INTERFACE_GRACE_TICKS = 2;
	// A region reload re-fires ItemSpawned for every ground item in the scene.
	private static final int LOADING_GRACE_TICKS = 1;
	// Bulk rune removals that exactly match at least this many charges of a
	// weapon recipe are recharges even when the chat message isn't recognized —
	// no spell consumes five charges' worth of a recipe in one tick.
	private static final int MIN_RECHARGE_CHARGES = 5;
	// An Eat/Drink click should consume its item within this many ticks
	private static final int CONSUME_GRACE_TICKS = 2;
	// Respawn restores hitpoints from 0 back to full and strips the gravestone on
	// the same tick. If that never happens within this window after death it was a
	// safe death (LMS, Inferno, poh dungeon) — everything was kept, stay silent.
	private static final int DEATH_LOSS_TIMEOUT_TICKS = 30;
	// After respawn the strip lands on the respawn tick (occasionally a tick or
	// two later). Watch this many ticks for the carried value to fall; if it never
	// does, nothing was taken.
	private static final int DEATH_STRIP_SETTLE_TICKS = 3;

	// "Prayer potion(3)" -> a sip costs a third of the (3) price
	private static final Pattern DOSE_SUFFIX = Pattern.compile("\\((\\d)\\)$");

	// Multi-bite foods leave a partial item: "Cake" -> "2/3 cake", "Plain pizza"
	// -> "1/2 plain pizza", "Meat pie" -> "Half a meat pie". Exact prefix+base
	// matching only — an unrelated item arriving mid-bite can't masquerade as
	// residue. The value is the bite fraction: a "2/3" residue means a 3-bite
	// food, so the bite consumed a third.
	private static final Map<String, Double> PARTIAL_FOOD_PREFIXES = Map.of(
		"1/2 ", 0.5,
		"Half a ", 0.5,
		"Half an ", 0.5,
		"2/3 ", 1.0 / 3,
		"Slice of ", 1.0 / 3
	);

	private static final Set<Integer> RUNE_IDS = Set.of(
		ItemID.AIRRUNE, ItemID.WATERRUNE, ItemID.EARTHRUNE, ItemID.FIRERUNE,
		ItemID.MINDRUNE, ItemID.BODYRUNE, ItemID.COSMICRUNE, ItemID.CHAOSRUNE,
		ItemID.NATURERUNE, ItemID.LAWRUNE, ItemID.DEATHRUNE, ItemID.ASTRALRUNE,
		ItemID.BLOODRUNE, ItemID.SOULRUNE, ItemID.WRATHRUNE, ItemID.SUNFIRERUNE,
		ItemID.MISTRUNE, ItemID.DUSTRUNE, ItemID.MUDRUNE,
		ItemID.SMOKERUNE, ItemID.STEAMRUNE, ItemID.LAVARUNE
	);

	// Interfaces where items legitimately leave the inventory/equipment without being consumed
	private static final int[] BUSY_INTERFACE_GROUPS = {
		InterfaceID.BANKMAIN,
		InterfaceID.BANK_DEPOSITBOX,
		InterfaceID.SHOPMAIN,
		InterfaceID.TRADECONFIRM,
		InterfaceID.TRADEMAIN,
		InterfaceID.GE_COLLECT,
		InterfaceID.GE_OFFERS,
	};

	private static final int[] POUCH_RUNE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6,
	};
	private static final int[] POUCH_AMOUNT_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6,
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

	// Cape-slot item -> fraction of darts lost per blowpipe shot (1 - recovery rate).
	// The ranging cape gives the accumulator effect (the Vorkath's-head upgrade to
	// assembler rates isn't visible client-side; the config override covers it).
	private static final Map<Integer, Double> AVAS_CAPES = Map.ofEntries(
		Map.entry(ItemID.ANMA_30_REWARD, 0.4),          // Ava's attractor
		Map.entry(ItemID.ANMA_50_REWARD, 0.28),         // Ava's accumulator
		Map.entry(ItemID.SKILLCAPE_MAX_ANMA, 0.28),     // Accumulator max cape
		Map.entry(ItemID.SKILLCAPE_RANGING, 0.28),
		Map.entry(ItemID.SKILLCAPE_RANGING_TRIMMED, 0.28),
		Map.entry(ItemID.AVAS_ASSEMBLER, 0.2),
		Map.entry(ItemID.SKILLCAPE_MAX_ASSEMBLER, 0.2),
		Map.entry(ItemID.AVAS_ASSEMBLER_MASORI, 0.2),
		Map.entry(ItemID.SKILLCAPE_MAX_ASSEMBLER_MASORI, 0.2),
		Map.entry(ItemID.DIZANAS_QUIVER_INFINITE, 0.2), // blessed quiver
		Map.entry(ItemID.SKILLCAPE_MAX_DIZANAS, 0.2)
	);

	private static final int[] CANNONBALL_IDS = {ItemID.MCANNONBALL, ItemID.GRANITE_CANNONBALL};

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

	private Map<Integer, Integer> prevInv = new HashMap<>();
	private Map<Integer, Integer> prevPouch = new HashMap<>();
	private final Map<Integer, Integer> groundThisTick = new HashMap<>();
	private final List<PendingAmmo> pendingAmmo = new ArrayList<>();
	private final List<PendingConsume> pendingConsumes = new ArrayList<>();
	private final int[] prevSlotIds = {-1, -1};
	private final int[] prevSlotQtys = {0, 0};
	private boolean synced;
	private int deathCooldown;
	private int deathLossTick = -1;
	private boolean deathRespawned;
	private int deathRespawnTick = -1;
	private long preStripValue;
	private long prevCarriedValue;
	private int lastBusyTick = -1;
	private int lastLoadingTick = -1;
	private int lastAnimationId = -1;
	private int lastAnimationTick = -1;
	private int suppressRunesUntilTick = -1;
	private int prevCannonAmmo = -1;
	// deliberately survives reset(): the cannon and its load outlive hops/logouts
	private int cannonBallItemId = ItemID.MCANNONBALL;
	private int dartItemId = -1;
	private boolean dartHintShown;
	private boolean dealtDamageThisTick;

	private static class PendingAmmo
	{
		final int itemId;
		int quantity;
		int expiryTick;

		PendingAmmo(int itemId, int quantity, int expiryTick)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.expiryTick = expiryTick;
		}
	}

	private static class PendingConsume
	{
		final int itemId;
		final int expiryTick;

		PendingConsume(int itemId, int expiryTick)
		{
			this.itemId = itemId;
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
		String dart = configManager.getConfiguration(KaChingConfig.GROUP, "dartType");
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
		prevInv = new HashMap<>();
		prevPouch = new HashMap<>();
		groundThisTick.clear();
		pendingAmmo.clear();
		prevSlotIds[0] = prevSlotIds[1] = -1;
		prevSlotQtys[0] = prevSlotQtys[1] = 0;
		synced = false;
		deathCooldown = 0;
		deathLossTick = -1;
		deathRespawned = false;
		deathRespawnTick = -1;
		preStripValue = 0;
		prevCarriedValue = 0;
		lastBusyTick = -1;
		lastLoadingTick = -1;
		lastAnimationId = -1;
		lastAnimationTick = -1;
		suppressRunesUntilTick = -1;
		prevCannonAmmo = -1;
		dealtDamageThisTick = false;
		pendingConsumes.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGIN_SCREEN:
			case HOPPING:
				overlay.clear();
				reset();
				break;
			case LOADING:
				lastLoadingTick = client.getTickCount();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			deathCooldown = DEATH_COOLDOWN_TICKS;
			pendingAmmo.clear();
			deathLossTick = client.getTickCount();
			deathRespawned = false;
			deathRespawnTick = -1;
			// Fallback base until the first dead tick refines it (see maybeShowDeathLoss)
			preStripValue = prevCarriedValue;
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
				configManager.setConfiguration(KaChingConfig.GROUP, "dartType", dartName);
			}
		}
	}

	/**
	 * Dev trigger: typing "::kcdeath 12345678" in game chat rolls in a fake
	 * death loss at that value, so each tier's color, hold time and window
	 * masking can be observed in-client without dying.
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"kcdeath".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		String[] args = event.getArguments();
		try
		{
			showDeathLoss(args.length > 0 ? Long.parseLong(args[0].replace(",", "")) : 1_000_000L);
		}
		catch (NumberFormatException e)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Ka-Ching: usage ::kcdeath <gp value>", null);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.trackConsumables() || !event.isItemOp())
		{
			return;
		}
		String option = event.getMenuOption();
		if ("Eat".equals(option) || "Drink".equals(option) || "Bury".equals(option))
		{
			pendingConsumes.add(new PendingConsume(event.getItemId(), client.getTickCount() + CONSUME_GRACE_TICKS));
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		recordGroundSpawn(event.getItem(), event.getItem().getQuantity());
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{
		int delta = event.getNewQuantity() - event.getOldQuantity();
		if (delta > 0)
		{
			recordGroundSpawn(event.getItem(), delta);
		}
	}

	/**
	 * Only the player's own drops and projectiles forgive consumption — another
	 * player's identical litter appearing nearby shouldn't cancel our accounting.
	 */
	private void recordGroundSpawn(TileItem item, int quantity)
	{
		int ownership = item.getOwnership();
		if (ownership == TileItem.OWNERSHIP_SELF || ownership == TileItem.OWNERSHIP_GROUP)
		{
			groundThisTick.merge(item.getId(), quantity, Integer::sum);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tickEvent)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			groundThisTick.clear();
			dealtDamageThisTick = false;
			return;
		}

		int tick = client.getTickCount();
		if (isBusyInterfaceOpen())
		{
			lastBusyTick = tick;
		}

		Map<Integer, Integer> curInv = readInventory();
		Map<Integer, Integer> curPouch = readRunePouch();

		boolean counting = synced
			&& deathCooldown == 0
			&& tick > lastBusyTick + INTERFACE_GRACE_TICKS
			&& tick > lastLoadingTick + LOADING_GRACE_TICKS;
		if (deathCooldown > 0)
		{
			deathCooldown--;
		}

		long value = 0;

		if (counting && config.trackSpells() && tick > suppressRunesUntilTick)
		{
			value += runesConsumedValue(curInv, curPouch);
		}

		value += trackAmmo(curInv, counting);

		if (counting && config.trackChargedWeapons())
		{
			value += chargedWeaponValue();
		}

		value += cannonValue(curInv);
		value += consumablesValue(curInv);

		if (value >= Math.max(1, config.minValue()))
		{
			kaching(value);
		}

		maybeShowDeathLoss(tick);
		prevCarriedValue = carriedValue();

		prevInv = curInv;
		prevPouch = curPouch;
		groundThisTick.clear();
		dealtDamageThisTick = false;
		synced = true;
	}

	/**
	 * Runes that vanished from inventory + rune pouch this tick, minus any that
	 * hit the ground (dropped, not cast), priced at GE value. Bulk removals
	 * shaped exactly like a charged weapon's recipe are recharges, not casts.
	 */
	private long runesConsumedValue(Map<Integer, Integer> curInv, Map<Integer, Integer> curPouch)
	{
		Map<Integer, Integer> decreases = new HashMap<>();
		for (int runeId : RUNE_IDS)
		{
			int prev = prevInv.getOrDefault(runeId, 0) + prevPouch.getOrDefault(runeId, 0);
			int cur = curInv.getOrDefault(runeId, 0) + curPouch.getOrDefault(runeId, 0);
			if (prev > cur)
			{
				decreases.put(runeId, prev - cur);
			}
		}
		if (decreases.isEmpty() || looksLikeRecharge(decreases))
		{
			return 0;
		}

		long value = 0;
		for (Map.Entry<Integer, Integer> entry : decreases.entrySet())
		{
			int consumed = entry.getValue() - takeFromGround(entry.getKey(), entry.getValue());
			if (consumed > 0)
			{
				value += (long) itemManager.getItemPrice(entry.getKey()) * consumed;
			}
		}
		return value;
	}

	private boolean looksLikeRecharge(Map<Integer, Integer> decreases)
	{
		for (ChargedWeapon weapon : ChargedWeapon.values())
		{
			Map<Integer, Integer> recipe = new HashMap<>();
			for (ChargedWeapon.ChargeCost cost : weapon.costs)
			{
				if (RUNE_IDS.contains(cost.itemId) && cost.quantity >= 1)
				{
					recipe.put(cost.itemId, (int) cost.quantity);
				}
			}
			if (recipe.isEmpty() || !decreases.keySet().equals(recipe.keySet()))
			{
				continue;
			}

			int charges = -1;
			boolean match = true;
			for (Map.Entry<Integer, Integer> entry : recipe.entrySet())
			{
				int decrease = decreases.get(entry.getKey());
				if (decrease % entry.getValue() != 0)
				{
					match = false;
					break;
				}
				int multiple = decrease / entry.getValue();
				if (charges == -1)
				{
					charges = multiple;
				}
				else if (charges != multiple)
				{
					match = false;
					break;
				}
			}
			if (match && charges >= MIN_RECHARGE_CHARGES)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Ammo accounting. Equipment slot decreases become pending losses; pending
	 * losses are forgiven if the ammo shows up on the ground (recoverable drop)
	 * within the grace window, and cashed in as broken once the window expires.
	 * Ava's saves never decrement the slot, so they never enter the pipeline.
	 * Slot snapshots are maintained even when the toggle is off or counting is
	 * suppressed so that re-enabling can't misread the gap as consumption.
	 */
	private long trackAmmo(Map<Integer, Integer> curInv, boolean counting)
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		boolean track = config.trackAmmo();
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
			if (!track || !counting || prevId == -1 || (id != prevId && id != -1))
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

		if (!track)
		{
			pendingAmmo.clear();
			return 0;
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
			else if (!counting)
			{
				// While suppressed (bank open, region loading) hold pendings open
				// rather than cashing them in with forgiveness data missing
				pending.expiryTick++;
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
	 * Charged weapons (tridents, sang, shadow, sceptre, tentacle, scythe,
	 * blowpipe) burn internal charges invisible to item containers, so attacks
	 * are detected directly: one AnimationChanged per attack for weapons whose
	 * animation ends between attacks, or an animation frame reset for the
	 * blowpipe, whose animation outlasts its rapid-fire cooldown and never
	 * re-fires the event.
	 */
	private long chargedWeaponValue()
	{
		Player player = client.getLocalPlayer();
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
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
		// melee, so its hitsplats land on the same tick as the attack animation.
		// (A thrall's damage on the same tick can satisfy this gate spuriously —
		// hitsplats don't identify their source weapon.)
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
		double value = itemManager.getItemPrice(ItemID.SNAKEBOSS_SCALE) * (2.0 / 3);
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
	 * Cannon ammo is server-synced, so a decrease is ground truth for shots
	 * fired. Only picking the cannon up both empties it and refunds the balls
	 * (to the inventory, or the ground when full), so refund forgiveness only
	 * applies when the count reaches zero — acquiring cannonballs any other way
	 * can't suppress real shots. The varp snapshot is maintained even when the
	 * toggle is off so re-enabling can't fabricate a delta.
	 */
	private long cannonValue(Map<Integer, Integer> curInv)
	{
		int cur = client.getVarpValue(VarPlayerID.ROCKTHROWER); // cannonballs left in your cannon
		int prev = prevCannonAmmo;
		prevCannonAmmo = cur;
		if (prev == -1 || !config.trackCannon())
		{
			return 0;
		}

		int delta = prev - cur;
		if (delta < 0)
		{
			// Loading: note which ball type left the inventory, preferring the
			// larger decrease when both types moved on the same tick
			int best = 0;
			for (int ballId : CANNONBALL_IDS)
			{
				int loaded = prevInv.getOrDefault(ballId, 0) - curInv.getOrDefault(ballId, 0);
				if (loaded > best)
				{
					best = loaded;
					cannonBallItemId = ballId;
				}
			}
			return 0;
		}
		if (delta == 0)
		{
			return 0;
		}

		int forgiven = 0;
		if (cur == 0)
		{
			for (int ballId : CANNONBALL_IDS)
			{
				forgiven += Math.max(0, curInv.getOrDefault(ballId, 0) - prevInv.getOrDefault(ballId, 0));
				forgiven += takeFromGround(ballId, delta - forgiven);
			}
		}
		int shots = delta - forgiven;
		return shots > 0 ? (long) shots * priceOf(cannonBallItemId) : 0;
	}

	/**
	 * Food and potions, gated on an explicit Eat/Drink click so that dropping or
	 * depositing them can never bill — the click marks intent, the inventory
	 * decrease confirms it.
	 */
	private long consumablesValue(Map<Integer, Integer> curInv)
	{
		if (pendingConsumes.isEmpty())
		{
			return 0;
		}
		long value = 0;
		Map<Integer, Integer> billed = new HashMap<>();
		Map<Integer, Integer> increases = null;
		for (Iterator<PendingConsume> it = pendingConsumes.iterator(); it.hasNext(); )
		{
			PendingConsume pending = it.next();
			int decrease = prevInv.getOrDefault(pending.itemId, 0) - curInv.getOrDefault(pending.itemId, 0);
			int alreadyBilled = billed.getOrDefault(pending.itemId, 0);
			if (decrease > alreadyBilled)
			{
				billed.put(pending.itemId, alreadyBilled + 1);
				if (increases == null)
				{
					increases = inventoryIncreases(curInv);
				}
				value += consumeCost(pending.itemId, increases);
				it.remove();
			}
			else if (client.getTickCount() >= pending.expiryTick)
			{
				it.remove(); // clicked but nothing was consumed
			}
		}
		return value;
	}

	/**
	 * A sip's true cost is the price step down to what you're left holding:
	 * price(Prayer potion(4)) - price(Prayer potion(3)) — dose prices are not
	 * linear on the GE — and the last dose credits the empty vial you keep. The
	 * lower-dose potion appears in the inventory on the tick of the sip and is
	 * identified by exact name. Falls back to price/doses when it can't be
	 * found or has no price; plain food bills full price.
	 */
	private long consumeCost(int itemId, Map<Integer, Integer> increases)
	{
		int price = itemManager.getItemPrice(itemId);
		String name = itemManager.getItemComposition(itemId).getName();
		Matcher doseMatcher = DOSE_SUFFIX.matcher(name);
		if (!doseMatcher.find())
		{
			return foodBiteCost(price, name, increases);
		}
		int doses = Math.max(1, Integer.parseInt(doseMatcher.group(1)));

		int residuePrice = -1;
		if (doses == 1)
		{
			// Credit the vial only if one actually appeared — with vial smashing
			// unlocked, the glassware is destroyed and its value truly lost
			if (increases.containsKey(ItemID.VIAL_EMPTY))
			{
				residuePrice = itemManager.getItemPrice(ItemID.VIAL_EMPTY);
			}
		}
		else
		{
			String expected = name.substring(0, doseMatcher.start()) + "(" + (doses - 1) + ")";
			for (int increasedId : increases.keySet())
			{
				if (expected.equals(itemManager.getItemComposition(increasedId).getName()))
				{
					residuePrice = itemManager.getItemPrice(increasedId);
					break;
				}
			}
		}
		if (residuePrice > 0)
		{
			return Math.max(0, price - residuePrice);
		}
		return Math.round((double) price / doses);
	}

	/**
	 * Multi-bite foods: the partial residue is usually NOT tradeable on the GE,
	 * so the price differential only applies when it has a real price. Otherwise
	 * the matched prefix gives the bite fraction to pro-rate by, and eating a
	 * partial itself prices the bite off the base item, found by exact name.
	 * Container residues (jugs, bowls, pie dishes, beer glasses) are
	 * deliberately never credited: this is a negative-kaching plugin.
	 */
	private long foodBiteCost(int price, String name, Map<Integer, Integer> increases)
	{
		// Eating a whole item that left a partial behind
		for (int increasedId : increases.keySet())
		{
			String residueName = itemManager.getItemComposition(increasedId).getName();
			for (Map.Entry<String, Double> prefix : PARTIAL_FOOD_PREFIXES.entrySet())
			{
				if (residueName.equalsIgnoreCase(prefix.getKey() + name))
				{
					int residuePrice = itemManager.getItemPrice(increasedId);
					if (residuePrice > 0)
					{
						return Math.max(0, price - residuePrice);
					}
					return Math.round(price * prefix.getValue());
				}
			}
		}
		// Eating an untradeable partial: price the bite off its base item
		if (price <= 0)
		{
			for (Map.Entry<String, Double> prefix : PARTIAL_FOOD_PREFIXES.entrySet())
			{
				String prefixText = prefix.getKey();
				if (name.regionMatches(true, 0, prefixText, 0, prefixText.length()))
				{
					int basePrice = exactPriceByName(name.substring(prefixText.length()));
					if (basePrice > 0)
					{
						return Math.round(basePrice * prefix.getValue());
					}
				}
			}
			// "Chocolate slice" -> a third of a chocolate cake (irregular name)
			if (name.toLowerCase().endsWith(" slice"))
			{
				int basePrice = exactPriceByName(name.substring(0, name.length() - " slice".length()) + " cake");
				if (basePrice > 0)
				{
					return Math.round(basePrice / 3.0);
				}
			}
		}
		return price;
	}

	private int exactPriceByName(String name)
	{
		for (ItemPrice result : itemManager.search(name))
		{
			if (name.equalsIgnoreCase(result.getName()))
			{
				return itemManager.getItemPrice(result.getId());
			}
		}
		return -1;
	}

	private Map<Integer, Integer> inventoryIncreases(Map<Integer, Integer> curInv)
	{
		Map<Integer, Integer> increases = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : curInv.entrySet())
		{
			int gained = entry.getValue() - prevInv.getOrDefault(entry.getKey(), 0);
			if (gained > 0)
			{
				increases.put(entry.getKey(), gained);
			}
		}
		return increases;
	}

	private int priceOf(int itemId)
	{
		// Coins aren't GE-tradeable; 1 gp is 1 gp
		return itemId == ItemID.COINS ? 1 : itemManager.getItemPrice(itemId);
	}

	/**
	 * Death losses are what the gravestone took: the fall in carried value across the
	 * respawn. The gravestone strips items on the tick the player respawns — never
	 * during the death animation — so the loss is measured against the respawn, found
	 * by hitpoints climbing off zero. Anything consumed while dying (a last-ditch bite
	 * or sip) leaves the containers before then, so the pre-strip baseline keeps
	 * tracking down to the real carried value and never counts it. Kept-on-death items
	 * never leave, so a safe death (LMS, Inferno, poh dungeon) shows no drop and stays
	 * silent; a bank opened after death empties slots too, so it cancels the pending
	 * check instead.
	 */
	private void maybeShowDeathLoss(int tick)
	{
		if (deathLossTick == -1)
		{
			return;
		}
		if (lastBusyTick >= deathLossTick)
		{
			clearDeathLoss();
			return;
		}

		if (!deathRespawned)
		{
			if (client.getBoostedSkillLevel(Skill.HITPOINTS) > 0)
			{
				// Respawned: freeze the pre-strip baseline and start watching for the strip
				deathRespawned = true;
				deathRespawnTick = tick;
			}
			else
			{
				// Still in the death animation: the containers hold the pre-strip loadout,
				// less anything consumed while dying — track it as the baseline
				preStripValue = carriedValue();
				if (tick >= deathLossTick + DEATH_LOSS_TIMEOUT_TICKS)
				{
					clearDeathLoss(); // never respawned (disconnect?) — give up
				}
				return;
			}
		}

		long loss = preStripValue - carriedValue();
		if (loss > 0)
		{
			if (config.trackDeathLoss())
			{
				showDeathLoss(loss);
			}
			clearDeathLoss();
		}
		else if (tick >= deathRespawnTick + DEATH_STRIP_SETTLE_TICKS)
		{
			clearDeathLoss(); // respawned but nothing left — safe death, stay silent
		}
	}

	/**
	 * Tier styling (color and hold time) is read from config here, at the moment
	 * the loss lands, so settings changes apply from the next death on. The
	 * higher tier owns each boundary: a loss equal to a threshold rounds up.
	 */
	private void showDeathLoss(long value)
	{
		Color color;
		int holdSeconds;
		if (value >= config.deathHighThreshold())
		{
			color = config.deathHighColor();
			holdSeconds = config.deathHighHold();
		}
		else if (value >= config.deathMediumThreshold())
		{
			color = config.deathMediumColor();
			holdSeconds = config.deathMediumHold();
		}
		else
		{
			color = config.deathLowColor();
			holdSeconds = config.deathLowHold();
		}
		overlay.showDeathLoss(value, color, holdSeconds * 1000);
	}

	private void clearDeathLoss()
	{
		deathLossTick = -1;
		deathRespawned = false;
		deathRespawnTick = -1;
		preStripValue = 0;
	}

	private long carriedValue()
	{
		return containerValue(client.getItemContainer(InventoryID.INV))
			+ containerValue(client.getItemContainer(InventoryID.WORN));
	}

	private long containerValue(ItemContainer container)
	{
		if (container == null)
		{
			return 0;
		}
		long value = 0;
		for (Item item : container.getItems())
		{
			if (item.getId() != -1)
			{
				value += (long) priceOf(item.getId()) * item.getQuantity();
			}
		}
		return value;
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
		if (wanted <= 0)
		{
			return 0;
		}
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
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
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
