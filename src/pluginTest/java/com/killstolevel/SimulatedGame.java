package com.killstolevel;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A plugin wired to a mocked client, plus the real overlay, so both the screenshot tool and the
 * overlay tests can drive it without running the game.
 */
final class SimulatedGame
{
	private static final int DESPAWN_LAG_TICKS = 6;

	final Client client = mock(Client.class);
	final NpcUtil npcUtil = mock(NpcUtil.class);
	final KillsToLevelConfig config = mock(KillsToLevelConfig.class);
	final KillsToLevelPlugin plugin = new KillsToLevelPlugin();
	final KillsToLevelOverlay overlay;

	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
	private int npcIndex;

	SimulatedGame()
	{
		when(client.getSkillExperience(any(Skill.class)))
			.thenAnswer(inv -> xp.getOrDefault(inv.<Skill>getArgument(0), 0));
		when(client.getVarpValue(anyInt())).thenReturn(0);
		when(config.sampleWindow()).thenReturn(20);
		when(config.useInGameGoal()).thenReturn(true);
		when(config.showHitpoints()).thenReturn(true);

		plugin.client = client;
		plugin.npcUtil = npcUtil;
		plugin.config = config;
		plugin.overlayManager = mock(OverlayManager.class);
		plugin.overlay = mock(KillsToLevelOverlay.class);

		xp.put(Skill.STRENGTH, 1000);
		xp.put(Skill.HITPOINTS, 5000);

		plugin.startUp();
		overlay = new KillsToLevelOverlay(plugin);
	}

	/** Kill goblins: 5 damage each, so 20 Strength XP and ~6 Hitpoints XP per kill. */
	SimulatedGame kills(int count)
	{
		tick(2);
		for (int i = 0; i < count; i++)
		{
			NPC npc = mock(NPC.class);
			when(npc.getIndex()).thenReturn(++npcIndex);
			when(npcUtil.isDying(npc)).thenReturn(true);

			xp.merge(Skill.STRENGTH, 20, Integer::sum);
			xp.merge(Skill.HITPOINTS, 6, Integer::sum);

			HitsplatApplied hit = new HitsplatApplied();
			hit.setActor(npc);
			hit.setHitsplat(hitsplat());
			plugin.onHitsplatApplied(hit);

			tick(DESPAWN_LAG_TICKS);
			plugin.onNpcDespawned(new NpcDespawned(npc));
			tick(1);
		}
		return this;
	}

	SimulatedGame hideHitpoints()
	{
		when(config.showHitpoints()).thenReturn(false);
		return this;
	}

	/** Land hits without completing any kill, to check overlay state before the first one lands. */
	SimulatedGame hits(int count)
	{
		tick(2);
		for (int i = 0; i < count; i++)
		{
			NPC npc = mock(NPC.class);
			when(npc.getIndex()).thenReturn(++npcIndex);

			xp.merge(Skill.STRENGTH, 20, Integer::sum);
			xp.merge(Skill.HITPOINTS, 6, Integer::sum);

			HitsplatApplied hit = new HitsplatApplied();
			hit.setActor(npc);
			hit.setHitsplat(hitsplat());
			plugin.onHitsplatApplied(hit);
		}
		return this;
	}

	SimulatedGame xpTarget(@SuppressWarnings("SameParameterValue") int varp, int goalXp)
	{
		when(client.getVarpValue(varp)).thenReturn(goalXp);
		return this;
	}

	private void tick(int times)
	{
		for (int i = 0; i < times; i++)
		{
			plugin.onGameTick(new GameTick());
		}
	}

	private static Hitsplat hitsplat()
	{
		return new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return HitsplatID.DAMAGE_ME;
			}

			@Override
			public int getAmount()
			{
				return 5;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return 0;
			}
		};
	}

	/**
	 * Render the overlay and return its size, or null if it drew nothing. Rendered twice on
	 * purpose: {@code PanelComponent} sizes itself from the child dimensions measured during the
	 * previous pass, so a single render only ever reports the border.
	 */
	Dimension render(Graphics2D graphics)
	{
		overlay.render(graphics);
		return overlay.render(graphics);
	}

	BufferedImage renderToImage()
	{
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		Dimension size = render(pg);
		pg.dispose();
		if (size == null || size.width <= 0 || size.height <= 0)
		{
			return null;
		}

		BufferedImage img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		render(g);
		g.dispose();
		return img;
	}

	/** The text rows the overlay actually put on screen, in order, as "left|right". */
	List<String> renderedLines()
	{
		// OverlayPanel empties the panel in a finally block once it has drawn, so the components
		// would be gone before we could read them. The overlay clears and rebuilds them itself at
		// the top of every render, so switching this off changes nothing about what is drawn.
		overlay.setClearChildren(false);

		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = probe.createGraphics();
		render(g);
		g.dispose();

		List<String> lines = new ArrayList<>();
		for (LayoutableRenderableEntity child : overlay.getPanelComponent().getChildren())
		{
			String left = field(child, "left");
			String right = field(child, "right");
			String title = field(child, "text");
			if (title != null)
			{
				lines.add("title:" + title);
			}
			else
			{
				lines.add((left == null ? "" : left) + "|" + (right == null ? "" : right));
			}
		}
		return lines;
	}

	/**
	 * The colour the overlay drew a row's right-hand value in, so grey (measured but still being
	 * confirmed) can be told apart from solid (confident). Null if there is no such row.
	 */
	Color rightColorOf(String left)
	{
		overlay.setClearChildren(false);

		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = probe.createGraphics();
		render(g);
		g.dispose();

		for (LayoutableRenderableEntity child : overlay.getPanelComponent().getChildren())
		{
			if (left.equals(field(child, "left")))
			{
				return (Color) rawField(child, "rightColor");
			}
		}
		return null;
	}

	/** Read a private field off a RuneLite component — they expose setters but no getters. */
	private static String field(Object target, String name)
	{
		Object value = rawField(target, name);
		return value == null ? null : value.toString();
	}

	private static Object rawField(Object target, String name)
	{
		try
		{
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		}
		catch (NoSuchFieldException e)
		{
			return null;
		}
		catch (IllegalAccessException e)
		{
			throw new AssertionError("cannot read " + name + " from " + target.getClass(), e);
		}
	}
}
