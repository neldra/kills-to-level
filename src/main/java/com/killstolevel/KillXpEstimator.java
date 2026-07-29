package com.killstolevel;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates XP-per-kill for a single skill by MEASURING it: at each credited kill the shell records
 * the player's cumulative skill XP, and the mean gain across a rolling window of the last
 * {@code windowSize} kills is the XP per kill. Because it divides real granted XP by real kills, it
 * is automatically correct for split attack styles (Controlled/Longrange), Magic's base-cast XP,
 * overkill, and slayer/bonus XP — the exact cases a {@code health x 4} heuristic gets wrong.
 *
 * <p>Cumulative snapshots (rather than per-tick deltas) are used deliberately: a monster's corpse
 * despawns several ticks after the killing blow that granted the XP, so the kill signal lags the XP
 * and a per-tick delta at despawn measures nothing. Differencing cumulative totals across a window
 * yields the correct total-XP / total-kills average regardless of that lag, or of several kills
 * landing together. Pure, unit-testable; the window is session state and is never persisted.
 */
final class KillXpEstimator
{
	/** Returned by {@link #killsToLevel(long)} when there is not yet a confident estimate. */
	static final int UNKNOWN = -1;

	private final int minSamples;
	private final Deque<Long> snapshots = new ArrayDeque<>();
	private int windowSize;

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

	/** Record a credited kill by the player's cumulative XP in this skill at the time of the kill. */
	void recordKill(long cumulativeSkillXp)
	{
		snapshots.addLast(cumulativeSkillXp);
		trim();
	}

	private void trim()
	{
		while (snapshots.size() > windowSize)
		{
			snapshots.removeFirst();
		}
	}

	/**
	 * Whether there is any measurement at all: two snapshots give one real interval, which for a
	 * fixed monster and style is already the exact XP per kill rather than an approximation. Below
	 * two there is no interval to divide, so there is genuinely nothing to report.
	 */
	boolean hasEstimate()
	{
		return snapshots.size() >= 2 && gained() > 0;
	}

	/**
	 * Whether the measurement has enough samples to be trusted without qualification. Everything
	 * from {@link #hasEstimate()} up to here is real but thin — one odd kill (a different monster,
	 * stray damage from someone else's target) still moves it noticeably — so the overlay shows it
	 * greyed rather than withholding it.
	 */
	boolean isConfident()
	{
		return snapshots.size() >= minSamples && gained() > 0;
	}

	/** Kills measured so far. */
	int sampleCount()
	{
		return snapshots.size();
	}

	int minSamples()
	{
		return minSamples;
	}

	double xpPerKill()
	{
		return (double) gained() / (snapshots.size() - 1);
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

	/** XP gained across the window (newest snapshot minus oldest); 0 until there are two snapshots. */
	private long gained()
	{
		if (snapshots.size() < 2)
		{
			return 0;
		}
		return snapshots.peekLast() - snapshots.peekFirst();
	}
}
