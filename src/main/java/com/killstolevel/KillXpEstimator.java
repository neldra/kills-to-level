package com.killstolevel;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates XP-per-kill for a single skill by MEASURING it: at each credited kill the shell reports
 * the player's cumulative skill XP, the kill is priced as the gain since the previous credited kill,
 * and the mean across a rolling window of the last {@code windowSize} kill gains is the XP per kill.
 * Because it divides real granted XP by real kills, it is automatically correct for split attack
 * styles (Controlled/Longrange), Magic's base-cast XP, overkill, and per-monster XP multipliers —
 * the exact cases a {@code health x 4} heuristic gets wrong.
 *
 * <p>Each call passes CUMULATIVE XP (not a precomputed gain) because the kill signal lags the XP by
 * the corpse despawn, and several kills can land together: an AoE stack's first despawn carries the
 * whole stack's gain and the rest arrive as zero-gain kills, which the mean averages correctly.
 *
 * <p>The shell also passes a ceiling on how much XP the interval since the previous kill could
 * plausibly contain, derived from the damage actually dealt — combat XP is paid per point of
 * damage, so a gain far beyond what the damage supports cannot be combat XP. Such a gain (an XP
 * lamp, a quest reward) is excised: the baseline advances past it but no sample is recorded, so the
 * estimate keeps its last good value instead of pricing one enormous fake kill. Pure, unit-testable;
 * the window is session state and is never persisted.
 */
final class KillXpEstimator
{
	/** Returned by {@link #killsToLevel(long)} when there is not yet a confident estimate. */
	static final int UNKNOWN = -1;

	private final int minSamples;
	/** XP gained by each of the last {@code windowSize} credited kills, oldest first. */
	private final Deque<Long> samples = new ArrayDeque<>();
	private int windowSize;
	private long baselineXp;
	private boolean hasBaseline;
	/**
	 * Set while an excised gain's AoE companions may still be arriving: the excised head of a stack
	 * carried the whole stack's XP, so its trailing zero-gain kills must be dropped with it — kept,
	 * they would fill the window with zeros no gain compensates for and drag the mean towards zero.
	 */
	private boolean discardZeroGains;

	KillXpEstimator(int windowSize, int minSamples)
	{
		this.windowSize = windowSize;
		this.minSamples = minSamples;
	}

	/** Change the window size, keeping the most recent samples rather than discarding the measurement. */
	void resize(int newWindowSize)
	{
		windowSize = newWindowSize;
		trim();
	}

	/**
	 * Record a credited kill by the player's cumulative XP in this skill at the time of the kill,
	 * bounded by the most XP the interval since the previous kill could plausibly hold.
	 *
	 * @return false when the gain exceeded the bound and was excised rather than recorded
	 */
	boolean recordKill(long cumulativeSkillXp, long maxPlausibleGain)
	{
		if (!hasBaseline)
		{
			hasBaseline = true;
			baselineXp = cumulativeSkillXp;
			return true;
		}
		long gain = cumulativeSkillXp - baselineXp;
		if (gain < 0)
		{
			// Kills are priced at their killing blow but credited at despawn, and despawns are not
			// ordered by blow: a lingering corpse can arrive priced OLDER than the previous kill.
			// Never move the baseline backwards — that would count the difference twice into the
			// next kill — and never record the negative gain.
			return true;
		}
		baselineXp = cumulativeSkillXp;
		if (gain > maxPlausibleGain)
		{
			discardZeroGains = true;
			return false;
		}
		if (gain == 0 && discardZeroGains)
		{
			return true;
		}
		if (gain > 0)
		{
			discardZeroGains = false;
		}
		samples.addLast(gain);
		trim();
		return true;
	}

	private void trim()
	{
		while (samples.size() > windowSize)
		{
			samples.removeFirst();
		}
	}

	/**
	 * Whether there is any measurement at all: one gain between two kills, which for a fixed monster
	 * and style is already the exact XP per kill rather than an approximation. Before that there is
	 * genuinely nothing to report.
	 */
	boolean hasEstimate()
	{
		return !samples.isEmpty() && gained() > 0;
	}

	/**
	 * Whether the measurement has enough samples to be trusted without qualification. Everything
	 * from {@link #hasEstimate()} up to here is real but thin — one odd kill (a different monster,
	 * stray damage from someone else's target) still moves it noticeably — so the overlay shows it
	 * greyed rather than withholding it.
	 */
	boolean isConfident()
	{
		return samples.size() >= minSamples && gained() > 0;
	}

	/** Kill gains measured so far — one fewer than kills seen, the first kill being the baseline. */
	int sampleCount()
	{
		return samples.size();
	}

	double xpPerKill()
	{
		return (double) gained() / samples.size();
	}

	int killsToLevel(long xpRemaining)
	{
		if (!hasEstimate())
		{
			return UNKNOWN;
		}
		if (xpRemaining <= 0)
		{
			return 0;
		}
		return (int) Math.ceil(xpRemaining / xpPerKill());
	}

	/** XP gained across the window; 0 until there is a sample. */
	private long gained()
	{
		long sum = 0;
		for (long gain : samples)
		{
			sum += gain;
		}
		return sum;
	}
}
