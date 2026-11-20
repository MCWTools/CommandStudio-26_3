package khoa.studio.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import khoa.api.picker.KhoaItemPicker;
import khoa.studio.give.GiveModel;
import khoa.studio.persistence.StudioState;

/**
 * In-process GUI test: drives the real screens the way the mouse and keyboard do, by delivering MouseButtonEvent /
 * CharacterEvent / KeyEvent to the open Screen (the same calls Minecraft's input handlers make), one step every few ticks,
 * and checks the visible state after each step. Results go to config/commandstudio-selftest.log (and chat).
 * Start it with {@code /commandstudiotest} in a world, or launch with {@code -Dcommandstudio.autotest=true}: it then waits
 * for the world to load, runs once and closes the game.
 */
public final class StudioSelfTest {
	private record Step(String name, Runnable action, Supplier<String> check, int delay) {
	}

	private static final boolean AUTO = System.getProperty("commandstudio.autotest") != null;
	private static final List<Step> steps = new ArrayList<>();
	private static boolean started;
	private static boolean autoDone;
	private static int index = -1;
	private static int wait;
	private static boolean acted;
	private static int worldTicks;
	private static int failures;
	private static int passes;
	private static Path log;

	private StudioSelfTest() {
	}

	public static void requestStart() {
		if (!started) {
			started = true;
			build();
			index = 0;
			acted = false;
			wait = 0;
			failures = 0;
			passes = 0;
			log = FabricLoader.getInstance().getConfigDir().resolve("commandstudio-selftest.log");
			try {
				Files.writeString(log, "", StandardCharsets.UTF_8);
			} catch (IOException ignored) {
				// logging is best effort
			}
			write("self test started, " + steps.size() + " steps");
		}
	}

	private static int diag;

	public static void tick(Minecraft mc) {
		if (AUTO && !autoDone && !started) {
			if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen && mc.player != null) {
				mc.player.respawn(); // a previous run left the dev player dead
			}
			if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
				mc.gui.setScreen(null); // the window lost focus (nobody is at the PC): the game paused itself
			}
			if (++diag % 200 == 0) {
				System.out.println("[CommandStudio selftest] waiting to start: screen=" + mc.gui.screen() + " player=" + (mc.player != null) + " worldTicks=" + worldTicks);
			}
			worldTicks = mc.player != null && mc.level != null && mc.gui.screen() == null ? worldTicks + 1 : 0;
			if (worldTicks > 80) {
				autoDone = true;
				// the test world keeps its clock between runs: make sure mobs cannot kill the player mid test
				for (String c : new String[] { "gamemode creative", "time set day", "difficulty normal" }) {
					mc.player.connection.sendCommand(c);
				}
				requestStart();
			}
		}
		if (!started || index < 0) {
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		if (index >= steps.size()) {
			write("DONE: " + passes + " passed, " + failures + " failed");
			started = false;
			index = -1;
			if (AUTO) {
				mc.stop();
			}
			return;
		}
		Step step = steps.get(index);
		if (!acted) {
			write("step " + (index + 1) + " " + step.name());
			try {
				step.action().run();
			} catch (Throwable t) {
				fail(step, "exception " + t);
				for (StackTraceElement e : t.getStackTrace()) {
					write("    at " + e);
					if (e.getClassName().startsWith("java.")) {
						break;
					}
				}
				index++;
				return;
			}
			acted = true;
			wait = step.delay();
			return;
		}
		acted = false;
		String problem;
		try {
			problem = step.check().get();
		} catch (Throwable t) {
			problem = "check threw " + t;
		}
		if (problem == null) {
			passes++;
			write("   ok");
		} else {
			fail(step, problem);
		}
		index++;
	}

	private static void fail(Step step, String why) {
		failures++;
		write("   FAIL: " + why);
	}

	private static void write(String line) {
		System.out.println("[CommandStudio selftest] " + line);
		try {
			if (log != null) {
				Files.writeString(log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		} catch (IOException ignored) {
			// logging is best effort
		}
	}

	// ------------------------------------------------------------------ input, delivered like the real devices

	private static Screen screen() {
		return Minecraft.getInstance().gui.screen();
	}

	private static void click(double[] at) {
		if (at == null) {
			throw new IllegalStateException("target not on screen (button or widget missing)");
		}
		MouseButtonEvent event = new MouseButtonEvent(at[0], at[1], new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
		Screen s = screen();
		s.mouseClicked(event, false);
		s.mouseReleased(event);
	}

	private static void doubleClick(double[] at) {
		MouseButtonEvent event = new MouseButtonEvent(at[0], at[1], new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
		Screen s = screen();
		s.mouseClicked(event, true);
		s.mouseReleased(event);
	}

	/** Clears the focused box with real Backspace presses (SDL keycode 8) and types the new text. */
	private static void setLevel(String text) {
		EnchantLevelScreen l = (EnchantLevelScreen) screen();
		click(center(l.levelBox()));
		for (int i = 0; i < 6; i++) {
			key(com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE, 8);
		}
		type(text);
	}

	private static String levelProblem(boolean expectError, String contains) {
		EnchantLevelScreen l = (EnchantLevelScreen) screen();
		String e = l.error();
		if (expectError) {
			return e != null && e.contains(contains) ? null : "expected an error containing '" + contains + "' but got " + e;
		}
		return e == null ? null : "unexpected error " + e;
	}

	/** Scrolls the component list with real mouse wheel events until row {@code i} is on screen. */
	private static void reveal(int i) {
		CommandStudioScreen g = give();
		for (int n = 0; n < 40 && !g.rowVisible(i); n++) {
			double[] at = g.compAreaPoint();
			g.mouseScrolled(at[0], at[1], 0, g.compScrollPos() > i ? 1 : -1);
			g.tick();
		}
	}

	private static void revealField(int i) {
		EntityGeneratorScreen e = entity();
		for (int n = 0; n < 60 && !e.fieldVisible(i); n++) {
			double[] at = e.fieldAreaPoint();
			e.mouseScrolled(at[0], at[1], 0, -1);
			e.tick();
		}
	}

	private static final boolean[] confirmed = { false };

	private static FormGeneratorScreen form() {
		return (FormGeneratorScreen) screen();
	}

	private static void survey() {
		StringBuilder sb = new StringBuilder();
		for (net.minecraft.resources.Identifier id : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.keySet()) {
			String nbt = khoa.studio.entity.EntitySerializer.defaultNbt(id.toString());
			if (nbt == null) {
				sb.append("ENTITY ").append(id).append(" -> NO SAMPLE\n");
				continue;
			}
			try {
				net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseCompoundFully(nbt);
				StringBuilder keys = new StringBuilder();
				for (String k : tag.keySet()) {
					net.minecraft.nbt.Tag v = tag.get(k);
					String low = k.toLowerCase(java.util.Locale.ROOT);
					boolean item = v instanceof net.minecraft.nbt.CompoundTag c && c.contains("id") && c.contains("count");
					if (item || low.contains("effect") || low.contains("potion") || low.contains("owner") || low.contains("item") || low.contains("color")) {
						String text = String.valueOf(v);
						keys.append(k).append('=').append(item ? "ITEM " : "").append(text.length() > 80 ? text.substring(0, 80) : text).append("; ");
					}
				}
				if (keys.length() > 0) {
					sb.append("ENTITY ").append(id).append(" -> ").append(keys).append('\n');
				}
			} catch (Exception e) {
				sb.append("ENTITY ").append(id).append(" -> unparsable\n");
			}
		}
		for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(item);
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
			StringBuilder tools = new StringBuilder();
			if (st.has(net.minecraft.core.component.DataComponents.POTION_CONTENTS)) {
				tools.append("EFFECTS ");
			}
			if (st.has(net.minecraft.core.component.DataComponents.BANNER_PATTERNS)) {
				tools.append("BANNER_PATTERNS ");
			}
			if (st.has(net.minecraft.core.component.DataComponents.BASE_COLOR)) {
				tools.append("BASE_COLOR ");
			}
			if (st.is(net.minecraft.tags.ItemTags.TRIMMABLE_ARMOR)) {
				tools.append("TRIM ");
			}
			if (tools.length() > 0) {
				sb.append("ITEM ").append(id).append(" -> ").append(tools).append('\n');
			}
		}
		try {
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("commandstudio-survey.txt"), sb.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			write("survey failed: " + e);
		}
	}

	private static void typeInto(EditBox box, String text) {
		click(center(box));
		key(com.mojang.blaze3d.platform.InputConstants.KEY_END, 1073741901); // cursor to the end, then wipe the old value
		for (int i = 0; i < 80; i++) {
			key(com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE, 8);
		}
		type(text);
	}

	/** Add component -> search -> first result, three real interactions; afterwards the model must hold {@code expected} components. */
	private static void addComponentSteps(String search, int expected) {
		steps.add(new Step("click Add component (" + search + ")", () -> click(give().btn("add")), () -> expectScreen(ComponentSelectorScreen.class), 6));
		steps.add(new Step("search '" + search + "' and click the first result", () -> {
			ComponentSelectorScreen s = (ComponentSelectorScreen) screen();
			click(center(s.searchBox()));
			type(search);
		}, () -> ((ComponentSelectorScreen) screen()).viewSize() > 0 ? null : "no component matches '" + search + "'", 4));
		steps.add(new Step("pick the first result", () -> click(((ComponentSelectorScreen) screen()).rowCenter(0)), () -> StudioState.get().give.components.size() == expected ? null : "component count is " + StudioState.get().give.components.size() + ", expected " + expected, 12));
	}

	private static void type(String text) {
		Screen s = screen();
		text.codePoints().forEach(cp -> s.charTyped(new CharacterEvent(cp)));
	}

	/** A key press as the game delivers it: {@code key} is the 26.3 key id, {@code keycode} the SDL key code (8 = Backspace, 27 = Esc) that text boxes switch on. */
	private static void key(int key, int glfw) {
		Screen s = screen();
		KeyEvent event = new KeyEvent(key, glfw, 0);
		s.keyPressed(event);
		s.keyReleased(event);
	}

	private static double[] center(AbstractWidget w) {
		return w == null ? null : new double[] { w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0 };
	}

	private static CommandStudioScreen give() {
		return screen() instanceof CommandStudioScreen g ? g : null;
	}

	private static EntityGeneratorScreen entity() {
		return screen() instanceof EntityGeneratorScreen e ? e : null;
	}

	private static String expectScreen(Class<?> type) {
		Screen s = screen();
		return type.isInstance(s) ? null : "expected " + type.getSimpleName() + " but the screen is " + (s == null ? "none" : s.getClass().getSimpleName());
	}

	// ------------------------------------------------------------------ the script

	private static void add(String name, Runnable action, Supplier<String> check) {
		steps.add(new Step(name, action, check, 6));
	}

	private static void build() {
		steps.clear();
		GiveModel give = StudioState.get().give;

		add("reset state and open the Give generator", () -> {
			give.reset();
			StudioState.get().entity.reset();
			CommandStudioScreen.open();
		}, () -> expectScreen(CommandStudioScreen.class));
		// --- enchantments: dedicated picker + level dialog
		addComponentSteps("enchantments", 1);
		add("adding enchantments opens the enchantment picker by itself", () -> {
		}, () -> expectScreen(EnchantPickerScreen.class));
		add("search 'sharp' in the enchantment list", () -> {
			EnchantPickerScreen e = (EnchantPickerScreen) screen();
			click(center(e.searchBox()));
			type("sharp");
		}, () -> ((EnchantPickerScreen) screen()).leftCount() > 0 ? null : "no enchantment matches 'sharp'");
		add("double click the first result -> level dialog", () -> doubleClick(((EnchantPickerScreen) screen()).leftRowCenter(0)), () -> expectScreen(EnchantLevelScreen.class));
		add("level 0 is refused", () -> setLevel("0"), () -> levelProblem(true, "at least 1"));
		add("level 300 is refused (limit 255)", () -> setLevel("300"), () -> levelProblem(true, "255"));
		add("click OK with an invalid level does nothing", () -> click(((EnchantLevelScreen) screen()).okCenter()), () -> expectScreen(EnchantLevelScreen.class));
		add("level 6 is accepted and flagged as above the normal max (notice, not an error)", () -> setLevel("6"), () -> {
			EnchantLevelScreen l = (EnchantLevelScreen) screen();
			if (l.error() != null) {
				return "unexpected error: " + l.error();
			}
			return l.notice() != null ? null : "no 'higher than normal' notice for level 6";
		});
		add("click OK", () -> click(((EnchantLevelScreen) screen()).okCenter()), () -> expectScreen(EnchantPickerScreen.class));
		add("the added list holds sharpness 6", () -> {
		}, () -> {
			Integer level = ((EnchantPickerScreen) screen()).levelOf("minecraft:sharpness");
			return level != null && level == 6 ? null : "level is " + level;
		});
		add("click Done", () -> click(((EnchantPickerScreen) screen()).doneCenter()), () -> {
			String wrong = expectScreen(CommandStudioScreen.class);
			if (wrong != null) {
				return wrong;
			}
			return give.components.get(0).value.contains("sharpness") && give.components.get(0).value.contains("6") ? null : "value: " + give.components.get(0).value;
		});
		// --- boolean, number and text components get their own control
		addComponentSteps("enchantment_glint", 2);
		add("scroll the list to row 1", () -> reveal(1), () -> give().rowVisible(1) ? null : "row not visible");
		add("boolean component shows a tick box; click it", () -> { click(give().btn("bool:1")); }, () -> give.components.get(1).value.equals("true") ? null : "value: " + give.components.get(1).value);
		addComponentSteps("repair_cost", 3);
		add("scroll the list to row 2", () -> reveal(2), () -> give().rowVisible(2) ? null : "row not visible");
		add("number component: type 7", () -> { typeInto(give().compBox(2), "7"); }, () -> give.components.get(2).value.equals("7") ? null : "value: " + give.components.get(2).value);
		addComponentSteps("item_name", 4);
		add("scroll the list to row 3", () -> reveal(3), () -> give().rowVisible(3) ? null : "row not visible");
		add("text component: type Hello (stored as a quoted string)", () -> { typeInto(give().compBox(3), "Hello"); }, () -> give.components.get(3).value.equals("\"Hello\"") ? null : "value: " + give.components.get(3).value);
		add("output is a valid /give with all four components", () -> {
		}, () -> {
			CommandStudioScreen g = give();
			if (g.outputError() != null) {
				return "output error: " + g.outputError() + " | " + give.itemText();
			}
			String o = g.outputText();
			return o.contains("sharpness") && o.contains("enchantment_glint_override=true") && o.contains("repair_cost=7") && o.contains("item_name=\"Hello\"") ? null : "output: " + o;
		});
		add("click Copy", () -> click(give().btn("copy")), () -> {
			String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
			return clip.equals(give().outputText()) ? null : "clipboard is '" + clip + "'";
		});
		add("click Run", () -> click(give().btn("run")), () -> give().status().startsWith("Sent") ? null : "status: " + give().status());
		add("click Pick item", () -> click(give().btn("pick")), () -> expectScreen(KhoaItemPicker.class));
		add("picker has a Saved Items tab (anyItem)", () -> {
			// a named stack saved into hotbar group 1, exactly what creative "save toolbar" stores
			Minecraft mc = Minecraft.getInstance();
			net.minecraft.world.item.ItemStack named = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
			named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Test Saved Armor"));
			mc.player.getInventory().setItem(0, named);
			mc.getHotbarManager().get(0).storeFrom(mc.player.getInventory(), mc.level.registryAccess());
			KhoaItemPicker p = (KhoaItemPicker) screen();
			p.onClose();
			click(give().btn("pick"));
		}, () -> screen() instanceof KhoaItemPicker p && p.categoryCenter("saved") != null ? null : "no Saved Items tab");
		add("click the Saved Items tab", () -> click(((KhoaItemPicker) screen()).categoryCenter("saved")), () -> {
			KhoaItemPicker p = (KhoaItemPicker) screen();
			if (!"saved".equals(p.currentCategory()) || p.viewSize() == 0) {
				return "saved tab empty (view " + p.viewSize() + ")";
			}
			for (int i = 0; i < p.viewSize(); i++) {
				if (p.viewName(i).equals("Test Saved Armor")) {
					return null;
				}
			}
			return "custom named item missing from the saved tab";
		});
		add("press Esc in the picker", () -> key(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 27), () -> expectScreen(CommandStudioScreen.class));
		add("click Close, then reopen: the configuration is still there", () -> click(give().btn("close")), () -> screen() == null ? null : "screen still open");
		add("reopen", CommandStudioScreen::open, () -> give.components.size() == 4 && give.components.get(0).value.contains("sharpness") ? null : "state lost after reopen");
		add("click Reset twice", () -> {
			click(give().btn("reset"));
			click(give().btn("reset"));
		}, () -> give.components.isEmpty() ? null : "reset did not clear the components");

		add("/runcommand parser: quotes required, delay optional, count >= 1", () -> {
		}, () -> {
			khoa.studio.run.RunCommand.Result ok = khoa.studio.run.RunCommand.parse(20, "1 \"/summon iron_golem\"");
			if (ok.parsed() == null || ok.parsed().count() != 20 || ok.parsed().delay() != 1 || !ok.parsed().command().equals("/summon iron_golem")) {
				return "bad parse " + ok;
			}
			khoa.studio.run.RunCommand.Result noDelay = khoa.studio.run.RunCommand.parse(3, "\"#goto 1 2 3\"");
			if (noDelay.parsed() == null || noDelay.parsed().delay() != 0 || !noDelay.parsed().command().equals("#goto 1 2 3")) {
				return "bad parse of a mod prefix " + noDelay;
			}
			if (khoa.studio.run.RunCommand.parse(3, "say hi").error() == null || khoa.studio.run.RunCommand.parse(3, "5 say hi").error() == null) {
				return "unquoted command was accepted";
			}
			return khoa.studio.run.RunCommand.parse(0, "\"x\"").error() == null ? "count 0 accepted" : null;
		});
		add("scheduler: 3 runs, 2 ticks apart", () -> {
		}, () -> {
			java.util.List<Integer> at = new java.util.ArrayList<>();
			int[] now = { 0 };
			khoa.studio.run.CommandScheduler sch = new khoa.studio.run.CommandScheduler(c -> at.add(now[0]));
			sch.schedule(3, 2, "x");
			for (now[0] = 0; now[0] < 10; now[0]++) {
				sch.tick();
			}
			return at.equals(java.util.List.of(0, 2, 4)) && sch.pending() == 0 ? null : "runs at ticks " + at;
		});
		add("real /runcommand through the client dispatcher queues and drains", () -> {
			Minecraft mc = Minecraft.getInstance();
			mc.player.connection.sendCommand("runcommand 3 20 \"say commandstudio-runcommand-test\"");
		}, () -> khoa.studio.run.CommandScheduler.GAME.pending() > 0 ? null : "nothing queued");
		steps.add(new Step("the queue empties by itself", () -> {
		}, () -> khoa.studio.run.CommandScheduler.GAME.pending() == 0 ? null : "still pending: " + khoa.studio.run.CommandScheduler.GAME.pending(), 150));
		add("unquoted /runcommand is refused (nothing queued)", () -> Minecraft.getInstance().player.connection.sendCommand("runcommand 2 say hi"), () -> khoa.studio.run.CommandScheduler.GAME.pending() == 0 ? null : "queued an unquoted command");
		add("sidebar: click Entity Generator", () -> click(give().btn("gen") != null ? give().btn("gen") : new double[] { give().sideX() + 20, 26 + 16 + 4 }), () -> expectScreen(EntityGeneratorScreen.class));
		add("click Pick entity", () -> click(entity().btn("entity")), () -> expectScreen(ComponentSelectorScreen.class));
		add("type 'zombie' and click the first result", () -> {
			ComponentSelectorScreen s = (ComponentSelectorScreen) screen();
			click(center(s.searchBox()));
			type("zombie");
		}, () -> ((ComponentSelectorScreen) screen()).viewSize() > 0 ? null : "no entity matches 'zombie'");
		add("click the result", () -> click(((ComponentSelectorScreen) screen()).rowCenter(0)), () -> expectScreen(EntityGeneratorScreen.class));
		add("entity changed and output is a /summon", () -> {
		}, () -> {
			String out = entity().outputText();
			return out.startsWith("/summon minecraft:") ? null : "output: '" + out + "' error: " + entity().outputError();
		});
		add("NBT tab: Load default NBT", () -> {
			click(entity().btn("tab:2"));
		}, () -> entity().btn("loadnbt") != null ? null : "NBT tab button missing");
		add("click Load default NBT", () -> click(entity().btn("loadnbt")), () -> StudioState.get().entity.nbt.contains("Health") ? null : "nbt: " + StudioState.get().entity.nbt);
		add("Main tab: toggle Silent", () -> click(entity().btn("tab:0")), () -> entity().btn("flag:Silent") != null ? null : "flag button missing");
		add("click Silent", () -> click(entity().btn("flag:Silent")), () -> StudioState.get().entity.flags.contains("Silent") && entity().outputText().contains("Silent") ? null : "flags=" + StudioState.get().entity.flags + " tab=" + StudioState.get().entity.tab + " silentBtn=" + java.util.Arrays.toString(entity().btn("flag:Silent")) + " error=" + entity().outputError());
		add("Fields tab lists typed fields from the game's sample NBT", () -> click(entity().btn("tab:3")), () -> entity().fieldCount() > 0 && entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.NUMBER) >= 0 && entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.BOOL) >= 0 ? null : "fields: " + entity().fieldCount());
		add("scroll to the first boolean field", () -> revealField(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.BOOL)), () -> entity().fieldVisible(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.BOOL)) ? null : "not visible");
		add("click the boolean field's tick box", () -> click(entity().btn("fb:" + entity().fieldKey(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.BOOL)))), () -> {
			String key = entity().fieldKey(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.BOOL));
			return entity().modelNbt().contains(key) ? null : "nbt has no " + key + ": " + entity().modelNbt();
		});
		add("scroll to the first number field", () -> revealField(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.NUMBER)), () -> entity().fieldVisible(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.NUMBER)) ? null : "not visible");
		add("type 7 into the number field", () -> typeInto(entity().fieldBox(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.NUMBER)), "7"), () -> {
			String key = entity().fieldKey(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.NUMBER));
			return entity().modelNbt().contains(key + ":7") && entity().outputError() == null ? null : "nbt: " + entity().modelNbt() + " error " + entity().outputError();
		});
		add("the summon command carries the typed field", () -> {
		}, () -> {
			String key = entity().fieldKey(entity().fieldIndex(khoa.studio.entity.EntityFields.Kind.NUMBER));
			return entity().outputText().contains(key + ":7") ? null : entity().outputText();
		});
		add("Equipment tab: click Pick on head and escape", () -> click(entity().btn("tab:1")), () -> entity().btn("eq:head") != null ? null : "equipment buttons missing");
		add("open the head picker", () -> click(entity().btn("eq:head")), () -> expectScreen(KhoaItemPicker.class));
		add("Esc", () -> key(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 27), () -> expectScreen(EntityGeneratorScreen.class));
		add("click Run", () -> click(entity().btn("run")), () -> entity().status().startsWith("Sent") ? null : "status: " + entity().status() + " error: " + entity().outputError());
		add("sidebar: back to Give Generator", () -> {
			if (entity().btn("gen") != null) {
				Sidebar.openById("give"); // narrow window: the cycle button goes to the next generator, not back
			} else {
				click(new double[] { entity().sideX() + 20, 26 + 4 });
			}
		}, () -> expectScreen(CommandStudioScreen.class));
		// --- every simple generator opens, shows a valid command with its defaults, and is reachable from the sidebar list
		for (khoa.studio.form.FormDef d : khoa.studio.form.Forms.all()) {
			String gid = d.id();
			add("generator " + gid + ": opens with a valid default command", () -> Sidebar.openById(gid), () -> {
				if (!(screen() instanceof FormGeneratorScreen f) || !f.id().equals(gid)) {
					return "screen is " + screen();
				}
				return f.outputError() == null && f.outputText().startsWith("/") ? null : "error: " + f.outputError() + " | " + f.outputText();
			});
		}
		// --- real interaction on the Effect generator: registry picker, choice button, hidden fields
		add("Effect: open from the sidebar by clicking its row", () -> {
			Sidebar.openById("entity");
		}, () -> expectScreen(EntityGeneratorScreen.class));
		add("click the Effect row in the sidebar (or the next-generator button on a narrow window)", () -> {
			if (entity().btn("gen") != null) {
				Sidebar.openById("effect"); // narrow window: no list, only the cycle button
			} else {
				click(Sidebar.rowCenter("effect", entity().sideX(), screen().height));
			}
		}, () -> screen() instanceof FormGeneratorScreen f && f.id().equals("effect") ? null : "screen is " + screen());
		add("Effect: open the effect picker", () -> click(form().btn("r:effect")), () -> expectScreen(khoa.api.gui.KhoaListPicker.class));
		add("Effect: search 'regen' and pick the first row", () -> {
			khoa.api.gui.KhoaListPicker p = (khoa.api.gui.KhoaListPicker) screen();
			click(center(p.searchBox()));
			type("regen");
		}, () -> ((khoa.api.gui.KhoaListPicker) screen()).viewSize() > 0 ? null : "no effect matches 'regen'");
		add("click the first effect", () -> click(((khoa.api.gui.KhoaListPicker) screen()).rowCenter(0)), () -> form().outputText().contains("regeneration") ? null : "output: " + form().outputText() + " " + form().outputError());
		add("Effect: switch Action to clear (seconds field disappears)", () -> click(form().btn("c:mode")), () -> form().outputText().startsWith("/effect clear") && form().box("seconds") != null && !form().box("seconds").visible ? null : "output: " + form().outputText());
		add("Tellraw: open from the sidebar and type a message", () -> {
			Sidebar.openById("tellraw");
		}, () -> screen() instanceof FormGeneratorScreen f && f.id().equals("tellraw") ? null : "screen is " + screen());
		add("Tellraw: type text into the Text box", () -> typeInto(form().box("text"), "Hi there"), () -> form().outputText().contains("Hi there") && form().outputError() == null ? null : form().outputText() + " " + form().outputError());
		add("Tellraw: click Run sends one command", () -> click(form().btn("run")), () -> form().status().startsWith("Sent 1") ? null : "status: " + form().status());
		add("Tellraw: close and reopen keeps the text", () -> {
			screen().onClose();
			Sidebar.openById("tellraw");
		}, () -> form().value("text").equals("Hi there") ? null : "value: " + form().value("text"));
		// --- confirmation dialog for several commands
		add("confirm dialog: Cancel sends nothing", () -> khoa.api.gui.KhoaConfirmScreen.open(screen(), "Run 3 commands?", java.util.List.of("/say a", "/say b", "/say c"), "Run all", () -> confirmed[0] = true), () -> screen() instanceof khoa.api.gui.KhoaConfirmScreen ? null : "no dialog");
		add("click Cancel", () -> click(((khoa.api.gui.KhoaConfirmScreen) screen()).noCenter()), () -> !confirmed[0] && screen() instanceof FormGeneratorScreen ? null : "confirmed=" + confirmed[0] + " screen " + screen());
		add("confirm dialog again, click Run all", () -> khoa.api.gui.KhoaConfirmScreen.open(screen(), "Run 3 commands?", java.util.List.of("/say a", "/say b", "/say c"), "Run all", () -> confirmed[0] = true), () -> screen() instanceof khoa.api.gui.KhoaConfirmScreen ? null : "no dialog");
		add("click Run all", () -> click(((khoa.api.gui.KhoaConfirmScreen) screen()).yesCenter()), () -> confirmed[0] ? null : "callback not called");
		// --- the chat box accepts long commands
		add("chat box: a 300 character command is kept (limit lifted)", () -> {
			Minecraft.getInstance().gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/", false));
		}, () -> screen() instanceof net.minecraft.client.gui.screens.ChatScreen ? null : "no chat screen");
		add("type 300 characters into the chat box", () -> type("a".repeat(300)), () -> {
			try {
				java.lang.reflect.Field f = net.minecraft.client.gui.screens.ChatScreen.class.getDeclaredField("input");
				f.setAccessible(true);
				int len = ((EditBox) f.get(screen())).getValue().length();
				return len >= 300 ? null : "chat box holds only " + len + " characters";
			} catch (ReflectiveOperationException e) {
				return "cannot read the chat box: " + e;
			}
		});
		add("leave chat, back to Give", () -> {
			screen().onClose();
			Sidebar.openById("give");
		}, () -> expectScreen(CommandStudioScreen.class));
		// --- item tools: effect picker, banner editor, trim editor, embedded item editor of the entity generator
		add("Give: potion item shows the Effects tool", () -> {
			StudioState.get().give.reset();
			StudioState.get().give.item = "minecraft:potion";
			Sidebar.openById("give");
		}, () -> give().btn("tool:EFFECTS") != null ? null : "no Effects button");
		add("click Effects", () -> click(give().btn("tool:EFFECTS")), () -> expectScreen(khoa.api.gui.KhoaEffectPicker.class));
		add("effects: search 'speed', double click the first row", () -> {
			khoa.api.gui.KhoaEffectPicker p = (khoa.api.gui.KhoaEffectPicker) screen();
			click(center(p.searchBox()));
			type("speed");
		}, () -> ((khoa.api.gui.KhoaEffectPicker) screen()).leftCount() > 0 ? null : "no speed effect");
		add("double click opens the level/duration dialog", () -> doubleClick(((khoa.api.gui.KhoaEffectPicker) screen()).leftRowCenter(0)), () -> expectScreen(khoa.api.gui.KhoaEffectDialog.class));
		add("dialog: duration 0 is refused", () -> {
			khoa.api.gui.KhoaEffectDialog d = (khoa.api.gui.KhoaEffectDialog) screen();
			typeInto(d.durationBox(), "0");
		}, () -> ((khoa.api.gui.KhoaEffectDialog) screen()).error() != null ? null : "duration 0 accepted");
		add("dialog: level 1, duration 600 is fine", () -> {
			khoa.api.gui.KhoaEffectDialog d = (khoa.api.gui.KhoaEffectDialog) screen();
			typeInto(d.levelBox(), "1");
			typeInto(d.durationBox(), "600");
		}, () -> ((khoa.api.gui.KhoaEffectDialog) screen()).error() == null ? null : ((khoa.api.gui.KhoaEffectDialog) screen()).error());
		add("click OK", () -> click(((khoa.api.gui.KhoaEffectDialog) screen()).okCenter()), () -> screen() instanceof khoa.api.gui.KhoaEffectPicker p && p.effects().size() == 1 ? null : "screen " + screen());
		add("click Done in the effect picker", () -> click(((khoa.api.gui.KhoaEffectPicker) screen()).doneCenter()), () -> expectScreen(CommandStudioScreen.class));
		add("potion output has the custom effect and is valid", () -> {
		}, () -> give().outputError() == null && give().outputText().contains("custom_effects") && give().outputText().contains("speed") ? null : give().outputError() + " | " + give().outputText());
		add("Give: shield shows the Banner tool", () -> {
			StudioState.get().give.reset();
			StudioState.get().give.item = "minecraft:white_banner";
			Sidebar.openById("give");
		}, () -> give().btn("tool:BANNER") != null ? null : "no Banner button");
		add("click Banner", () -> click(give().btn("tool:BANNER")), () -> expectScreen(khoa.api.gui.KhoaBannerEditor.class));
		add("banner: search 'stripe_top', double click adds a layer", () -> {
			khoa.api.gui.KhoaBannerEditor e = (khoa.api.gui.KhoaBannerEditor) screen();
			click(center(e.searchBox()));
			type("stripe_top");
		}, () -> ((khoa.api.gui.KhoaBannerEditor) screen()).patternCount() > 0 ? null : "no pattern");
		add("banner: pick red base, add a layer", () -> {
			khoa.api.gui.KhoaBannerEditor e = (khoa.api.gui.KhoaBannerEditor) screen();
			click(e.baseSwatchCenter(14));
			doubleClick(e.patternCenter(0));
		}, () -> ((khoa.api.gui.KhoaBannerEditor) screen()).layerCount() == 1 && ((khoa.api.gui.KhoaBannerEditor) screen()).baseName().equals("red") ? null : "layers " + ((khoa.api.gui.KhoaBannerEditor) screen()).layerCount() + " base " + ((khoa.api.gui.KhoaBannerEditor) screen()).baseName());
		add("banner: Done returns to Give with a red banner", () -> click(((khoa.api.gui.KhoaBannerEditor) screen()).doneCenter()), () -> expectScreen(CommandStudioScreen.class));
		add("banner output has banner_patterns on red_banner", () -> {
		}, () -> give().outputError() == null && give().outputText().contains("red_banner") && give().outputText().contains("banner_patterns") ? null : give().outputError() + " | " + give().outputText());
		add("Give: chestplate shows the Trim tool", () -> {
			StudioState.get().give.reset();
			StudioState.get().give.item = "minecraft:iron_chestplate";
			Sidebar.openById("give");
		}, () -> give().btn("tool:TRIM") != null ? null : "no Trim button");
		add("click Trim, pick pattern and material", () -> click(give().btn("tool:TRIM")), () -> expectScreen(khoa.api.gui.KhoaTrimEditor.class));
		add("trim: select the first pattern and material", () -> {
			khoa.api.gui.KhoaTrimEditor e = (khoa.api.gui.KhoaTrimEditor) screen();
			click(e.patternCenter(0));
			click(e.materialCenter(0));
		}, () -> ((khoa.api.gui.KhoaTrimEditor) screen()).currentSnbt() != null ? null : "no trim selected");
		add("trim: Done", () -> click(((khoa.api.gui.KhoaTrimEditor) screen()).doneCenter()), () -> expectScreen(CommandStudioScreen.class));
		add("trim output has the trim component and no preview item change", () -> {
		}, () -> give().outputError() == null && give().outputText().contains("trim") && give().outputText().contains("iron_chestplate") ? null : give().outputError() + " | " + give().outputText());
		add("Entity: item entity edits its item with the Give editor", () -> {
			StudioState.get().entity.reset();
			StudioState.get().entity.entity = "minecraft:item";
			Sidebar.openById("entity");
		}, () -> expectScreen(EntityGeneratorScreen.class));
		add("open the item field editor", () -> entity().openFieldItem("Item"), () -> expectScreen(CommandStudioScreen.class));
		add("item editor: item picked is stored, Use returns", () -> {
			give().setItemForTest("minecraft:splash_potion");
			click(give().btn("run"));
		}, () -> screen() instanceof EntityGeneratorScreen && entity().modelNbt().contains("splash_potion") ? null : "screen " + screen() + " nbt " + (screen() instanceof EntityGeneratorScreen ? entity().modelNbt() : ""));
		add("survey: entity NBT keys and item tools go to commandstudio-survey.txt", () -> survey(), () -> null);
		add("back to Give", () -> Sidebar.openById("give"), () -> expectScreen(CommandStudioScreen.class));
		add("close", () -> click(give().btn("close")), () -> screen() == null ? null : "screen still open");
	}
}
