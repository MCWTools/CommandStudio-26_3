package khoa.studio.run;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContextBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * Checks a command against the command tree the connected server sent to the client (the game's own parser, so the syntax is
 * always the current version's). Returns null when the command parses completely or when there is nothing to check against.
 */
public final class CommandCheck {
	private CommandCheck() {
	}

	public static String validate(String command) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return null;
		}
		String text = command.trim();
		if (text.startsWith("/")) {
			text = text.substring(1);
		}
		CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
		ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(new StringReader(text), connection.getSuggestionsProvider());
		if (parse.getReader().canRead()) {
			if (!parse.getExceptions().isEmpty()) {
				return parse.getExceptions().values().iterator().next().getMessage();
			}
			return "Unknown command or argument at: " + parse.getReader().getRemaining();
		}
		CommandContextBuilder<ClientSuggestionProvider> context = parse.getContext().getLastChild();
		if (context.getCommand() == null) {
			return "Incomplete command";
		}
		return null;
	}
}
