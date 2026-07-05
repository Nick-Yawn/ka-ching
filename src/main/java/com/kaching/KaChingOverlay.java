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
	private static final int RISE_PX = 60;
	private static final Color COIN_GOLD = new Color(255, 215, 0);

	private final Client client;
	private final List<Drop> drops = new ArrayList<>();

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
		setLayer(OverlayLayer.ABOVE_SCENE);
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

	void clear()
	{
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
			int stack = 0;
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
				int y = base.getY() - (int) (progress * RISE_PX) - stack * 16;
				// Hold full opacity for the first half, then fade
				int alpha = progress < 0.5f ? 255 : (int) (255 * (1f - (progress - 0.5f) * 2f));

				graphics.setColor(new Color(0, 0, 0, alpha));
				graphics.drawString(drop.text, x + 1, y + 1);
				graphics.setColor(new Color(COIN_GOLD.getRed(), COIN_GOLD.getGreen(), COIN_GOLD.getBlue(), alpha));
				graphics.drawString(drop.text, x, y);
				stack++;
			}
		}
		return null;
	}
}
