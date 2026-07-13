package com.kaching;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.QuantityFormatter;

/**
 * XP-drop style floating text: rises above the player's head and fades out.
 */
@Singleton
public class KaChingOverlay extends Overlay
{
	private static final int DURATION_MS = 2600;
	// Fast enough that drops born on consecutive game ticks (600ms) are separated
	// by more than a line of text — age alone keeps simultaneous pops legible
	private static final int RISE_PX = 100;
	private static final Color COIN_GOLD = new Color(255, 215, 0);

	// Death loss: rolls up into place over 3s, hangs overhead for 60s, then
	// rolls away upward over 3s, fading symmetrically at both ends
	private static final int DEATH_SCROLL_MS = 3000;
	private static final int DEATH_HOLD_MS = 60_000;
	private static final Color LOSS_RED = Color.RED;

	private final Client client;
	private final List<Drop> drops = new ArrayList<>();
	// Reference swap is the only mutation, so volatile suffices across threads
	private volatile Drop deathDrop;

	private static class Drop
	{
		final String text;
		// Captured at spawn: getLogicalHeight() tracks the current animation
		// pose, so reading it live makes the text bob with attack animations
		final int zOffset;
		final long startMs = System.currentTimeMillis();

		Drop(String text, int zOffset)
		{
			this.text = text;
			this.zOffset = zOffset;
		}
	}

	@Inject
	KaChingOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		// Overhead prayer/skull icons are drawn by the client on top of the
		// ABOVE_SCENE layer, so the popup has to sit on ABOVE_WIDGETS to render
		// in front of them
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void add(long value)
	{
		Player player = client.getLocalPlayer();
		int zOffset = (player != null ? player.getLogicalHeight() : 220) + 40;
		synchronized (drops)
		{
			drops.add(new Drop("-" + QuantityFormatter.formatNumber(value) + " gp", zOffset));
		}
	}

	/** A new death replaces any loss still hanging from a previous one. */
	void showDeathLoss(long value)
	{
		Player player = client.getLocalPlayer();
		int zOffset = (player != null ? player.getLogicalHeight() : 220) + 40;
		deathDrop = new Drop("-" + QuantityFormatter.formatNumber(value) + " gp", zOffset);
	}

	void clear()
	{
		deathDrop = null;
		synchronized (drops)
		{
			drops.clear();
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			clear();
			return null;
		}

		graphics.setFont(FontManager.getRunescapeBoldFont());
		long now = System.currentTimeMillis();

		synchronized (drops)
		{
			for (Iterator<Drop> it = drops.iterator(); it.hasNext(); )
			{
				Drop drop = it.next();
				float progress = (now - drop.startMs) / (float) DURATION_MS;
				if (progress >= 1f)
				{
					it.remove();
					continue;
				}

				Point base = Perspective.getCanvasTextLocation(
					client, graphics, player.getLocalLocation(), drop.text,
					drop.zOffset);
				if (base == null)
				{
					continue;
				}

				int x = base.getX();
				int y = base.getY() - (int) (progress * RISE_PX);
				// Hold full opacity for the first half, then fade
				int alpha = progress < 0.5f ? 255 : (int) (255 * (1f - (progress - 0.5f) * 2f));

				graphics.setColor(new Color(0, 0, 0, alpha));
				graphics.drawString(drop.text, x + 1, y + 1);
				graphics.setColor(new Color(COIN_GOLD.getRed(), COIN_GOLD.getGreen(), COIN_GOLD.getBlue(), alpha));
				graphics.drawString(drop.text, x, y);
			}
		}

		renderDeathLoss(graphics, player, now);
		return null;
	}

	private void renderDeathLoss(Graphics2D graphics, Player player, long now)
	{
		Drop death = deathDrop;
		if (death == null)
		{
			return;
		}
		long elapsed = now - death.startMs;
		if (elapsed >= (long) DEATH_SCROLL_MS + DEATH_HOLD_MS + DEATH_SCROLL_MS)
		{
			deathDrop = null;
			return;
		}

		// in/out each ramp 0->1 across their own 3s scroll window
		float in = Math.min(1f, elapsed / (float) DEATH_SCROLL_MS);
		float out = Math.max(0f, (elapsed - DEATH_SCROLL_MS - DEATH_HOLD_MS) / (float) DEATH_SCROLL_MS);

		Point base = Perspective.getCanvasTextLocation(
			client, graphics, player.getLocalLocation(), death.text, death.zOffset);
		if (base == null)
		{
			return;
		}

		int x = base.getX();
		// Rolls up from below into place, holds, then keeps rolling up and away
		int y = base.getY() + (int) ((1f - in) * RISE_PX) - (int) (out * RISE_PX);
		int alpha = (int) (255 * Math.min(in, 1f - out));

		graphics.setColor(new Color(0, 0, 0, alpha));
		graphics.drawString(death.text, x + 1, y + 1);
		graphics.setColor(new Color(LOSS_RED.getRed(), LOSS_RED.getGreen(), LOSS_RED.getBlue(), alpha));
		graphics.drawString(death.text, x, y);
	}
}
