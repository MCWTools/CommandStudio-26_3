package khoa.studio.run;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/** Runs a command {@code count} times, {@code delay} ticks apart (0 = all in the same tick). Driven by the client tick. */
public final class CommandScheduler {
	public static final int MAX_COUNT = 10000;
	public static final int MAX_DELAY = 72000;

	/** The scheduler the game ticks; tests build their own with a recording sink. */
	public static final CommandScheduler GAME = new CommandScheduler(CommandSender::send);

	private static final class Job {
		final String command;
		int remaining;
		final int delay;
		int wait;

		Job(String command, int remaining, int delay) {
			this.command = command;
			this.remaining = remaining;
			this.delay = delay;
		}
	}

	private final List<Job> jobs = new ArrayList<>();
	private final Consumer<String> sink;

	public CommandScheduler(Consumer<String> sink) {
		this.sink = sink;
	}

	public synchronized void schedule(int count, int delay, String command) {
		jobs.add(new Job(command, count, delay));
	}

	/** Cancels everything that is still waiting; returns how many commands were dropped. */
	public synchronized int cancelAll() {
		int n = pending();
		jobs.clear();
		return n;
	}

	public synchronized int pending() {
		return jobs.stream().mapToInt(j -> j.remaining).sum();
	}

	/** First run happens on the first tick after scheduling, the next ones {@code delay} ticks after each other. */
	public synchronized void tick() {
		Iterator<Job> it = jobs.iterator();
		while (it.hasNext()) {
			Job job = it.next();
			if (job.wait > 0) {
				job.wait--;
				continue;
			}
			if (job.delay == 0) {
				while (job.remaining > 0) {
					sink.accept(job.command);
					job.remaining--;
				}
			} else {
				sink.accept(job.command);
				job.remaining--;
				job.wait = job.delay - 1;
			}
			if (job.remaining <= 0) {
				it.remove();
			}
		}
	}
}
