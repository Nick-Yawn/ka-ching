package com.kaching;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(KaChingConfig.GROUP)
public interface KaChingConfig extends Config
{
	String GROUP = "kaching";

	@ConfigItem(
		keyName = "trackSpells",
		name = "Track spell casts",
		description = "Show the GE price of runes consumed by each cast",
		position = 0
	)
	default boolean trackSpells()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackAmmo",
		name = "Track ranged ammo",
		description = "Show the GE price of ammo that breaks (not saved by Ava's, not recoverable from the ground)",
		position = 1
	)
	default boolean trackAmmo()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackChargedWeapons",
		name = "Track charged weapons",
		description = "Show the per-attack cost of tridents, sanguinesti staff, Tumeken's shadow, warped sceptre, abyssal tentacle, Scythe of Vitur and toxic blowpipe",
		position = 2
	)
	default boolean trackChargedWeapons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackCannon",
		name = "Track cannon",
		description = "Show the cost of cannonballs as your dwarf multicannon fires them",
		position = 3
	)
	default boolean trackCannon()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackConsumables",
		name = "Track food, potions & bones",
		description = "Show the GE price of food you eat, potion doses you drink (priced per dose) and bones you bury",
		position = 4
	)
	default boolean trackConsumables()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackDeathLoss",
		name = "Track death losses",
		description = "After you die, show the GE value of everything you lost over your head — color and time scale with the loss (see Death loss tiers)",
		position = 5
	)
	default boolean trackDeathLoss()
	{
		return true;
	}

	@ConfigItem(
		keyName = "avasDevice",
		name = "Ava's device",
		description = "Dart recovery for blowpipe cost estimates. Auto-detect reads your cape slot; override if using an untracked cape",
		position = 6
	)
	default AvasDevice avasDevice()
	{
		return AvasDevice.AUTO_DETECT;
	}

	@ConfigItem(
		keyName = "playSound",
		name = "Coin jingle",
		description = "Play the GE coin jingle when money burns",
		position = 7
	)
	default boolean playSound()
	{
		return true;
	}

	@Range(min = 1, max = 127)
	@ConfigItem(
		keyName = "soundVolume",
		name = "Jingle volume",
		description = "Volume of the coin jingle (1-127)",
		position = 8
	)
	default int soundVolume()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "minValue",
		name = "Minimum value",
		description = "Only ka-ching for at least this many gp",
		position = 9
	)
	default int minValue()
	{
		return 1;
	}

	@ConfigSection(
		name = "Death loss tiers",
		description = "Color and on-screen time of the death loss, by how much the death cost",
		position = 10,
		closedByDefault = false
	)
	String deathTiers = "deathTiers";

	@ConfigItem(
		keyName = "deathLowColor",
		name = "Low tier color",
		description = "Color of the death loss below the medium threshold",
		position = 0,
		section = deathTiers
	)
	default Color deathLowColor()
	{
		return new Color(0xFFCC99);
	}

	@Range(min = 1, max = 600)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "deathLowHold",
		name = "Low tier time",
		description = "How long a low-tier loss stays in view (the 3s roll-in and roll-out are extra)",
		position = 1,
		section = deathTiers
	)
	default int deathLowHold()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "deathMediumColor",
		name = "Medium tier color",
		description = "Color of the death loss from the medium threshold up to the high threshold",
		position = 2,
		section = deathTiers
	)
	default Color deathMediumColor()
	{
		return new Color(0xFF8C00);
	}

	@Range(min = 1, max = 600)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "deathMediumHold",
		name = "Medium tier time",
		description = "How long a medium-tier loss stays in view (the 3s roll-in and roll-out are extra)",
		position = 3,
		section = deathTiers
	)
	default int deathMediumHold()
	{
		return 25;
	}

	@ConfigItem(
		keyName = "deathHighColor",
		name = "High tier color",
		description = "Color of the death loss at or above the high threshold",
		position = 4,
		section = deathTiers
	)
	default Color deathHighColor()
	{
		return new Color(0xFF0000);
	}

	@Range(min = 1, max = 600)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "deathHighHold",
		name = "High tier time",
		description = "How long a high-tier loss stays in view (the 3s roll-in and roll-out are extra)",
		position = 5,
		section = deathTiers
	)
	default int deathHighHold()
	{
		return 45;
	}

	@Range(min = 0, max = Integer.MAX_VALUE)
	@ConfigItem(
		keyName = "deathMediumThreshold",
		name = "Medium threshold",
		description = "A loss of at least this many gp counts as medium tier",
		position = 6,
		section = deathTiers
	)
	default int deathMediumThreshold()
	{
		return 100_000;
	}

	@Range(min = 0, max = Integer.MAX_VALUE)
	@ConfigItem(
		keyName = "deathHighThreshold",
		name = "High threshold",
		description = "A loss of at least this many gp counts as high tier",
		position = 7,
		section = deathTiers
	)
	default int deathHighThreshold()
	{
		return 10_000_000;
	}

	enum AvasDevice
	{
		AUTO_DETECT(-1),
		NONE(1.0),
		ATTRACTOR(0.4),
		ACCUMULATOR(0.28),
		ASSEMBLER(0.2);

		final double dartLossRate;

		AvasDevice(double dartLossRate)
		{
			this.dartLossRate = dartLossRate;
		}
	}
}
