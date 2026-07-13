package com.kaching;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
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

	// Death loss: rolls up into place over 3s, hangs overhead for its tier's
	// configured hold, then rolls away upward over 3s, fading symmetrically at
	// both ends
	private static final int DEATH_SCROLL_MS = 3000;
	// World-space height above the player that anchors the resting loss clear of the
	// head. Projected every frame, so the drop stays fixed relative to the character
	// as the camera moves (vs the gold drops' +40, which sits right at head height).
	private static final int DEATH_HEAD_OFFSET = 120;

	private final Client client;
	private final List<Drop> drops = new ArrayList<>();
	// Reference swap is the only mutation, so volatile suffices across threads
	private volatile DeathDrop deathDrop;

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

	// Tier styling is resolved by the plugin when the death lands, so a config
	// change takes effect on the next death, not the one already on screen
	private static class DeathDrop extends Drop
	{
		final Color color;
		final int holdMs;

		DeathDrop(String text, int zOffset, Color color, int holdMs)
		{
			super(text, zOffset);
			this.color = color;
			this.holdMs = holdMs;
		}
	}

	@Inject
	KaChingOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		// UNDER_WIDGETS renders in drawAboveOverheads(): after the client's
		// overhead prayer/skull icons (so the popup sits in front of them) but
		// before widgets and the right-click menu (so the menu draws over it).
		setLayer(OverlayLayer.UNDER_WIDGETS);
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
	void showDeathLoss(long value, Color color, int holdMs)
	{
		Player player = client.getLocalPlayer();
		int zOffset = (player != null ? player.getLogicalHeight() : 220) + DEATH_HEAD_OFFSET;
		deathDrop = new DeathDrop("-" + QuantityFormatter.formatNumber(value) + " gp", zOffset, color, holdMs);
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
		DeathDrop death = deathDrop;
		if (death == null)
		{
			return;
		}
		long elapsed = now - death.startMs;
		if (elapsed >= (long) DEATH_SCROLL_MS + death.holdMs + DEATH_SCROLL_MS)
		{
			deathDrop = null;
			return;
		}

		if (client.isMenuOpen())
		{
			return; // keep the loss pending, but don't paint over an open menu
		}

		// in/out each ramp 0->1 across their own 3s scroll window
		float in = Math.min(1f, elapsed / (float) DEATH_SCROLL_MS);
		float out = Math.max(0f, (elapsed - DEATH_SCROLL_MS - death.holdMs) / (float) DEATH_SCROLL_MS);

		Point base = Perspective.getCanvasTextLocation(
			client, graphics, player.getLocalLocation(), death.text, death.zOffset);
		if (base == null)
		{
			return;
		}

		// Anchored in world space a fixed height above the head (re-projected each
		// frame), so the resting loss stays put relative to the character as the
		// camera moves.
		FontMetrics fm = graphics.getFontMetrics();
		int band = fm.getHeight(); // one text line — the height of every section
		int restY = base.getY();
		int x = base.getX();
		// Three stacked sections, each one band tall: a blocked lower bound, the
		// visible display window (where the loss rests), and a blocked overflow above.
		// The text moves exactly one band per phase, so it originates hidden in the
		// lower bound, scrolls up into the display window to rest, then scrolls up and
		// out into the overflow — fully legible the whole time it crosses the window.
		int y = restY + (int) ((1f - in) * band) - (int) (out * band);
		int alpha = (int) (255 * Math.min(in, 1f - out));

		Rectangle clipBounds = graphics.getClipBounds();
		Shape prevClip = graphics.getClip();
		boolean masked = clipBounds != null;
		if (masked)
		{
			// Reveal only the display window; the lower bound and overflow stay blocked
			int windowTop = Math.max(clipBounds.y, restY - fm.getAscent());
			int windowBottom = Math.min(clipBounds.y + clipBounds.height, restY - fm.getAscent() + band);
			if (windowBottom <= windowTop)
			{
				return; // the display window is off-screen — nothing to show
			}
			graphics.setClip(clipBounds.x, windowTop, clipBounds.width, windowBottom - windowTop);
		}

		graphics.setColor(new Color(0, 0, 0, alpha));
		graphics.drawString(death.text, x + 1, y + 1);
		graphics.setColor(new Color(death.color.getRed(), death.color.getGreen(), death.color.getBlue(), alpha));
		graphics.drawString(death.text, x, y);

		if (masked)
		{
			graphics.setClip(prevClip);
		}
	}
}
