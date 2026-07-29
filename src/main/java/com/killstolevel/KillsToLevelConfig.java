package com.killstolevel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(KillsToLevelConfig.GROUP)
public interface KillsToLevelConfig extends Config
{
	String GROUP = "killstolevel";

	@Range(min = 5, max = 100)
	@ConfigItem(
		keyName = "sampleWindow",
		name = "Sample window (kills)",
		description = "How many recent kills to average your XP over. Larger = steadier; smaller = reacts faster when you switch method.",
		position = 1
	)
	default int sampleWindow()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "useInGameGoal",
		name = "Count to your XP target",
		description = "Count kills to the XP target you set on the skill tab in game, instead of to your next level. Falls back to the next level when no target is set.",
		position = 2
	)
	default boolean useInGameGoal()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showHitpoints",
		name = "Show Hitpoints",
		description = "Hitpoints trains on every kill whatever your attack style, so it is always listed. Turn this off to show only the skill your style is training.",
		position = 3
	)
	default boolean showHitpoints()
	{
		return true;
	}
}
