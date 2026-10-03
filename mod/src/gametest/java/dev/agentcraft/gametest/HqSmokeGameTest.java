package dev.agentcraft.gametest;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class HqSmokeGameTest {
	@GameTest(maxTicks = 20)
	public void hqCommandBuildsPlotZeroAndPublishes69Anchors(GameTestHelper helper) {
		helper.getLevel().getServer().getCommands().performPrefixedCommand(
			helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(),
			"agentcraft hq"
		);

		Anchors.Layout layout = Anchors.current();
		helper.assertValueEqual(layout.anchors().size(), 69, "published anchor count");

		Anchor spawn = layout.get(AnchorNames.SPAWN);
		if (spawn == null) {
			helper.fail("Plot 0 layout did not publish the spawn anchor");
		}
		helper.assertValueEqual(spawn.x(), 0.5, "plot 0 spawn x");
		helper.assertValueEqual(spawn.y(), 66.0, "plot 0 spawn y");
		helper.assertValueEqual(spawn.z(), 22.5, "plot 0 spawn z");
		helper.succeed();
	}
}
