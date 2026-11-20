package khoa.studio;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.network.chat.Component;

import khoa.studio.gui.CommandStudioScreen;
import khoa.studio.persistence.StudioState;
import khoa.studio.run.CommandScheduler;
import khoa.studio.run.RunCommand;

/**
 * Entry point: {@code /commandstudio} opens the GUI (a command runs inside the chat screen, so the screen opens on the next tick);
 * {@code /runcommand <count> [<delay>] "<command>"} repeats a command, {@code /runcommand stop} cancels what is still queued.
 */
public class CommandStudioClient implements ClientModInitializer {
	private static boolean openNext;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("commandstudio").executes(c -> {
			openNext = true;
			return 1;
		})));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("commandstudiotest").executes(c -> {
			khoa.studio.gui.StudioSelfTest.requestStart();
			return 1;
		})));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("runcommand")
			.then(ClientCommands.literal("stop").executes(c -> {
				int dropped = CommandScheduler.GAME.cancelAll();
				c.getSource().sendFeedback(Component.literal("Stopped; " + dropped + " queued run(s) dropped"));
				return 1;
			}))
			.then(ClientCommands.argument("count", IntegerArgumentType.integer(1, CommandScheduler.MAX_COUNT))
				.then(ClientCommands.argument("command", StringArgumentType.greedyString()).executes(c -> {
					RunCommand.Result result = RunCommand.parse(IntegerArgumentType.getInteger(c, "count"), StringArgumentType.getString(c, "command"));
					if (result.error() != null) {
						c.getSource().sendError(Component.literal(result.error()));
						return 0;
					}
					RunCommand.Parsed p = result.parsed();
					CommandScheduler.GAME.schedule(p.count(), p.delay(), p.command());
					c.getSource().sendFeedback(Component.literal("Queued " + p.count() + "x " + p.command() + (p.delay() == 0 ? " (all at once)" : ", every " + p.delay() + " tick(s)")));
					return 1;
				})))));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			khoa.studio.gui.StudioSelfTest.tick(mc);
			StudioState.tick();
			if (mc.player != null) {
				CommandScheduler.GAME.tick();
			}
			if (openNext) {
				openNext = false;
				khoa.studio.gui.Sidebar.openById(StudioState.get().lastGenerator);
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> StudioState.saveNow());
	}
}
