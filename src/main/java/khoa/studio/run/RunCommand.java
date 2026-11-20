package khoa.studio.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser of {@code /runcommand <count> [<delay>] "<command>"}. The command must be in double quotes (so it can carry any prefix:
 * {@code /}, none, or another mod's such as {@code #} or {@code .}); without quotes the user gets an error instead of a guess.
 */
public final class RunCommand {
	public record Parsed(int count, int delay, String command) {
	}

	/** Either {@code parsed} or {@code error} is set. */
	public record Result(Parsed parsed, String error) {
		static Result error(String message) {
			return new Result(null, message);
		}
	}

	private static final Pattern DELAY = Pattern.compile("^(\\d+)\\s+(.+)$", Pattern.DOTALL);

	private RunCommand() {
	}

	/** {@code rest} is everything after the count. */
	public static Result parse(int count, String rest) {
		if (count < 1) {
			return Result.error("The count must be 1 or more");
		}
		if (count > CommandScheduler.MAX_COUNT) {
			return Result.error("The count is at most " + CommandScheduler.MAX_COUNT);
		}
		String text = rest.trim();
		int delay = 0;
		Matcher m = DELAY.matcher(text);
		if (m.matches()) {
			try {
				delay = Integer.parseInt(m.group(1));
			} catch (NumberFormatException e) {
				return Result.error("The delay is too large");
			}
			if (delay > CommandScheduler.MAX_DELAY) {
				return Result.error("The delay is at most " + CommandScheduler.MAX_DELAY + " ticks");
			}
			text = m.group(2).trim();
		}
		if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
			return Result.error("Put the command in double quotes, e.g. /runcommand 20 1 \"/summon iron_golem\" (works for /commands and other mods' prefixes like # or .)");
		}
		String command = text.substring(1, text.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim();
		if (command.isEmpty()) {
			return Result.error("The command in quotes is empty");
		}
		return new Result(new Parsed(count, delay, command), null);
	}
}
