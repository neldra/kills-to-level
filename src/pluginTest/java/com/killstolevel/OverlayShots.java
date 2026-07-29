package com.killstolevel;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Writes PNGs of the overlay without running the game, so a visual change can be reviewed — and
 * used in the README — without logging in and grinding kills for every tweak.
 *
 * <p>Run with {@code ./gradlew overlayShots}.
 */
public final class OverlayShots
{
	private static final int SCALE = 3;
	private static final int MARGIN = 8;
	private static final Color GAME_BACKDROP = new Color(0x3E, 0x38, 0x2E);

	private OverlayShots()
	{
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		Path dir = Paths.get(args.length > 0 ? args[0] : "build/overlay-shots");
		Files.createDirectories(dir);

		write(dir, "01-warming-up", new SimulatedGame().kills(3));
		write(dir, "02-measured", new SimulatedGame().kills(9));
		write(dir, "03-xp-target", new SimulatedGame()
			.xpTarget(VarPlayerID.XPDROPS_STRENGTH_END, 4470)
			.kills(9));

		System.out.println("overlay screenshots in " + dir.toAbsolutePath());
	}

	private static void write(Path dir, String name, SimulatedGame game) throws Exception
	{
		BufferedImage overlay = game.renderToImage();
		if (overlay == null)
		{
			System.out.println("skipped " + name + ": the overlay drew nothing");
			return;
		}

		int w = (overlay.getWidth() + MARGIN * 2) * SCALE;
		int h = (overlay.getHeight() + MARGIN * 2) * SCALE;
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

		Graphics2D g = out.createGraphics();
		g.setColor(GAME_BACKDROP);   // a game-ish backdrop, since the overlay is translucent
		g.fillRect(0, 0, w, h);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(overlay, MARGIN * SCALE, MARGIN * SCALE,
			overlay.getWidth() * SCALE, overlay.getHeight() * SCALE, null);
		g.dispose();

		File file = dir.resolve(name + ".png").toFile();
		ImageIO.write(out, "png", file);
		System.out.println("wrote " + file.getName()
			+ "  (overlay " + overlay.getWidth() + "x" + overlay.getHeight() + ")");
	}
}
