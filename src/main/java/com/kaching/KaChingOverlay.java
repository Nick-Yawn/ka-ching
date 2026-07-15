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
	// Cap on how much painted time a single frame can add, so a rendering gap (menu
	// held open, tabbed out, region load) can't jump the animation forward — normal
	// frames fall well under this and pass through as near real time.
	private static final int DEATH_MAX_FRAME_STEP_MS = 250;
	// Fixed world-space height above the player's feet where the loss rests. A constant
	// rather than logicalHeight + margin (as the gold drops use) so it lands at the same
	// spot for a real death — shown on the respawn tick, when the live pose height is
	// briefly off and dragged the anchor down over the head — and for the ::kcdeath dev
	// command fired while standing (full height, which sat too high). Re-projected every
	// frame, so it stays anchored above the character as the camera moves.
	private static final int DEATH_LOSS_HEIGHT = 240;

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
		// The animation runs on painted time, not wall-clock: shownMs only advances
		// on frames where the loss is actually drawn, so a right-click menu (or a
		// region load) pauses it and it resumes where it left off instead of
		// expiring unseen. lastFrameMs is the previous frame's clock, -1 before the
		// first frame.
		long shownMs;
		long lastFrameMs = -1;

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
		// Fixed height (DEATH_LOSS_HEIGHT): independent of the live pose, so a real death
		// and the ::kcdeath dev command anchor the loss at the same spot.
		deathDrop = new DeathDrop("-" + QuantityFormatter.formatNumber(value) + " gp",
			DEATH_LOSS_HEIGHT, color, holdMs);
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
			// A region load or teleport briefly nulls the local player: skip the
			// frame but keep any pending loss, so it resumes when the player is back
			// (the painted-time clock is frozen meanwhile). A real logout/hop clears
			// it from the plugin's game-state handler instead.
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
		// Advance the animation clock only on frames the loss is actually shown. A
		// menu (or a null-player gap) freezes it here, so it never drains unseen; the
		// per-frame step is clamped so resuming after a long gap can't skip ahead.
		boolean menuOpen = client.isMenuOpen();
		long step = death.lastFrameMs < 0 ? 0 : now - death.lastFrameMs;
		death.lastFrameMs = now;
		if (!menuOpen)
		{
			death.shownMs += Math.min(step, DEATH_MAX_FRAME_STEP_MS);
		}

		long elapsed = death.shownMs;
		if (elapsed >= (long) DEATH_SCROLL_MS + death.holdMs + DEATH_SCROLL_MS)
		{
			deathDrop = null;
			return;
		}

		if (menuOpen)
		{
			return; // don't paint over an open menu; the clock above stays frozen
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
