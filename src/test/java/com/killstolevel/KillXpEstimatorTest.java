package com.killstolevel;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The pure core: price each credited kill as the cumulative-XP gain since the previous one (never
 * health x4), average a rolling window of those gains, and derive kills-to-next-level. Cumulative
 * input so it is robust to the death-animation lag between the killing blow's XP and the corpse's
 * despawn; a plausibility bound on each gain so foreign XP (a lamp, a quest reward) is excised
 * instead of being priced as one enormous kill.
 */
public class KillXpEstimatorTest
{
	/** For scenarios where the guard is not what's under test. */
	private static final long UNBOUNDED = Long.MAX_VALUE;

	@Test
	public void freshEstimatorHasNoEstimate()
	{
		KillXpEstimator e = new KillXpEstimator(19, 4);
		assertFalse(e.hasEstimate());
		assertEquals(KillXpEstimator.UNKNOWN, e.killsToLevel(1000));
	}

	/**
	 * The first kill is only a baseline, but the second gives one real gain, and for a fixed
	 * monster that gain is the exact xp per kill — so there is a measurement well before
	 * {@code minSamples}. What the minimum buys is confidence against mixed content, so the two are
	 * reported separately: the shell shows a thin measurement greyed rather than withholding it.
	 */
	@Test
	public void measurementArrivesAtTheSecondKillConfidenceAtTheMinimum()
	{
		KillXpEstimator e = new KillXpEstimator(19, 4);

		e.recordKill(100, UNBOUNDED);
		assertFalse("the first kill is only a baseline, nothing to measure", e.hasEstimate());
		assertFalse(e.isConfident());

		e.recordKill(200, UNBOUNDED);
		assertTrue("the second kill gives one real gain", e.hasEstimate());
		assertEquals(100.0, e.xpPerKill(), 1e-9);
		assertFalse("but not confident until the minimum", e.isConfident());

		e.recordKill(300, UNBOUNDED);
		e.recordKill(400, UNBOUNDED);
		assertTrue(e.hasEstimate());
		assertFalse("still one short of the minimum", e.isConfident());

		e.recordKill(500, UNBOUNDED);
		assertTrue("the minimum is reached", e.isConfident());
		assertEquals("and the rate never moved", 100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void steadyRateGivesXpPerKill()
	{
		KillXpEstimator e = new KillXpEstimator(19, 4);
		for (long xp = 100; xp <= 600; xp += 100)   // 6 kills: 5 gains of 100
		{
			e.recordKill(xp, UNBOUNDED);
		}
		assertTrue(e.hasEstimate());
		assertEquals(100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void killsToLevelRoundsUp()
	{
		KillXpEstimator e = new KillXpEstimator(19, 4);
		for (long xp = 100; xp <= 600; xp += 100)   // 100 xp/kill
		{
			e.recordKill(xp, UNBOUNDED);
		}
		assertEquals(10, e.killsToLevel(1000));
		assertEquals(11, e.killsToLevel(1050));      // ceil(10.5)
		assertEquals(1, e.killsToLevel(1));
		assertEquals(0, e.killsToLevel(0));
	}

	@Test
	public void windowEvictsOldAndReconverges()
	{
		KillXpEstimator e = new KillXpEstimator(4, 2);
		long xp = 0;
		for (int i = 0; i < 4; i++)                  // early kills at 100 xp each
		{
			xp += 100;
			e.recordKill(xp, UNBOUNDED);
		}
		for (int i = 0; i < 5; i++)                  // recent kills at 300 xp each
		{
			xp += 300;
			e.recordKill(xp, UNBOUNDED);
		}
		assertEquals(300.0, e.xpPerKill(), 1e-9);    // window holds the last 4 gains -> all +300
	}

	@Test
	public void aoeKillsCountedTogetherAverageCorrectly()
	{
		// Three kills whose XP all landed on the same tick, then their corpses despawn one-by-one:
		// the first despawn carries the whole gain and the rest read zero, but all three count.
		KillXpEstimator e = new KillXpEstimator(19, 2);
		e.recordKill(1000, UNBOUNDED);   // baseline kill
		e.recordKill(1300, UNBOUNDED);   // +300 from a 3-kill barrage already applied
		e.recordKill(1300, UNBOUNDED);
		e.recordKill(1300, UNBOUNDED);
		// 300 over 3 kills = 100/kill, not 300
		assertEquals(100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void zeroXpAcrossWindowHasNoEstimate()
	{
		KillXpEstimator e = new KillXpEstimator(19, 4);
		for (int i = 0; i < 6; i++)                  // same cumulative xp -> nothing gained
		{
			e.recordKill(500, UNBOUNDED);
		}
		assertFalse(e.hasEstimate());
		assertEquals(KillXpEstimator.UNKNOWN, e.killsToLevel(1000));
	}

	@Test
	public void shrinkingWindowKeepsRecentSamples()
	{
		KillXpEstimator e = new KillXpEstimator(19, 2);
		long xp = 0;
		for (int i = 0; i < 5; i++)                  // 100/kill
		{
			xp += 100;
			e.recordKill(xp, UNBOUNDED);
		}
		for (int i = 0; i < 4; i++)                  // then 300/kill
		{
			xp += 300;
			e.recordKill(xp, UNBOUNDED);
		}
		e.resize(3);                                 // keep only the last 3 gains -> all +300
		assertTrue(e.hasEstimate());
		assertEquals(300.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void growingWindowPreservesExistingSamples()
	{
		KillXpEstimator e = new KillXpEstimator(4, 2);
		long xp = 0;
		for (int i = 0; i < 5; i++)
		{
			xp += 100;
			e.recordKill(xp, UNBOUNDED);
		}
		e.resize(49);                                // widening must not discard the measurement
		assertTrue(e.hasEstimate());
		assertEquals(100.0, e.xpPerKill(), 1e-9);
	}

	/**
	 * A resize keeps the most recent GAINS, not one more: with a mixed window the two readings give
	 * different means, so this pins the interval interpretation the config conversion depends on.
	 */
	@Test
	public void resizeCountsGainsNotKills()
	{
		KillXpEstimator e = new KillXpEstimator(19, 2);
		long xp = 0;
		for (int i = 0; i < 5; i++)                  // 4 gains of 20
		{
			xp += 20;
			e.recordKill(xp, UNBOUNDED);
		}
		for (int i = 0; i < 4; i++)                  // then 4 gains of 100
		{
			xp += 100;
			e.recordKill(xp, UNBOUNDED);
		}
		e.resize(4);
		// keeping the last 4 gains means all +100; keeping 5 would mix a 20 in for a mean of 84
		assertEquals(100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void implausibleGainIsExcisedAndTheBaselineMovesPastIt()
	{
		KillXpEstimator e = new KillXpEstimator(19, 2);
		e.recordKill(1000, 280);
		e.recordKill(1020, 280);
		assertEquals(20.0, e.xpPerKill(), 1e-9);

		assertFalse("a lamp-sized gain reports as excised", e.recordKill(6040, 280));
		assertEquals("and the estimate keeps its value", 20.0, e.xpPerKill(), 1e-9);

		assertTrue(e.recordKill(6060, 280));
		assertEquals("the next kill prices from beyond the lamp, not across it", 20.0, e.xpPerKill(), 1e-9);
		assertEquals(2, e.sampleCount());
	}

	@Test
	public void negativeGainIsRejectedWithoutMovingTheBaselineBackwards()
	{
		// Despawns are not ordered by killing blow, so a lingering corpse can arrive priced with an
		// OLDER snapshot than the previous kill's. It must neither enter the mean nor drag the
		// baseline back — a backward baseline would count the difference twice into the next kill.
		KillXpEstimator e = new KillXpEstimator(19, 2);
		e.recordKill(1000, UNBOUNDED);
		e.recordKill(1100, UNBOUNDED);
		assertTrue("out-of-order pricing is tolerated, not excised", e.recordKill(1050, UNBOUNDED));
		assertEquals(1, e.sampleCount());

		e.recordKill(1200, UNBOUNDED);
		assertEquals("a backward baseline would have read this gain as 150", 100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void zeroGainsAreDroppedAfterAnExcisionUntilARealGainLands()
	{
		// An excised AoE head carried the whole stack's XP; its trailing zero-gain kills must be
		// dropped with it, or the window fills with zeros that no gain compensates for.
		KillXpEstimator e = new KillXpEstimator(19, 2);
		e.recordKill(1000, 300);
		e.recordKill(1020, 300);                     // one clean gain of 20

		assertFalse(e.recordKill(7020, 300));        // stack head, over the bound
		e.recordKill(7020, 300);                     // trailing companions of the excised head
		e.recordKill(7020, 300);
		assertEquals("the companions must not dilute the estimate", 20.0, e.xpPerKill(), 1e-9);
		assertEquals(1, e.sampleCount());

		e.recordKill(7040, 300);                     // a real gain ends the suppression
		e.recordKill(7040, 300);                     // so an ordinary AoE zero counts again
		assertEquals(3, e.sampleCount());
		assertEquals((20.0 + 20.0 + 0.0) / 3, e.xpPerKill(), 1e-9);
	}
}
