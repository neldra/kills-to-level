package com.killstolevel;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the plugin's real event handlers against a mocked client. The estimator's arithmetic is
 * covered by {@link KillXpEstimatorTest}; what is exercised here is the event plumbing around it —
 * which kills get credited, which tick a kill is priced at, and which skills a kill counts as
 * having trained.
 */
public class KillCreditTest
{
	/** Goblin: 5 hitpoints, so 5 damage and 4 XP per damage. */
	private static final int DAMAGE = 5;
	private static final int DESPAWN_LAG_TICKS = 6;   // measured in-client

	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
	private Client client;
	private NpcUtil npcUtil;
	private KillsToLevelConfig config;
	private KillsToLevelPlugin plugin;
	private int nextNpcIndex;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		npcUtil = mock(NpcUtil.class);

		when(client.getSkillExperience(any(Skill.class)))
			.thenAnswer(inv -> xp.getOrDefault(inv.<Skill>getArgument(0), 0));

		config = mock(KillsToLevelConfig.class);
		when(config.sampleWindow()).thenReturn(20);
		when(config.useInGameGoal()).thenReturn(true);
		when(config.showHitpoints()).thenReturn(true);
		when(client.getVarpValue(anyInt())).thenReturn(0);   // no in-game target set
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));

		plugin = new KillsToLevelPlugin();
		plugin.client = client;
		plugin.npcUtil = npcUtil;
		plugin.config = config;
		plugin.overlayManager = mock(OverlayManager.class);
		plugin.overlay = mock(KillsToLevelOverlay.class);

		xp.put(Skill.STRENGTH, 1000);
		xp.put(Skill.HITPOINTS, 5000);
		xp.put(Skill.MAGIC, 3000);

		plugin.startUp();
	}

	private void tick()
	{
		plugin.onGameTick(new GameTick());
	}

	private void tick(int times)
	{
		for (int i = 0; i < times; i++)
		{
			tick();
		}
	}

	private NPC npc()
	{
		NPC npc = mock(NPC.class);
		when(npc.getIndex()).thenReturn(++nextNpcIndex);
		return npc;
	}

	private void grant(Skill skill, int amount)
	{
		xp.merge(skill, amount, Integer::sum);
	}

	/**
	 * A real {@link Hitsplat}, not a mock: {@code isMine()} is a default method on the interface, so
	 * only the hitsplat type is ours to supply and RuneLite's own classification is what runs. If
	 * RuneLite changes which types count as the player's, these tests follow it.
	 */
	private static Hitsplat hitsplat(int type, int amount)
	{
		return new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return type;
			}

			@Override
			public int getAmount()
			{
				return amount;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return 0;
			}
		};
	}

	/** Land a hit, granting XP on the same tick — the client does exactly this. */
	private void hit(NPC npc, int damage, boolean mine)
	{
		hit(npc, damage, mine ? HitsplatID.DAMAGE_ME : HitsplatID.DAMAGE_OTHER);
	}

	private void hit(NPC npc, int damage, int hitsplatType)
	{
		Hitsplat hitsplat = hitsplat(hitsplatType, damage);
		if (hitsplat.isMine())
		{
			grantKillXp(damage);
		}
		applyHitsplat(npc, hitsplat);
	}

	/** Land our hit but withhold the XP, so a caller can deliver it on a later tick. */
	private void hitWithoutXp(NPC npc, int damage)
	{
		applyHitsplat(npc, hitsplat(HitsplatID.DAMAGE_ME, damage));
	}

	private void applyHitsplat(NPC npc, Hitsplat hitsplat)
	{
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(npc);
		event.setHitsplat(hitsplat);
		plugin.onHitsplatApplied(event);
	}

	private void grantKillXp(int damage)
	{
		grant(Skill.STRENGTH, damage * 4);
		grant(Skill.HITPOINTS, (damage * 4) / 3);
	}

	private void grantDefenceKillXp(int damage)
	{
		grant(Skill.DEFENCE, damage * 4);
		grant(Skill.HITPOINTS, (damage * 4) / 3);
	}

	private void despawn(NPC npc, boolean dying)
	{
		when(npcUtil.isDying(npc)).thenReturn(dying);
		plugin.onNpcDespawned(new NpcDespawned(npc));
	}

	/** A whole kill: killing blow, then the corpse lingering before it despawns. */
	private void killOne()
	{
		killOne(DAMAGE);
	}

	/**
	 * A kill ordered the way the live client orders it: the client applies the damage and grants the
	 * XP, RuneLite posts GameTick, and only then posts HitsplatApplied for that same cycle. So the
	 * blow's XP is already in the tick that just closed, while lastHitTick names the tick that has
	 * just begun — measured in-client, every kill, without exception.
	 */
	private void killOne(int damage)
	{
		NPC goblin = npc();
		grantKillXp(damage);
		tick();
		applyHitsplat(goblin, hitsplat(HitsplatID.DAMAGE_ME, damage));
		tick(DESPAWN_LAG_TICKS - 1);
		despawn(goblin, true);
	}

	/** Mirrors {@link #killOne(int)} but trains Defence — for testing a switch between styles. */
	private void killOneTrainingDefence()
	{
		NPC goblin = npc();
		grantDefenceKillXp(DAMAGE);
		tick();
		applyHitsplat(goblin, hitsplat(HitsplatID.DAMAGE_ME, DAMAGE));
		tick(DESPAWN_LAG_TICKS - 1);
		despawn(goblin, true);
	}

	private void login()
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(event);
	}

	private void configChanged(String group)
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(group);
		plugin.onConfigChanged(event);
	}

	@Test
	public void fiveKillsProduceAnEstimateForTheTrainedSkill()
	{
		tick(2);   // settle the XP history before any kill
		for (int i = 0; i < 5; i++)
		{
			killOne();
		}

		// 5 snapshots -> 4 intervals of 20 xp -> 20 xp/kill. 1100 xp is level 9;
		// level 10 is 1154, so 54 remaining, ceil(54 / 20) = 3.
		assertEquals(1100, (int) xp.get(Skill.STRENGTH));
		assertEquals(3, plugin.killsToLevel(Skill.STRENGTH));
		assertTrue("a kill must mark the skill it trained", plugin.isRecentlyTrained(Skill.STRENGTH));
	}

	@Test
	public void skillNotTrainedByTheKillIsNotListed()
	{
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();
			grant(Skill.MAGIC, 65);   // alching between kills, never on a killing blow
			tick();
		}

		assertTrue(plugin.isRecentlyTrained(Skill.STRENGTH));
		assertFalse("magic xp that never landed on a kill must not list magic",
			plugin.isRecentlyTrained(Skill.MAGIC));
	}

	@Test
	public void damageThatIsNotOursIsNotCredited()
	{
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			NPC goblin = npc();
			hit(goblin, DAMAGE, false);   // cannon / thrall / another player
			tick(DESPAWN_LAG_TICKS);
			despawn(goblin, true);
		}

		assertEquals(KillXpEstimator.UNKNOWN, plugin.killsToLevel(Skill.STRENGTH));
		assertFalse(plugin.isRecentlyTrained(Skill.STRENGTH));
	}

	@Test
	public void npcThatDespawnsWithoutDyingIsNotCredited()
	{
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			NPC goblin = npc();
			hit(goblin, DAMAGE, true);
			tick(DESPAWN_LAG_TICKS);
			despawn(goblin, false);   // walked off, or a phase change
		}

		assertEquals(KillXpEstimator.UNKNOWN, plugin.killsToLevel(Skill.STRENGTH));
	}

	@Test
	public void killAndDespawnOnTheSameTickIsPricedCorrectly()
	{
		// The history slot for the tick in progress has not been written yet; reading it would
		// snapshot a zero and make the next window difference the player's entire xp total.
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			NPC goblin = npc();
			hit(goblin, DAMAGE, true);
			despawn(goblin, true);   // same tick as the killing blow, before GameTick
			tick();
		}

		assertEquals(1100, (int) xp.get(Skill.STRENGTH));
		assertEquals("same-tick despawns must measure the same 20 xp/kill", 3,
			plugin.killsToLevel(Skill.STRENGTH));
	}

	/**
	 * The six-tick corpse lag is something we measured, not something RuneLite promises. Pricing a
	 * kill at its killing blow should hold for any lag the buffer covers, so the plugin does not
	 * depend on that number staying six.
	 */
	@Test
	public void pricingHoldsForAnyDespawnLag()
	{
		for (int lag = 0; lag <= 20; lag++)
		{
			setUp();
			tick(2);
			for (int i = 0; i < 5; i++)
			{
				NPC goblin = npc();
				hit(goblin, DAMAGE, true);
				tick(lag);
				despawn(goblin, true);
				tick();
			}
			assertEquals("despawn lag of " + lag + " ticks", 3, plugin.killsToLevel(Skill.STRENGTH));
			assertTrue("despawn lag of " + lag + " ticks", plugin.isRecentlyTrained(Skill.STRENGTH));
		}
	}

	/**
	 * We observed XP landing on the same tick as the hitsplat. If that were ever off by a tick, the
	 * measurement must not care: a consistent offset cancels when differencing cumulative totals, and
	 * the skill should still be listed as trained.
	 */
	@Test
	public void estimateSurvivesXpArrivingATickLate()
	{
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			NPC goblin = npc();
			tick();
			hitWithoutXp(goblin, DAMAGE);
			grantKillXp(DAMAGE);                      // xp trails the hitsplat instead of leading it
			tick(DESPAWN_LAG_TICKS - 1);
			despawn(goblin, true);
			tick();
		}

		assertEquals("a consistent one-tick offset must cancel out", 3,
			plugin.killsToLevel(Skill.STRENGTH));
		assertTrue("the skill must still be listed as trained",
			plugin.isRecentlyTrained(Skill.STRENGTH));
	}

	/**
	 * With an XP target set on the skill tab, count to that instead of the next level. The core XP
	 * tracker reads the same varps, so both agree on what you are working towards.
	 */
	@Test
	public void countsToTheInGameXpTargetWhenOneIsSet()
	{
		// Level 20 is 4470 xp; from 1100 that is 3370 remaining at 20 xp/kill -> 169 kills.
		when(client.getVarpValue(VarPlayerID.XPDROPS_STRENGTH_END)).thenReturn(4470);

		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();
		}

		assertEquals(169, plugin.killsToLevel(Skill.STRENGTH));
		assertEquals(20, plugin.goalLevel(Skill.STRENGTH));
	}

	@Test
	public void fallsBackToNextLevelWhenTheTargetIsBehindUs()
	{
		// A target already passed must not produce a negative or zero remainder.
		when(client.getVarpValue(VarPlayerID.XPDROPS_STRENGTH_END)).thenReturn(500);

		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();
		}

		assertEquals(3, plugin.killsToLevel(Skill.STRENGTH));
		assertEquals("no goal marker when the target is behind us", -1, plugin.goalLevel(Skill.STRENGTH));
	}

	@Test
	public void inGameTargetIsIgnoredWhenTurnedOff()
	{
		when(config.useInGameGoal()).thenReturn(false);
		when(client.getVarpValue(VarPlayerID.XPDROPS_STRENGTH_END)).thenReturn(4470);

		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();
		}

		assertEquals(3, plugin.killsToLevel(Skill.STRENGTH));
		assertEquals(-1, plugin.goalLevel(Skill.STRENGTH));
	}

	/**
	 * A number is measured from the second kill — one interval of a fixed monster's xp is already
	 * exact — but stays unconfident until MIN_SAMPLES, so the overlay can grey it rather than
	 * withholding it. Answering from kill two instead of kill five is the whole point.
	 */
	@Test
	public void estimateArrivesOnTheSecondKillButIsNotConfidentYet()
	{
		tick(2);
		assertEquals("nothing measurable before any kill",
			KillXpEstimator.UNKNOWN, plugin.killsToLevel(Skill.STRENGTH));

		killOne();
		assertEquals("one kill is one snapshot — no interval, so still nothing",
			KillXpEstimator.UNKNOWN, plugin.killsToLevel(Skill.STRENGTH));
		assertFalse(plugin.isConfident(Skill.STRENGTH));

		killOne();
		// 1040 xp is level 9; level 10 is 1154, so 114 remaining at the measured 20 xp/kill.
		assertEquals("two kills give one real interval of 20 xp -> a usable number", 6,
			plugin.killsToLevel(Skill.STRENGTH));
		assertFalse("but not confident yet, so the overlay greys it",
			plugin.isConfident(Skill.STRENGTH));

		killOne();
		killOne();
		assertFalse("still thin at four samples", plugin.isConfident(Skill.STRENGTH));

		killOne();
		// The measured rate never moved off 20 xp/kill; the count shrank only because 5 kills of
		// xp brought the level closer — 1100 xp leaves 54 to go. Confidence is what changed here.
		assertEquals(3, plugin.killsToLevel(Skill.STRENGTH));
		assertTrue("five samples is confident, so the overlay shows it solid",
			plugin.isConfident(Skill.STRENGTH));
	}

	/**
	 * Hopping worlds fires LOGGED_IN again on the same account. The measurement has to survive it —
	 * a hop mid-grind is routine, and resetting there would send the overlay back to warming up.
	 */
	@Test
	public void worldHopKeepsTheMeasurement()
	{
		when(client.getAccountHash()).thenReturn(1234L);
		login();

		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();
		}
		assertEquals(3, plugin.killsToLevel(Skill.STRENGTH));

		login();   // the hop: LOGGED_IN again, same account

		assertEquals("a world hop must not discard the measurement", 3,
			plugin.killsToLevel(Skill.STRENGTH));
		assertTrue("nor its confidence", plugin.isConfident(Skill.STRENGTH));
	}

	/** A different account is a different player: its kills must not price this one's. */
	@Test
	public void switchingAccountWipesTheMeasurement()
	{
		when(client.getAccountHash()).thenReturn(1234L);
		login();

		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();
		}
		assertEquals(3, plugin.killsToLevel(Skill.STRENGTH));

		when(client.getAccountHash()).thenReturn(5678L);
		login();

		assertEquals("another account's kills must not carry over",
			KillXpEstimator.UNKNOWN, plugin.killsToLevel(Skill.STRENGTH));
		assertEquals("nor its samples", 0, plugin.sampleCount(Skill.STRENGTH));
		assertFalse(plugin.isConfident(Skill.STRENGTH));
	}

	/**
	 * Changing the sample window resizes in place rather than starting over, so the overlay keeps
	 * answering across the change — and the smaller window tracks the more recent kills.
	 */
	@Test
	public void shrinkingTheWindowTracksTheRecentKills()
	{
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();       // 20 xp each
		}
		for (int i = 0; i < 4; i++)
		{
			killOne(25);     // 100 xp each
		}

		// 9 snapshots spanning 1020..1500 -> 480 xp over 8 intervals = 60 xp/kill. 1500 xp is
		// level 11; level 12 is 1584, so 84 remaining, ceil(84 / 60) = 2.
		assertEquals(1500, (int) xp.get(Skill.STRENGTH));
		assertEquals(2, plugin.killsToLevel(Skill.STRENGTH));

		when(config.sampleWindow()).thenReturn(5);
		configChanged(KillsToLevelConfig.GROUP);

		// The last 5 snapshots span 1100..1500 -> 400 over 4 = 100 xp/kill, so ceil(84 / 100) = 1.
		assertEquals("a shrunk window must track the recent, faster kills", 1,
			plugin.killsToLevel(Skill.STRENGTH));
	}

	@Test
	public void configChangeFromAnotherPluginIsIgnored()
	{
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne();
		}
		for (int i = 0; i < 4; i++)
		{
			killOne(25);
		}
		assertEquals(2, plugin.killsToLevel(Skill.STRENGTH));

		when(config.sampleWindow()).thenReturn(5);
		configChanged("someotherplugin");

		assertEquals("another plugin's config change must not resize our window", 2,
			plugin.killsToLevel(Skill.STRENGTH));
	}

	/**
	 * Measured in the live client: GameTick is posted before HitsplatApplied for the same cycle, so
	 * the killing blow's XP lands in the tick before the one lastHitTick records. A kill must still
	 * mark the skill it trained, or the overlay never appears at all.
	 */
	@Test
	public void killIsAttributedWhenXpLandsBeforeTheRecordedHitTick()
	{
		tick(2);
		for (int i = 0; i < 5; i++)
		{
			killOne(DAMAGE);
		}

		assertTrue("the kill must mark the skill it trained, whichever tick the xp landed on",
			plugin.isRecentlyTrained(Skill.STRENGTH));
		assertTrue(plugin.isRecentlyTrained(Skill.HITPOINTS));
		assertFalse("a skill the kill never trained must stay unlisted",
			plugin.isRecentlyTrained(Skill.MAGIC));
		assertEquals("pricing must still be correct", 3, plugin.killsToLevel(Skill.STRENGTH));
	}

	/**
	 * The companion to {@link #pricingHoldsForAnyDespawnLag()}, on the axis that actually broke.
	 *
	 * <p>Nothing here controls when RuneLite hands us the XP relative to the hitsplat — that is the
	 * client's business, it is not documented, and it was silently assumed to be one thing while
	 * being another. The plugin shipped with a green suite and could not draw its overlay at all,
	 * because every test granted the XP before firing the hitsplat and so only ever exercised the
	 * assumption, never questioned it.
	 *
	 * <p>So sweep the offset instead of assuming one. This pins exactly which arrival offsets are
	 * supported: the XP landing in the snapshot for the tick before the recorded hit (what the live
	 * client does), or in the recorded tick itself. If RuneLite ever reorders these events again,
	 * this fails with a named offset rather than an overlay that quietly never appears.
	 */
	@Test
	public void attributionHoldsAcrossXpArrivalOffsets()
	{
		for (int offset = -1; offset <= 0; offset++)
		{
			setUp();
			tick(2);
			for (int i = 0; i < 5; i++)
			{
				NPC goblin = npc();
				if (offset == -1)
				{
					grantKillXp(DAMAGE);   // xp granted before GameTick closes the previous tick
					tick();
					applyHitsplat(goblin, hitsplat(HitsplatID.DAMAGE_ME, DAMAGE));
				}
				else
				{
					tick();
					applyHitsplat(goblin, hitsplat(HitsplatID.DAMAGE_ME, DAMAGE));
					grantKillXp(DAMAGE);   // xp granted after the hitsplat, same tick
				}
				tick(DESPAWN_LAG_TICKS - 1);
				despawn(goblin, true);
			}

			assertTrue("xp arriving at offset " + offset + " must still attribute the kill",
				plugin.isRecentlyTrained(Skill.STRENGTH));
			assertEquals("xp arriving at offset " + offset + " must still price the kill",
				3, plugin.killsToLevel(Skill.STRENGTH));
		}
	}

	/**
	 * The overlay used to wait for the first kill to complete before showing anything at all — the
	 * skill you're training is only known once a kill's xp lands, so there was nothing to attribute
	 * before that. Visibility now comes from the hit itself: the same tick-window check that prices
	 * a kill also runs on every hit, so the panel appears the moment its xp registers, with warm-up
	 * starting at 0/5 rather than waiting for kill 1.
	 */
	@Test
	public void hittingShowsTheSkillBeforeAnyKillCompletes()
	{
		tick(2);
		NPC goblin = npc();
		hit(goblin, DAMAGE, true);

		assertTrue("a hit must mark the skill trained immediately, before the kill completes",
			plugin.isRecentlyTrained(Skill.STRENGTH));
		assertEquals("no kills measured yet, so no samples", 0, plugin.sampleCount(Skill.STRENGTH));
		assertEquals("no estimate yet — only a hit has landed, not a completed kill",
			KillXpEstimator.UNKNOWN, plugin.killsToLevel(Skill.STRENGTH));
	}

	@Test
	public void nonMineHitDoesNotShowAnySkill()
	{
		tick(2);
		NPC goblin = npc();
		hit(goblin, DAMAGE, false);   // cannon / thrall / another player

		assertFalse("damage that isn't ours must not mark any skill trained",
			plugin.isRecentlyTrained(Skill.STRENGTH));
	}

	/**
	 * Every kill used to feed every skill's estimator, whether or not that kill trained it — a kill
	 * before you switch styles recorded the new skill's UNCHANGED total, and once you switch, those
	 * flat entries sat in its rolling window dragging the average down until the window rolled all
	 * the way over. A kill must only feed the skill(s) it actually trained, so the rate is accurate
	 * as soon as there are enough real samples, regardless of what you were training before.
	 */
	@Test
	public void switchingStylesGivesAnAccurateRateNotADilutedOne()
	{
		grant(Skill.DEFENCE, 1000);

		tick(2);
		for (int i = 0; i < 15; i++)
		{
			killOne();   // strength-training kills; defence never moves
		}
		for (int i = 0; i < 5; i++)
		{
			killOneTrainingDefence();
		}

		// 5 defence-training kills at 20 xp each -> 4 intervals of 20 xp -> 20 xp/kill, matching
		// the same math as every other "3 kills left" case in this file — not diluted down by the
		// 15 strength kills that came before the switch.
		assertEquals("a style switch must not dilute the new skill's rate",
			3, plugin.killsToLevel(Skill.DEFENCE));
	}

	/**
	 * Switching attack style used to leave the old skill listed for ACTIVE_TIMEOUT_TICKS — about five
	 * more attacks — formatted identically to the skill you had just moved to, so there was no way to
	 * tell which number was live. Rows now follow the most recent thing you trained.
	 */
	@Test
	public void switchingStyleSwapsTheListedSkillOnTheNextHit()
	{
		tick(2);
		for (int i = 0; i < 3; i++)
		{
			killOne();   // aggressive: strength
		}
		assertTrue(plugin.isRecentlyTrained(Skill.STRENGTH));
		assertFalse("defence has not been trained at all yet",
			plugin.isRecentlyTrained(Skill.DEFENCE));

		killOneTrainingDefence();

		assertTrue("the style you switched to must be listed", plugin.isRecentlyTrained(Skill.DEFENCE));
		assertFalse("the style you left must drop off immediately, not 30s later",
			plugin.isRecentlyTrained(Skill.STRENGTH));
	}

	/** Controlled trains three skills on the same tick, so all three stay listed together. */
	@Test
	public void aStyleTrainingSeveralSkillsListsThemAll()
	{
		tick(2);
		for (int i = 0; i < 3; i++)
		{
			NPC goblin = npc();
			grant(Skill.ATTACK, DAMAGE * 4 / 3);
			grant(Skill.STRENGTH, DAMAGE * 4 / 3);
			grant(Skill.DEFENCE, DAMAGE * 4 / 3);
			grant(Skill.HITPOINTS, DAMAGE * 4 / 3);
			tick();
			applyHitsplat(goblin, hitsplat(HitsplatID.DAMAGE_ME, DAMAGE));
			tick(DESPAWN_LAG_TICKS - 1);
			despawn(goblin, true);
		}

		assertTrue(plugin.isRecentlyTrained(Skill.ATTACK));
		assertTrue(plugin.isRecentlyTrained(Skill.STRENGTH));
		assertTrue(plugin.isRecentlyTrained(Skill.DEFENCE));
		assertTrue(plugin.isRecentlyTrained(Skill.HITPOINTS));
	}

	@Test
	public void hitpointsCanBeHidden()
	{
		assertTrue("shown by default", plugin.isShown(Skill.HITPOINTS));
		assertTrue(plugin.isShown(Skill.STRENGTH));

		when(config.showHitpoints()).thenReturn(false);

		assertFalse("hitpoints hides when switched off", plugin.isShown(Skill.HITPOINTS));
		assertTrue("every other skill is unaffected", plugin.isShown(Skill.STRENGTH));
	}

	/**
	 * Hitpoints gains 1.33 xp per damage, integer-rounded, so a small hit can award it nothing at all
	 * while the style skill still gains. It must not flicker off the panel on those hits — it trains
	 * on every point of damage whatever the style, so it is never the skill you switched away from.
	 */
	@Test
	public void hitpointsStaysListedThroughAHitThatRoundsItToZero()
	{
		tick(2);
		for (int i = 0; i < 3; i++)
		{
			killOne();
		}
		assertTrue(plugin.isRecentlyTrained(Skill.HITPOINTS));

		// A hit that moves strength but leaves hitpoints exactly where it was.
		NPC goblin = npc();
		grant(Skill.STRENGTH, DAMAGE * 4);
		tick();
		applyHitsplat(goblin, hitsplat(HitsplatID.DAMAGE_ME, DAMAGE));
		tick(DESPAWN_LAG_TICKS - 1);
		despawn(goblin, true);

		assertTrue("hitpoints must not drop out over a rounding gap",
			plugin.isRecentlyTrained(Skill.HITPOINTS));
		assertTrue(plugin.isRecentlyTrained(Skill.STRENGTH));
	}

	@Test
	public void indexReuseDoesNotCreditAnUndamagedNpc()
	{
		tick(2);
		NPC first = npc();
		hit(first, DAMAGE, true);
		tick(DESPAWN_LAG_TICKS);
		despawn(first, true);

		// A new NPC reusing the same index, which we never hit, must not be credited.
		int reusedIndex = first.getIndex();
		NPC recycled = mock(NPC.class);
		when(recycled.getIndex()).thenReturn(reusedIndex);
		tick(DESPAWN_LAG_TICKS);
		despawn(recycled, true);

		// One credited kill only: a single snapshot can never reach an estimate.
		assertEquals(KillXpEstimator.UNKNOWN, plugin.killsToLevel(Skill.STRENGTH));
	}

	/**
	 * XP that arrives with no damage behind it — a lamp, a quest reward — must not be priced as a
	 * kill. Without the plausibility bound the 5,020 gain below would enter the mean and the count
	 * would read 17 instead of 693 until the poisoned sample rolled out of the window.
	 */
	@Test
	public void lampBetweenKillsDoesNotSkewTheEstimate()
	{
		when(client.getVarpValue(VarPlayerID.XPDROPS_STRENGTH_END)).thenReturn(20000);
		tick(2);
		for (int i = 0; i < 6; i++)
		{
			killOne();
		}

		grant(Skill.STRENGTH, 5000);   // the lamp, landing between fights
		tick(2);
		killOne();

		// xp = 1000 + 7x20 + 5000 = 6140, so 13860 remain at a true 20/kill.
		assertEquals(693, plugin.killsToLevel(Skill.STRENGTH));
	}

	/**
	 * Leagues and Deadman worlds multiply combat XP past any fixed plausibility ceiling, so the
	 * bound stands down there: the lamp scenario above is absorbed the way v1.0 absorbed it, in
	 * exchange for real multiplied kills never being excised.
	 */
	@Test
	public void boostedWorldStandsTheGuardDown()
	{
		when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.SEASONAL));
		when(client.getVarpValue(VarPlayerID.XPDROPS_STRENGTH_END)).thenReturn(20000);
		tick(2);
		for (int i = 0; i < 6; i++)
		{
			killOne();
		}

		grant(Skill.STRENGTH, 5000);
		tick(2);
		killOne();

		// The 5,020 gain is accepted: mean (5x20 + 5020) / 6, and 13860 remain.
		assertEquals(17, plugin.killsToLevel(Skill.STRENGTH));
	}

	/**
	 * A credited kill whose XP registers too late feeds no estimator, so the NEXT kill's gain spans
	 * two kills — and its bound must too. The damage counters are per skill, reset only when that
	 * skill records, precisely so an unfed kill cannot shrink the bound out from under the gain it
	 * leaves behind: a single counter reset on every credited kill would bound this 720 at 680.
	 */
	@Test
	public void killWhoseXpArrivesLateDoesNotShrinkTheNextKillsBound()
	{
		when(client.getVarpValue(VarPlayerID.XPDROPS_STRENGTH_END)).thenReturn(20000);
		tick(2);
		// A monster paying 12 XP per damage — well under the 16 cap, well over half of it.
		killBigMonster(360);
		killBigMonster(360);

		// The unfed kill: blow lands, corpse despawns, and only then does the XP register.
		NPC late = npc();
		tick();
		applyHitsplat(late, hitsplat(HitsplatID.DAMAGE_ME, 30));
		tick(DESPAWN_LAG_TICKS - 1);
		despawn(late, true);
		grant(Skill.STRENGTH, 360);
		tick(2);

		killBigMonster(360);

		// Gains 360 and 720 both accepted: xp = 1000 + 4x360 = 2440, 17560 remain at a mean of 540.
		assertEquals(33, plugin.killsToLevel(Skill.STRENGTH));
	}

	private void killBigMonster(int strengthXp)
	{
		NPC monster = npc();
		grant(Skill.STRENGTH, strengthXp);
		tick();
		applyHitsplat(monster, hitsplat(HitsplatID.DAMAGE_ME, 30));
		tick(DESPAWN_LAG_TICKS - 1);
		despawn(monster, true);
	}

	/**
	 * A splash pays Magic's base cast XP with zero damage, so a splash-heavy fight can legitimately
	 * gain more than any damage-derived bound allows. Each observed splash widens Magic's
	 * allowance: without that, the 300 gain below would be excised against a bound of 232.
	 */
	@Test
	public void splashesWidenMagicsAllowance()
	{
		when(client.getVarpValue(VarPlayerID.XPDROPS_MAGIC_END)).thenReturn(20000);
		tick(2);
		// A first magic kill, only to give Magic its baseline.
		NPC first = npc();
		grant(Skill.MAGIC, 20);
		tick();
		applyHitsplat(first, hitsplat(HitsplatID.DAMAGE_ME, 2));
		tick(DESPAWN_LAG_TICKS - 1);
		despawn(first, true);

		// Then a fight of four splashes before the 2-damage killing hit lands.
		NPC second = npc();
		for (int i = 0; i < 4; i++)
		{
			applyHitsplat(second, hitsplat(HitsplatID.DAMAGE_ME, 0));
		}
		grant(Skill.MAGIC, 300);
		tick();
		applyHitsplat(second, hitsplat(HitsplatID.DAMAGE_ME, 2));
		tick(DESPAWN_LAG_TICKS - 1);
		despawn(second, true);

		// xp = 3000 + 320, so 16680 remain at 300/kill.
		assertEquals(56, plugin.killsToLevel(Skill.MAGIC));
	}

	/**
	 * The config counts kills, the estimator counts gains between them — one fewer. With a mixed
	 * window the two readings give different means, so this pins the conversion at both the
	 * construction and the resize call sites.
	 */
	@Test
	public void sampleWindowCountsKillsNotGains()
	{
		when(client.getVarpValue(VarPlayerID.XPDROPS_STRENGTH_END)).thenReturn(20000);
		tick(2);
		// 3 goblins then 19 bigger monsters: 21 gains, of which a 20-kill window spans the last 19.
		for (int i = 0; i < 3; i++)
		{
			killOne();
		}
		for (int i = 0; i < 19; i++)
		{
			killOne(25);   // 100 xp each
		}

		// xp = 1000 + 3x20 + 19x100 = 2960: 17040 remain at 100/kill — a window one too wide
		// would still hold a 20 and read 178.
		assertEquals(171, plugin.killsToLevel(Skill.STRENGTH));

		// And shrinking to 5 kills must keep the last 4 gains, not 5.
		for (int i = 0; i < 4; i++)
		{
			killOne(50);   // 200 xp each
		}
		when(config.sampleWindow()).thenReturn(5);
		configChanged(KillsToLevelConfig.GROUP);

		// xp = 3760: 16240 remain at 200/kill — keeping 5 gains would mix a 100 in and read 91.
		assertEquals(82, plugin.killsToLevel(Skill.STRENGTH));
	}
}
