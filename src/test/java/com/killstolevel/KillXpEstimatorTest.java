package com.killstolevel;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The pure core: measure XP-per-kill from a rolling window of cumulative-XP snapshots taken at each
 * credited kill (never health x4), then derive kills-to-next-level. Cumulative (not delta) so it is
 * robust to the death-animation lag between the killing blow's XP and the corpse's despawn.
 */
public class KillXpEstimatorTest
{
	@Test
	public void freshEstimatorHasNoEstimate()
	{
		KillXpEstimator e = new KillXpEstimator(20, 5);
		assertFalse(e.hasEstimate());
		assertEquals(KillXpEstimator.UNKNOWN, e.killsToLevel(1000));
	}

	/**
	 * Two snapshots is one real interval, and for a fixed monster that interval is the exact xp per
	 * kill — so there is a measurement well before {@code minSamples}. What the minimum buys is
	 * confidence against mixed content, so the two are reported separately: the shell shows a thin
	 * measurement greyed rather than withholding it.
	 */
	@Test
	public void measurementArrivesAtTwoSnapshotsConfidenceAtTheMinimum()
	{
		KillXpEstimator e = new KillXpEstimator(20, 5);

		e.recordKill(100);
		assertFalse("one snapshot is no interval, so nothing to measure", e.hasEstimate());
		assertFalse(e.isConfident());

		e.recordKill(200);
		assertTrue("two snapshots give one real interval", e.hasEstimate());
		assertEquals(100.0, e.xpPerKill(), 1e-9);
		assertFalse("but not confident until the minimum", e.isConfident());

		e.recordKill(300);
		e.recordKill(400);
		assertTrue(e.hasEstimate());
		assertFalse("still one short of the minimum", e.isConfident());

		e.recordKill(500);
		assertTrue("the minimum is reached", e.isConfident());
		assertEquals("and the rate never moved", 100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void steadyRateGivesXpPerKill()
	{
		KillXpEstimator e = new KillXpEstimator(20, 5);
		for (long xp = 100; xp <= 600; xp += 100)   // 6 snapshots: (600-100)/(6-1) = 100/kill
		{
			e.recordKill(xp);
		}
		assertTrue(e.hasEstimate());
		assertEquals(100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void killsToLevelRoundsUp()
	{
		KillXpEstimator e = new KillXpEstimator(20, 5);
		for (long xp = 100; xp <= 600; xp += 100)   // 100 xp/kill
		{
			e.recordKill(xp);
		}
		assertEquals(10, e.killsToLevel(1000));
		assertEquals(11, e.killsToLevel(1050));      // ceil(10.5)
		assertEquals(1, e.killsToLevel(1));
		assertEquals(0, e.killsToLevel(0));
	}

	@Test
	public void windowEvictsOldAndReconverges()
	{
		KillXpEstimator e = new KillXpEstimator(5, 3);
		long xp = 0;
		for (int i = 0; i < 4; i++)                  // early kills at 100 xp each
		{
			xp += 100;
			e.recordKill(xp);
		}
		for (int i = 0; i < 5; i++)                  // recent kills at 300 xp each
		{
			xp += 300;
			e.recordKill(xp);
		}
		assertEquals(300.0, e.xpPerKill(), 1e-9);    // window holds the last 5 -> all +300
	}

	@Test
	public void aoeKillsCountedTogetherAverageCorrectly()
	{
		// Three kills whose XP all landed on the same tick, then their corpses despawn one-by-one:
		// snapshots read the same cumulative XP, but all three are still counted.
		KillXpEstimator e = new KillXpEstimator(20, 3);
		e.recordKill(1000);   // baseline kill
		e.recordKill(1300);   // +300 from a 3-kill barrage already applied
		e.recordKill(1300);
		e.recordKill(1300);
		// (1300-1000) over 3 kills = 100/kill, not 300
		assertEquals(100.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void zeroXpAcrossWindowHasNoEstimate()
	{
		KillXpEstimator e = new KillXpEstimator(20, 5);
		for (int i = 0; i < 6; i++)                  // same cumulative xp -> nothing gained
		{
			e.recordKill(500);
		}
		assertFalse(e.hasEstimate());
		assertEquals(KillXpEstimator.UNKNOWN, e.killsToLevel(1000));
	}

	@Test
	public void shrinkingWindowKeepsRecentSamples()
	{
		KillXpEstimator e = new KillXpEstimator(20, 3);
		long xp = 0;
		for (int i = 0; i < 5; i++)                  // 100/kill
		{
			xp += 100;
			e.recordKill(xp);
		}
		for (int i = 0; i < 4; i++)                  // then 300/kill
		{
			xp += 300;
			e.recordKill(xp);
		}
		e.resize(4);                                 // keep only the last 4 -> all +300
		assertTrue(e.hasEstimate());
		assertEquals(300.0, e.xpPerKill(), 1e-9);
	}

	@Test
	public void growingWindowPreservesExistingSamples()
	{
		KillXpEstimator e = new KillXpEstimator(5, 3);
		long xp = 0;
		for (int i = 0; i < 5; i++)
		{
			xp += 100;
			e.recordKill(xp);
		}
		e.resize(50);                                // widening must not discard the measurement
		assertTrue(e.hasEstimate());
		assertEquals(100.0, e.xpPerKill(), 1e-9);
	}
}
