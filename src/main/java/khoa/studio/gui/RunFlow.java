package khoa.studio.gui;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.screens.Screen;

import khoa.api.gui.KhoaConfirmScreen;
import khoa.studio.persistence.StudioState;
import khoa.studio.run.CommandSender;

/** The Run button of every generator: one command runs at once, several ask for confirmation first (the server applies its normal permissions). */
final class RunFlow {
	private RunFlow() {
	}

	/** @param say receives the status line shown under the output. */
	static void run(Screen parent, String output, Consumer<String> say) {
		List<String> lines = CommandSender.lines(output);
		if (lines.isEmpty()) {
			say.accept("Nothing to run");
			return;
		}
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			say.accept("Not in a world");
			return;
		}
		if (lines.size() == 1) {
			send(lines, say);
			return;
		}
		KhoaConfirmScreen.open(parent, "Run " + lines.size() + " commands?", lines, "Run all", () -> send(lines, say));
	}

	private static void send(List<String> lines, Consumer<String> say) {
		for (String line : lines) {
			CommandSender.send(line);
		}
		StudioState.saveNow();
		say.accept("Sent " + lines.size() + " command(s); the configuration is kept");
	}
}
