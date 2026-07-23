package com.kaching;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * Movable on-screen panel with the running total of gp burned, in the style of
 * the DPS counter overlay. Shift-right-click &rarr; Clear zeroes the tally
 * (overlay menus only open while shift is held; see OverlayRenderer).
 */
@Singleton
class KaChingTotalOverlay extends OverlayPanel
{
	private final Client client;
	private final KaChingPlugin plugin;
	private final KaChingConfig config;

	@Inject
	KaChingTotalOverlay(Client client, KaChingPlugin plugin, KaChingConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Clear", "Ka-Ching! total", e -> plugin.clearTotal());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTotal() || client.getLocalPlayer() == null)
		{
			return null;
		}

		long total = plugin.getTotalBurned();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text((total > 0 ? "-" : "") + QuantityFormatter.formatNumber(total) + " gp")
			.build());

		return super.render(graphics);
	}
}
