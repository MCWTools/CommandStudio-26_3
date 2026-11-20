package khoa.studio.form;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.mojang.serialization.JsonOps;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;

import khoa.studio.form.FormDef.Field;

/**
 * The simple generators. Each is a field list plus a template; the template only joins the values, and the finished
 * command is checked by the game's own command parser (see CommandCheck), so a wrong template shows up as an error, not a wrong command.
 * Choice lists that exist in the game (particles, effects, attributes, sounds, enchantments, damage types, blocks, game modes,
 * chat colours, sound sources) are read from it at runtime.
 */
public final class Forms {
	private static final Map<String, FormDef> DEFS = new LinkedHashMap<>();

	private Forms() {
	}

	public static FormDef get(String id) {
		return DEFS.get(id);
	}

	public static List<FormDef> all() {
		return new ArrayList<>(DEFS.values());
	}

	private static String v(Map<String, String> m, String key) {
		return m.getOrDefault(key, "").trim();
	}

	/** " value" when not blank, else "". */
	private static String opt(Map<String, String> m, String key) {
		String s = v(m, key);
		return s.isEmpty() ? "" : " " + s;
	}

	private static <T> List<String[]> rows(Registry<T> registry) {
		List<String[]> out = new ArrayList<>();
		for (Identifier id : registry.keySet()) {
			String path = id.getPath().replace('_', ' ').replace('/', ' ');
			out.add(new String[] { id.toString(), Character.toUpperCase(path.charAt(0)) + path.substring(1) });
		}
		return out;
	}

	private static <T> List<String[]> dynamic(ResourceKey<Registry<T>> key) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? List.of() : rows(mc.level.registryAccess().lookupOrThrow(key));
	}

	private static void def(String id, String label, List<Field> fields, Function<Map<String, String>, String> build) {
		DEFS.put(id, new FormDef(id, label, fields, build));
	}

	private static String json(Component component) {
		return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).result().orElseThrow().toString();
	}

	private static String block(Map<String, String> m) {
		return v(m, "block") + (v(m, "state").isEmpty() ? "" : "[" + v(m, "state") + "]");
	}

	static {
		List<String> colors = new ArrayList<>();
		colors.add("");
		for (ChatFormatting f : ChatFormatting.values()) {
			if (f.ordinal() < 16) {
				colors.add(f.name().toLowerCase(java.util.Locale.ROOT)); // the sixteen chat colours, as the game declares them
			}
		}

		def("block", "Setblock Generator", List.of(
			FormDef.text("pos", "Position", "~ ~ ~"),
			FormDef.registry("block", "Block", "minecraft:stone", () -> rows(BuiltInRegistries.BLOCK)),
			FormDef.text("state", "Block states (a=b,c=d)", ""),
			FormDef.text("nbt", "Block entity NBT", ""),
			FormDef.choice("mode", "Mode", List.of("replace", "destroy", "keep"))),
			m -> "/setblock " + v(m, "pos") + " " + block(m) + v(m, "nbt") + " " + v(m, "mode"));

		def("fill", "Fill Generator", List.of(
			FormDef.text("from", "From", "~ ~ ~"),
			FormDef.text("to", "To", "~5 ~5 ~5"),
			FormDef.registry("block", "Block", "minecraft:stone", () -> rows(BuiltInRegistries.BLOCK)),
			FormDef.text("state", "Block states (a=b,c=d)", ""),
			FormDef.choice("mode", "Mode", List.of("replace", "destroy", "hollow", "keep", "outline"))),
			m -> "/fill " + v(m, "from") + " " + v(m, "to") + " " + block(m) + " " + v(m, "mode"));

		def("particle", "Particle Generator", List.of(
			FormDef.registry("particle", "Particle", "minecraft:flame", () -> rows(BuiltInRegistries.PARTICLE_TYPE)),
			FormDef.text("pos", "Position", "~ ~1 ~"),
			FormDef.text("delta", "Spread (dx dy dz)", "0.5 0.5 0.5"),
			FormDef.text("speed", "Speed", "0.1"),
			FormDef.text("count", "Count", "20"),
			FormDef.choice("mode", "Mode", List.of("normal", "force")),
			FormDef.text("viewers", "Viewers (selector)", "")),
			m -> "/particle " + v(m, "particle") + " " + v(m, "pos") + " " + v(m, "delta") + " " + v(m, "speed") + " " + v(m, "count") + " " + v(m, "mode") + opt(m, "viewers"));

		def("attribute", "Attribute Generator", List.of(
			FormDef.text("target", "Target", "@s"),
			FormDef.registry("attribute", "Attribute", "minecraft:max_health", () -> rows(BuiltInRegistries.ATTRIBUTE)),
			FormDef.choice("mode", "Action", List.of("get", "base get", "base set")),
			FormDef.text("value", "Value", "20").when("mode", "base set")),
			m -> "/attribute " + v(m, "target") + " " + v(m, "attribute") + " " + v(m, "mode") + (v(m, "mode").equals("base set") ? " " + v(m, "value") : ""));

		def("effect", "Effect Generator", List.of(
			FormDef.choice("mode", "Action", List.of("give", "clear")),
			FormDef.text("target", "Target", "@s"),
			FormDef.registry("effect", "Effect", "minecraft:speed", () -> rows(BuiltInRegistries.MOB_EFFECT)),
			FormDef.text("seconds", "Seconds", "30").when("mode", "give"),
			FormDef.text("amplifier", "Amplifier", "0").when("mode", "give"),
			FormDef.bool("hide", "Hide particles", false).when("mode", "give")),
			m -> "/effect " + v(m, "mode") + " " + v(m, "target") + " " + v(m, "effect")
				+ (v(m, "mode").equals("give") ? " " + v(m, "seconds") + " " + v(m, "amplifier") + " " + v(m, "hide") : ""));

		def("enchantment", "Enchantment Generator", List.of(
			FormDef.text("target", "Target", "@s"),
			FormDef.registry("enchantment", "Enchantment", "minecraft:sharpness", () -> dynamic(Registries.ENCHANTMENT)),
			FormDef.text("level", "Level", "1")),
			m -> "/enchant " + v(m, "target") + " " + v(m, "enchantment") + " " + v(m, "level"));

		def("tellraw", "Tellraw Generator", List.of(
			FormDef.text("target", "Target", "@a"),
			FormDef.text("text", "Text", "Hello"),
			FormDef.choice("color", "Colour", colors),
			FormDef.bool("bold", "Bold", false),
			FormDef.bool("italic", "Italic", false)),
			m -> {
				Style style = Style.EMPTY;
				if (!v(m, "color").isEmpty()) {
					style = style.applyFormat(ChatFormatting.valueOf(v(m, "color").toUpperCase(java.util.Locale.ROOT)));
				}
				style = style.withBold(v(m, "bold").equals("true") ? Boolean.TRUE : null).withItalic(v(m, "italic").equals("true") ? Boolean.TRUE : null);
				return "/tellraw " + v(m, "target") + " " + json(Component.literal(m.getOrDefault("text", "")).withStyle(style));
			});

		List<String> sources = Arrays.stream(SoundSource.values()).map(SoundSource::getName).toList();
		def("playsound", "Playsound Generator", List.of(
			FormDef.registry("sound", "Sound", "minecraft:entity.experience_orb.pickup", () -> rows(BuiltInRegistries.SOUND_EVENT)),
			FormDef.choice("source", "Source", sources),
			FormDef.text("target", "Target", "@s"),
			FormDef.text("pos", "Position", "~ ~ ~"),
			FormDef.text("volume", "Volume", "1"),
			FormDef.text("pitch", "Pitch", "1"),
			FormDef.text("min", "Min volume", "0")),
			m -> "/playsound " + v(m, "sound") + " " + v(m, "source") + " " + v(m, "target") + " " + v(m, "pos") + " " + v(m, "volume") + " " + v(m, "pitch") + " " + v(m, "min"));

		def("teleport", "Teleport Generator", List.of(
			FormDef.text("target", "Target", "@s"),
			FormDef.choice("mode", "Destination", List.of("position", "entity")),
			FormDef.text("pos", "Position", "~ ~ ~").when("mode", "position"),
			FormDef.text("dest", "Destination entity", "@p").when("mode", "entity"),
			FormDef.text("rot", "Rotation (yaw pitch, optional)", "").when("mode", "position")),
			m -> "/tp " + v(m, "target") + " " + (v(m, "mode").equals("entity") ? v(m, "dest") : v(m, "pos") + opt(m, "rot")));

		def("rotate", "Rotate Generator", List.of(
			FormDef.text("target", "Target", "@s"),
			FormDef.text("yaw", "Yaw", "0"),
			FormDef.text("pitch", "Pitch", "0")),
			m -> "/rotate " + v(m, "target") + " " + v(m, "yaw") + " " + v(m, "pitch"));

		def("scoreboard", "Scoreboard Generator", List.of(
			FormDef.choice("mode", "Action", List.of("objectives add", "objectives remove", "players set", "players add", "players remove", "players get")),
			FormDef.text("objective", "Objective", "points"),
			FormDef.text("criteria", "Criteria", "dummy").when("mode", "objectives add"),
			FormDef.text("target", "Target", "@s").when("mode", "players set", "players add", "players remove", "players get"),
			FormDef.text("score", "Score", "1").when("mode", "players set", "players add", "players remove")),
			m -> {
				String mode = v(m, "mode");
				if (mode.startsWith("players")) {
					return "/scoreboard " + mode + " " + v(m, "target") + " " + v(m, "objective") + (mode.equals("players get") ? "" : " " + v(m, "score"));
				}
				return "/scoreboard " + mode + " " + v(m, "objective") + (mode.equals("objectives add") ? " " + v(m, "criteria") : "");
			});

		def("team", "Team Generator", List.of(
			FormDef.choice("mode", "Action", List.of("add", "remove", "join", "leave", "modify color")),
			FormDef.text("team", "Team", "red_team").when("mode", "add", "remove", "join", "modify color"),
			FormDef.text("members", "Members", "@s").when("mode", "join", "leave"),
			FormDef.choice("color", "Colour", colors.subList(1, colors.size())).when("mode", "modify color")),
			m -> {
				String mode = v(m, "mode");
				return switch (mode) {
					case "join" -> "/team join " + v(m, "team") + " " + v(m, "members");
					case "leave" -> "/team leave " + v(m, "members");
					case "modify color" -> "/team modify " + v(m, "team") + " color " + v(m, "color");
					default -> "/team " + mode + " " + v(m, "team");
				};
			});

		def("bossbar", "Bossbar Generator", List.of(
			FormDef.choice("mode", "Action", List.of("add", "remove", "set value", "set max", "set color", "set style", "set visible", "set players")),
			FormDef.text("id", "Id", "mybar"),
			FormDef.text("name", "Name", "My Bar").when("mode", "add"),
			FormDef.text("value", "Number", "50").when("mode", "set value", "set max"),
			FormDef.choice("color", "Colour", List.of("pink", "blue", "red", "green", "yellow", "purple", "white")).when("mode", "set color"),
			FormDef.choice("style", "Style", List.of("progress", "notched_6", "notched_10", "notched_12", "notched_20")).when("mode", "set style"),
			FormDef.bool("visible", "Visible", true).when("mode", "set visible"),
			FormDef.text("players", "Players", "@a").when("mode", "set players")),
			m -> {
				String mode = v(m, "mode");
				String base = "/bossbar " + (mode.startsWith("set") ? "set " : mode + " ") + v(m, "id");
				return switch (mode) {
					case "add" -> base + " " + json(Component.literal(m.getOrDefault("name", "")));
					case "remove" -> base;
					case "set value" -> base + " value " + v(m, "value");
					case "set max" -> base + " max " + v(m, "value");
					case "set color" -> base + " color " + v(m, "color");
					case "set style" -> base + " style " + v(m, "style");
					case "set visible" -> base + " visible " + v(m, "visible");
					default -> base + " players " + v(m, "players");
				};
			});

		def("damage", "Damage Generator", List.of(
			FormDef.text("target", "Target", "@s"),
			FormDef.text("amount", "Amount", "5"),
			FormDef.registry("type", "Damage type", "minecraft:generic", () -> dynamic(Registries.DAMAGE_TYPE)),
			FormDef.text("by", "By entity (optional)", "")),
			m -> "/damage " + v(m, "target") + " " + v(m, "amount") + " " + v(m, "type") + (v(m, "by").isEmpty() ? "" : " by " + v(m, "by")));

		List<String> modes = Arrays.stream(GameType.values()).map(GameType::getName).toList();
		def("gamemode", "Gamemode Generator", List.of(
			FormDef.choice("mode", "Mode", modes),
			FormDef.text("target", "Target", "@s")),
			m -> "/gamemode " + v(m, "mode") + opt(m, "target"));

		def("kill", "Kill Generator", List.of(
			FormDef.text("target", "Target", "@e[type=zombie,distance=..10]")),
			m -> "/kill " + v(m, "target"));

		def("ride", "Ride Generator", List.of(
			FormDef.choice("mode", "Action", List.of("mount", "dismount")),
			FormDef.text("target", "Rider", "@s"),
			FormDef.text("vehicle", "Vehicle", "@e[type=horse,limit=1,sort=nearest]").when("mode", "mount")),
			m -> "/ride " + v(m, "target") + " " + v(m, "mode") + (v(m, "mode").equals("mount") ? " " + v(m, "vehicle") : ""));
	}
}
