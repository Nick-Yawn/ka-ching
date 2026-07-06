package com.kaching;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;

/**
 * Weapons that consume internal charges per attack, invisible to item containers.
 * Detection is per-attack: either one AnimationChanged event per attack (4/5-tick
 * weapons whose attack animation finishes between attacks), or — for the blowpipe,
 * whose animation outlasts its 2-tick rapid cooldown and never re-fires the event —
 * the animation frame resetting to 0 on a game tick.
 *
 * Gameval naming glossary: TOTS = trident of the seas, TOXIC_TOTS = trident of the
 * swamp, _I = enhanced (e), _ORN = ornament kit, plain TOTS = the "(full)" variant,
 * SNAKEBOSS_SCALE = zulrah's scale, ROTTEN_SCYTHE = the in-raid Scythe of Vitur.
 */
enum ChargedWeapon
{
	TRIDENT_OF_THE_SEAS(
		ids(ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_I_CHARGED,
			ItemID.TOTS_ORN, ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_I_CHARGED_ORN),
		ids(AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE),
		false,
		new ChargeCost(ItemID.DEATHRUNE, 1), new ChargeCost(ItemID.CHAOSRUNE, 1),
		new ChargeCost(ItemID.FIRERUNE, 5), new ChargeCost(ItemID.COINS, 10)),

	TRIDENT_OF_THE_SWAMP(
		ids(ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_I_CHARGED,
			ItemID.TOXIC_TOTS_CHARGED_ORN, ItemID.TOXIC_TOTS_I_CHARGED_ORN),
		ids(AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE),
		false,
		new ChargeCost(ItemID.DEATHRUNE, 1), new ChargeCost(ItemID.CHAOSRUNE, 1),
		new ChargeCost(ItemID.FIRERUNE, 5), new ChargeCost(ItemID.SNAKEBOSS_SCALE, 1)),

	SANGUINESTI_STAFF(
		ids(ItemID.SANGUINESTI_STAFF, ItemID.SANGUINESTI_STAFF_OR),
		ids(AnimationID.HUMAN_CASTWAVE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE),
		false,
		new ChargeCost(ItemID.BLOODRUNE, 3)),

	TUMEKENS_SHADOW(
		ids(ItemID.TUMEKENS_SHADOW),
		ids(AnimationID.TOA_SOT_CAST_A, AnimationID.TOA_SOT_CAST_B),
		false,
		new ChargeCost(ItemID.SOULRUNE, 2), new ChargeCost(ItemID.CHAOSRUNE, 5)),

	WARPED_SCEPTRE(
		ids(ItemID.WARPED_SCEPTRE),
		ids(AnimationID.VFX_WARPED_SCEPTRE_CAST, AnimationID.POG_WARPED_SCEPTRE_ATTACK),
		false,
		new ChargeCost(ItemID.CHAOSRUNE, 2), new ChargeCost(ItemID.EARTHRUNE, 5)),

	// 10,000 attacks (misses included) consume the abyssal whip inside;
	// the kraken tentacle itself survives
	ABYSSAL_TENTACLE(
		ids(ItemID.ABYSSAL_TENTACLE),
		ids(AnimationID.SLAYER_ABYSSAL_WHIP_ATTACK),
		false,
		new ChargeCost(ItemID.ABYSSAL_WHIP, 1.0 / 10_000)),

	// Hit-gated: a swing only consumes a charge when at least one hit deals
	// damage. 100 charges = 200 blood runes + 1 vial of blood (post Jan 2024).
	// Uncharged variants consume nothing and are deliberately absent.
	SCYTHE_OF_VITUR(
		ids(ItemID.SCYTHE_OF_VITUR, ItemID.ROTTEN_SCYTHE,
			ItemID.SCYTHE_OF_VITUR_OR, ItemID.SCYTHE_OF_VITUR_BL),
		ids(AnimationID.SCYTHE_OF_VITUR_ATTACK),
		false,
		new ChargeCost(ItemID.BLOODRUNE, 2), new ChargeCost(ItemID.VIAL_BLOOD, 1.0 / 100)),

	// Costs are dynamic (dart type + Ava's device); priced in the plugin
	TOXIC_BLOWPIPE(
		ids(ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT),
		ids(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK_ORNAMENT),
		true);

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
