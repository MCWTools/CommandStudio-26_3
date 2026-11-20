package khoa.studio.run;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Sends one line the way the game would when the player typed it in chat:
 * {@code /cmd} and a bare {@code cmd} are commands (client commands such as Fabric's included), while a line that starts with
 * another symbol ({@code #}, {@code .}, {@code !}, ...) is sent as chat so the mod that owns that prefix can intercept it.
 */
public final class CommandSender {
	private CommandSender() {
	}

	/** Splits generator output into command lines (blank lines dropped). */
	public static List<String> lines(String output) {
		List<String> out = new ArrayList<>();
		for (String line : output.split("\n")) {
			if (!line.isBlank()) {
				out.add(line.trim());
			}
		}
		return out;
	}

	/** Returns false when there is no connection (not in a world). */
	public static boolean send(String line) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		String text = line.trim();
		if (connection == null || text.isEmpty()) {
			return false;
		}
		if (text.startsWith("/")) {
			connection.sendCommand(text.substring(1));
		} else if (Character.isLetter(text.charAt(0))) {
			connection.sendCommand(text);
		} else {
			connection.sendChat(text);
		}
		return true;
	}
}
