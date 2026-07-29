package com.killstolevel;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class KillsToLevelPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(KillsToLevelPlugin.class);
		RuneLite.main(args);
	}
}
