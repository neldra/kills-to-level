package com.killstolevel;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Skill;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Shows kills-to-next-level for the combat skill(s) you're actively training. Renders nothing when
 * you're not training combat. A number is prefixed "~" and greyed until enough kills back it up —
 * it is measured from the second kill onwards either way — and "Measuring" means there is
 * nothing measured yet, or the kills so far were ambiguous (an AoE barrage whose XP all landed
 * together).
 */
class KillsToLevelOverlay extends OverlayPanel
{
	private final KillsToLevelPlugin plugin;

	@Inject
	KillsToLevelOverlay(KillsToLevelPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();

		boolean any = false;
		for (Skill skill : plugin.combatSkills())
		{
			if (!plugin.isRecentlyTrained(skill) || !plugin.isShown(skill))
			{
				continue;
			}
			// "~" and grey until the skill has enough kills behind it to trust the number without
			// qualification. The figure is measured either way, never guessed — both say "still
			// confirming this", so a usable answer arrives on the second kill instead of the fifth.
			// The prefix exists because colour alone is easy to miss and impossible to describe.
			int kills = plugin.killsToLevel(skill);
			boolean confident = plugin.isConfident(skill);
			String right = kills == KillXpEstimator.UNKNOWN
				? "Measuring"
				: (confident ? "" : "~") + kills;
			Color rightColor = confident ? Color.WHITE : Color.LIGHT_GRAY;

			// Name the target when it's an XP goal, so the number isn't mistaken for the next level.
			int goal = plugin.goalLevel(skill);
			String left = goal == -1 ? skill.getName() : skill.getName() + " (" + goal + ")";

			panelComponent.getChildren().add(LineComponent.builder()
				.left(left).right(right).rightColor(rightColor).build());
			any = true;
		}

		if (!any)
		{
			return null;
		}

		panelComponent.getChildren().add(0, TitleComponent.builder().text("Kills to level").build());
		return super.render(graphics);
	}
}
