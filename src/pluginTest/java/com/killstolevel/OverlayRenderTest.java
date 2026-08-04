package com.killstolevel;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Renders the real overlay against a mocked client and asserts on what it actually drew. The unit
 * tests cover the numbers; these cover the thing tests usually cannot — whether anything reaches
 * the screen, and what it says when it does.
 */
public class OverlayRenderTest
{
	@Test
	public void drawsNothingWithNoCombatAtAll()
	{
		SimulatedGame game = new SimulatedGame();
		assertNull("overlay must stay hidden until something trains a skill",
			game.renderToImage());
	}

	/**
	 * The overlay used to wait for the first kill to complete before showing anything — before
	 * that, nothing said which skill you were training. It now appears on the first hit whose xp
	 * registers, since that's the same signal a completed kill would have given, just earlier.
	 */
	@Test
	public void showsTheSkillOnTheFirstHitBeforeAnyKillCompletes()
	{
		List<String> lines = new SimulatedGame().hits(1).renderedLines();

		assertEquals("title:Kills to level", lines.get(0));
		assertTrue("a hit must show the skill it trained, before any kill: " + lines,
			lines.contains("Strength|Measuring"));
		assertTrue("and say it is measuring, since one kill has not even happened yet: " + lines,
			lines.stream().noneMatch(l -> l.matches("Strength\\|~?\\d+")));
	}

	/**
	 * Three kills is a real measurement — two would do — but not yet a confident one, so the number
	 * is shown greyed with a "~" rather than withheld. Waiting for five meant staring at a
	 * placeholder for the first minute of every session with no number to act on; the prefix is
	 * there because colour alone is easy to miss.
	 */
	@Test
	public void thinlyMeasuredSkillShowsATildedGreyNumber()
	{
		SimulatedGame game = new SimulatedGame().kills(3);
		List<String> lines = game.renderedLines();

		assertEquals("title:Kills to level", lines.get(0));
		assertTrue("three kills must produce a number, marked ~ while thin: " + lines,
			lines.stream().anyMatch(l -> l.matches("Strength\\|~\\d+")));
		assertEquals("and it must be greyed to say it is still being confirmed",
			Color.LIGHT_GRAY, game.rightColorOf("Strength"));
	}

	@Test
	public void confidentSkillShowsASolidNumber()
	{
		SimulatedGame game = new SimulatedGame().kills(9);

		assertTrue("nine kills is past the confidence threshold: " + game.renderedLines(),
			game.renderedLines().stream().anyMatch(l -> l.matches("Strength\\|\\d+")));
		assertTrue("a confident number must not carry the ~: " + game.renderedLines(),
			game.renderedLines().stream().noneMatch(l -> l.contains("|~")));
		assertEquals("a confident number must not be greyed",
			Color.WHITE, game.rightColorOf("Strength"));
	}

	@Test
	public void measuredShowsCountsAndDropsTheProgressLine()
	{
		List<String> lines = new SimulatedGame().kills(9).renderedLines();

		assertEquals("title:Kills to level", lines.get(0));
		assertTrue("strength should show a number: " + lines,
			lines.stream().anyMatch(l -> l.matches("Strength\\|\\d+")));
		assertTrue("no shared warm-up line — confidence is per-row, in the colour: " + lines,
			lines.stream().noneMatch(l -> l.contains("kills measured")));
	}

	@Test
	public void hidingHitpointsLeavesOnlyTheStyleSkill()
	{
		List<String> shown = new SimulatedGame().kills(9).renderedLines();
		assertTrue("hitpoints is listed by default: " + shown,
			shown.stream().anyMatch(l -> l.startsWith("Hitpoints|")));

		List<String> hidden = new SimulatedGame().hideHitpoints().kills(9).renderedLines();
		assertTrue("hitpoints must be gone when switched off: " + hidden,
			hidden.stream().noneMatch(l -> l.startsWith("Hitpoints|")));
		assertTrue("and the style skill must remain: " + hidden,
			hidden.stream().anyMatch(l -> l.matches("Strength\\|\\d+")));
	}

	@Test
	public void xpTargetIsNamedBesideTheSkill()
	{
		List<String> lines = new SimulatedGame()
			.xpTarget(VarPlayerID.XPDROPS_STRENGTH_END, 4470)   // level 20
			.kills(9)
			.renderedLines();

		assertTrue("the target level should be shown so the count is not misread: " + lines,
			lines.stream().anyMatch(l -> l.startsWith("Strength (20)|")));
	}

	@Test
	public void actuallyPutsPixelsOnScreen()
	{
		BufferedImage img = new SimulatedGame().kills(9).renderToImage();

		assertNotNull("the overlay rendered no image at all", img);
		assertTrue("overlay is implausibly small: " + img.getWidth() + "x" + img.getHeight(),
			img.getWidth() > 60 && img.getHeight() > 20);

		int drawn = 0;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					drawn++;
				}
			}
		}
		assertTrue("overlay drew almost nothing: " + drawn + " opaque pixels", drawn > 500);
	}
}
