package com.kaching;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ItemID;
import net.runelite.api.gameval.AnimationID;

/**
 * Weapons that consume internal charges per attack, invisible to item containers.
 * Detection is per-attack: either one AnimationChanged event per attack (4/5-tick
 * weapons whose attack animation finishes between attacks), or — for the blowpipe,
 * whose animation outlasts its 2-tick rapid cooldown and never re-fires the event —
 * the animation frame resetting to 0 on a game tick.
 */
enum ChargedWeapon
{
	TRIDENT_OF_THE_SEAS(
		ids(ItemID.TRIDENT_OF_THE_SEAS, ItemID.TRIDENT_OF_THE_SEAS_E),
		ids(AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE),
		false,
		new ChargeCost(ItemID.DEATH_RUNE, 1), new ChargeCost(ItemID.CHAOS_RUNE, 1),
		new ChargeCost(ItemID.FIRE_RUNE, 5), new ChargeCost(ItemID.COINS_995, 10)),

	TRIDENT_OF_THE_SWAMP(
		ids(ItemID.TRIDENT_OF_THE_SWAMP, ItemID.TRIDENT_OF_THE_SWAMP_E),
		ids(AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE),
		false,
		new ChargeCost(ItemID.DEATH_RUNE, 1), new ChargeCost(ItemID.CHAOS_RUNE, 1),
		new ChargeCost(ItemID.FIRE_RUNE, 5), new ChargeCost(ItemID.ZULRAHS_SCALES, 1)),

	SANGUINESTI_STAFF(
		ids(ItemID.SANGUINESTI_STAFF, ItemID.HOLY_SANGUINESTI_STAFF),
		ids(AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE),
		false,
		new ChargeCost(ItemID.BLOOD_RUNE, 3)),

	TUMEKENS_SHADOW(
		ids(ItemID.TUMEKENS_SHADOW),
		ids(AnimationID.TOA_SOT_CAST_A, AnimationID.TOA_SOT_CAST_B),
		false,
		new ChargeCost(ItemID.SOUL_RUNE, 2), new ChargeCost(ItemID.CHAOS_RUNE, 5)),

	WARPED_SCEPTRE(
		ids(ItemID.WARPED_SCEPTRE),
		ids(AnimationID.VFX_WARPED_SCEPTRE_CAST, AnimationID.POG_WARPED_SCEPTRE_ATTACK),
		false,
		new ChargeCost(ItemID.CHAOS_RUNE, 2), new ChargeCost(ItemID.EARTH_RUNE, 5)),

	// 10,000 attacks (misses included) consume the abyssal whip inside;
	// the kraken tentacle itself survives
	ABYSSAL_TENTACLE(
		ids(ItemID.ABYSSAL_TENTACLE),
		ids(AnimationID.SLAYER_ABYSSAL_WHIP_ATTACK),
		false,
		new ChargeCost(ItemID.ABYSSAL_WHIP, 1.0 / 10_000)),

	// Costs are dynamic (dart type + Ava's device); priced in the plugin
	TOXIC_BLOWPIPE(
		ids(ItemID.TOXIC_BLOWPIPE, ItemID.BLAZING_BLOWPIPE),
		ids(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK_ORNAMENT),
		true),

	// Hit-gated: a swing only consumes a charge when at least one hit deals
	// damage. 100 charges = 200 blood runes + 1 vial of blood (post Jan 2024).
	// Uncharged variants consume nothing and are deliberately absent.
	SCYTHE_OF_VITUR(
		ids(ItemID.SCYTHE_OF_VITUR, ItemID.SCYTHE_OF_VITUR_22664,
			ItemID.HOLY_SCYTHE_OF_VITUR, ItemID.SANGUINE_SCYTHE_OF_VITUR),
		ids(AnimationID.SCYTHE_OF_VITUR_ATTACK),
		false,
		new ChargeCost(ItemID.BLOOD_RUNE, 2), new ChargeCost(ItemID.VIAL_OF_BLOOD_22446, 1.0 / 100));

	// Coins aren't GE-tradeable; the plugin prices this id at 1 gp.
	// (Referenced by name here because enum constants can't forward-reference it.)
	static final int COINS = ItemID.COINS_995;

	static class ChargeCost
	{
		final int itemId;
		final double quantity;

		ChargeCost(int itemId, double quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	final int[] weaponIds;
	final int[] animationIds;
	final boolean frameReset;
	final ChargeCost[] costs;

	private static final Map<Integer, ChargedWeapon> BY_WEAPON = new HashMap<>();

	static
	{
		for (ChargedWeapon weapon : values())
		{
			for (int id : weapon.weaponIds)
			{
				BY_WEAPON.put(id, weapon);
			}
		}
	}

	ChargedWeapon(int[] weaponIds, int[] animationIds, boolean frameReset, ChargeCost... costs)
	{
		this.weaponIds = weaponIds;
		this.animationIds = animationIds;
		this.frameReset = frameReset;
		this.costs = costs;
	}

	static ChargedWeapon forWeapon(int itemId)
	{
		return BY_WEAPON.get(itemId);
	}

	boolean hasAnimation(int animationId)
	{
		for (int id : animationIds)
		{
			if (id == animationId)
			{
				return true;
			}
		}
		return false;
	}

	private static int[] ids(int... values)
	{
		return values;
	}
}
