package com.killstolevel;

import com.google.inject.Provides;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.annotations.Varp;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Shows "N kills to next level" for the combat skill(s) you're training, by MEASURING your XP per
 * kill rather than estimating it from monster health. The overlay appears as soon as a hit trains a
 * combat skill, greying a number until enough kills back it up. A kill is counted only when an NPC
 * you dealt damage to actually dies ({@link NpcUtil#isDying}), is priced at the tick of its killing
 * blow — not at despawn, which trails the kill by several ticks — and feeds only the
 * {@link KillXpEstimator} of the skills it actually trained.
 */
@Slf4j
@PluginDescriptor(
	name = "Kills to Level",
	description = "Shows how many kills until your next combat level, measured from your actual XP per kill.",
	tags = {"combat", "xp", "kills", "level", "goal", "slayer", "training"}
)
public class KillsToLevelPlugin extends Plugin
{
	private static final Set<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS, Skill.RANGED, Skill.MAGIC);
	private static final int MIN_SAMPLES = 5;
	private static final int ACTIVE_TIMEOUT_TICKS = 50;   // ~30s the panel stays up after you stop training
	/**
	 * How far behind the newest style-training tick a skill may be and still count as current. Kept
	 * well under one attack cycle so a style you have left drops off on your next hit; the skills a
	 * multi-skill style trains are stamped on the same tick, so they never need the slack.
	 */
	private static final int STYLE_SWITCH_SLACK_TICKS = 2;
	/** Ticks of cumulative-XP history kept, so a kill can be priced at its killing blow. */
	private static final int XP_HISTORY_TICKS = 64;

	// Package-private so tests can drive the plugin with mocked client state.
	@Inject Client client;
	@Inject NpcUtil npcUtil;
	@Inject OverlayManager overlayManager;
	@Inject KillsToLevelConfig config;
	@Inject KillsToLevelOverlay overlay;

	private final Map<Skill, KillXpEstimator> estimators = new EnumMap<>(Skill.class);
	/** Tick a skill's XP last registered — from a hit immediately, or its kill as a fallback. */
	private final Map<Skill, Integer> lastTrainedTick = new EnumMap<>(Skill.class);
	private final Map<Skill, int[]> xpHistory = new EnumMap<>(Skill.class);
	private final Set<Integer> damagedNpcs = new HashSet<>();
	/** Tick of the most recent hit we landed on each NPC — i.e. its killing blow, once it dies. */
	private final Map<Integer, Integer> lastHitTick = new HashMap<>();
	private int tick;
	private long lastAccountHash = -1;

	@Provides
	KillsToLevelConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(KillsToLevelConfig.class);
	}

	@Override
	protected void startUp()
	{
		buildEstimators();
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		reset();
		estimators.clear();
	}

	private void buildEstimators()
	{
		estimators.clear();
		for (Skill s : COMBAT_SKILLS)
		{
			estimators.put(s, new KillXpEstimator(config.sampleWindow(), MIN_SAMPLES));
			xpHistory.put(s, new int[XP_HISTORY_TICKS]);
		}
	}

	private void reset()
	{
		lastTrainedTick.clear();
		damagedNpcs.clear();
		lastHitTick.clear();
	}

	/**
	 * Cumulative XP as of the end of {@code atTick}. The slot for the tick still in progress has not
	 * been written yet, and one older than the buffer has been overwritten, so both read live instead.
	 */
	private int xpAtTick(Skill skill, int atTick)
	{
		if (atTick >= tick || tick - atTick >= XP_HISTORY_TICKS)
		{
			return client.getSkillExperience(skill);
		}
		return xpHistory.get(skill)[atTick % XP_HISTORY_TICKS];
	}

	/**
	 * Whether this skill gained XP on {@code atTick} itself. Used to decide which skills a kill
	 * actually trained: combat XP lands on the tick the blow connects, so this distinguishes real
	 * combat XP from XP that merely arrived between kills (alching while ranging, a quest lamp).
	 */
	private boolean gainedXpAtTick(Skill skill, int atTick)
	{
		if (atTick < 1 || tick - (atTick - 1) >= XP_HISTORY_TICKS)
		{
			return false;
		}
		return xpAtTick(skill, atTick) > xpAtTick(skill, atTick - 1);
	}

	/**
	 * Whether a skill's XP registered near {@code eventTick} — the tick recorded for a hit, or for
	 * the kill it led to. Checked at the event tick and the tick before it.
	 *
	 * <p>The tick before is the one that matters in practice. The client grants the XP, then
	 * RuneLite posts {@link GameTick}, and only then posts {@link HitsplatApplied} for that same
	 * cycle — so by the time a hit is seen, {@code tick} has already advanced past the tick whose
	 * snapshot holds the XP. Measured in-client: a kill recorded at tick 27 had its XP land in the
	 * snapshot for tick 26, every time. Looking only at {@code eventTick} and later finds nothing,
	 * no skill is ever marked as trained, and the overlay never appears at all.
	 *
	 * <p>The window stays two ticks wide. Widening it further would start crediting XP that merely
	 * arrived nearby, which is the thing this check exists to rule out.
	 */
	private boolean xpArrivedFor(Skill skill, int eventTick)
	{
		return gainedXpAtTick(skill, eventTick - 1) || gainedXpAtTick(skill, eventTick);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (KillsToLevelConfig.GROUP.equals(e.getGroup()))
		{
			// Resize in place — changing the window shouldn't throw away kills already measured.
			for (KillXpEstimator est : estimators.values())
			{
				est.resize(config.sampleWindow());
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		// Only wipe the window on a genuine account change. A world hop also fires LOGGED_IN and must
		// keep the measurement going, so gate on the account hash.
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			long hash = client.getAccountHash();
			if (hash != lastAccountHash)
			{
				lastAccountHash = hash;
				buildEstimators();
				reset();
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		Actor actor = e.getActor();
		Hitsplat h = e.getHitsplat();
		if (actor instanceof NPC && h.isMine())
		{
			int idx = ((NPC) actor).getIndex();
			damagedNpcs.add(idx);
			lastHitTick.put(idx, tick);

			// Mark visibility right away rather than waiting for this target to die — a completed
			// kill still catches it below as a fallback, for the rare case this blow's XP is delayed
			// past the window.
			for (Skill s : COMBAT_SKILLS)
			{
				if (xpArrivedFor(s, tick))
				{
					lastTrainedTick.put(s, tick);
				}
			}
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		NPC npc = e.getNpc();
		boolean iDamaged = damagedNpcs.remove(npc.getIndex());
		Integer hitTick = lastHitTick.remove(npc.getIndex());
		if (iDamaged && npcUtil.isDying(npc))
		{
			// The corpse lingers several ticks after the killing blow, and by despawn we may already
			// have damaged the next target — pricing the kill here would fold that XP into this
			// snapshot. Price it at the killing blow instead, where the XP had just landed.
			int killTick = hitTick == null ? tick : hitTick;
			for (Skill s : COMBAT_SKILLS)
			{
				// Only feed a skill's estimator on a kill that actually trained it — otherwise a
				// kill that trained a different skill records this one's unchanged total, and that
				// flat entry sits in its rolling window diluting the rate the next time you switch
				// to training it.
				if (xpArrivedFor(s, killTick))
				{
					estimators.get(s).recordKill(xpAtTick(s, killTick));
					lastTrainedTick.put(s, tick);
				}
			}
			log.debug("kill priced at tick {}", killTick);
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		// Record before advancing, so slot[tick] holds the XP total at the END of tick `tick` —
		// StatChanged has already fired this tick, so a kill landed this tick is included.
		int slot = tick % XP_HISTORY_TICKS;
		for (Skill s : COMBAT_SKILLS)
		{
			xpHistory.get(s)[slot] = client.getSkillExperience(s);
		}
		tick++;
	}

	/** Kills to this skill's target, or {@link KillXpEstimator#UNKNOWN} if there's no estimate. */
	int killsToLevel(Skill skill)
	{
		KillXpEstimator est = estimators.get(skill);
		if (est == null)
		{
			return KillXpEstimator.UNKNOWN;
		}
		int xp = client.getSkillExperience(skill);
		long target = targetXp(skill, xp);
		if (target <= xp)
		{
			return KillXpEstimator.UNKNOWN;   // maxed, or already past the target
		}
		return est.killsToLevel(target - xp);
	}

	/**
	 * The XP we're counting towards: the target set on the skill tab in game if there is one ahead of
	 * us, otherwise the next level. Reading the game's own target is how the core XP tracker does it,
	 * so there's no goal UI to build and the number agrees with what the game already shows you.
	 */
	private long targetXp(Skill skill, int currentXp)
	{
		if (config.useInGameGoal())
		{
			int goalXp = client.getVarpValue(goalEndVarp(skill));
			if (goalXp > currentXp)
			{
				return goalXp;
			}
		}
		int level = Experience.getLevelForXp(currentXp);
		return level >= Experience.MAX_REAL_LEVEL ? currentXp : Experience.getXpForLevel(level + 1);
	}

	/** The level shown alongside a skill, or -1 when simply counting to the next level. */
	int goalLevel(Skill skill)
	{
		if (!config.useInGameGoal())
		{
			return -1;
		}
		int goalXp = client.getVarpValue(goalEndVarp(skill));
		return goalXp > client.getSkillExperience(skill) ? Experience.getLevelForXp(goalXp) : -1;
	}

	private static @Varp int goalEndVarp(Skill skill)
	{
		switch (skill)
		{
			case ATTACK:
				return VarPlayerID.XPDROPS_ATTACK_END;
			case STRENGTH:
				return VarPlayerID.XPDROPS_STRENGTH_END;
			case DEFENCE:
				return VarPlayerID.XPDROPS_DEFENCE_END;
			case HITPOINTS:
				return VarPlayerID.XPDROPS_HITPOINTS_END;
			case RANGED:
				return VarPlayerID.XPDROPS_RANGED_END;
			case MAGIC:
				return VarPlayerID.XPDROPS_MAGIC_END;
			default:
				throw new IllegalArgumentException("not a combat skill: " + skill);
		}
	}

	/**
	 * Whether this skill's number has enough samples behind it to be shown without qualification.
	 * A measurement exists from two kills — for a fixed monster that is already exact — but stays
	 * thin until {@link #MIN_SAMPLES}, so the overlay greys it rather than withholding it.
	 */
	boolean isConfident(Skill skill)
	{
		KillXpEstimator est = estimators.get(skill);
		return est != null && est.isConfident();
	}

	/** Kills measured for this skill so far. */
	int sampleCount(Skill skill)
	{
		KillXpEstimator est = estimators.get(skill);
		return est == null ? 0 : est.sampleCount();
	}

	/**
	 * Whether a trained skill should be on the panel at all. Hitpoints trains on every kill whatever
	 * your style, so it is always present and can be switched off to leave only the skill you chose
	 * to train.
	 */
	boolean isShown(Skill skill)
	{
		return skill != Skill.HITPOINTS || config.showHitpoints();
	}

	/**
	 * Whether this skill belongs on the panel right now: trained recently enough to still count as
	 * being in combat, and trained by the style you are currently using.
	 *
	 * <p>The second half is what makes switching style swap the rows on your next hit, rather than
	 * leaving the skill you just left sitting there for {@link #ACTIVE_TIMEOUT_TICKS} formatted
	 * identically to the live one. A style that trains several skills at once — Controlled,
	 * Longrange — stamps them all on the same tick, so they stay listed together.
	 *
	 * <p>Hitpoints is exempt because it trains on every point of damage whatever the style, so it is
	 * never the skill you switched away from. It also gains fractionally (1.33 per damage, rounded),
	 * so it legitimately misses the odd hit and would flicker if held to the same window.
	 */
	boolean isRecentlyTrained(Skill skill)
	{
		Integer t = lastTrainedTick.get(skill);
		if (t == null || tick - t > ACTIVE_TIMEOUT_TICKS)
		{
			return false;
		}
		if (skill == Skill.HITPOINTS)
		{
			return true;
		}
		return t >= latestStyleTrainedTick() - STYLE_SWITCH_SLACK_TICKS;
	}

	/**
	 * The most recent tick a style-determined skill was trained, ignoring Hitpoints — which every
	 * style trains and so says nothing about which style you are on.
	 */
	private int latestStyleTrainedTick()
	{
		int latest = Integer.MIN_VALUE;
		for (Map.Entry<Skill, Integer> e : lastTrainedTick.entrySet())
		{
			if (e.getKey() != Skill.HITPOINTS)
			{
				latest = Math.max(latest, e.getValue());
			}
		}
		return latest;
	}

	Set<Skill> combatSkills()
	{
		return COMBAT_SKILLS;
	}
}
