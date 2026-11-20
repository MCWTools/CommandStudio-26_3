package khoa.studio.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import khoa.studio.give.GiveModel;

/**
 * Everything that survives closing the GUI, leaving a world and restarting the game: stored in
 * config/commandstudio.json (local, not per world). Writes are batched: {@link #markDirty()} + {@link #tick()}.
 * A file that cannot be read is renamed to .broken so nothing is lost.
 */
public final class StudioState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int SAVE_DELAY_TICKS = 20;
	private static StudioState instance;
	private static int dirtyTicks = -1;

	public String lastGenerator = "give";
	public GiveModel give = new GiveModel();
	public khoa.studio.entity.EntityModel entity = new khoa.studio.entity.EntityModel();
	/** Field values of the simple generators, by generator id. */
	public java.util.Map<String, java.util.Map<String, String>> forms = new java.util.LinkedHashMap<>();

	private static Path file() {
		// the automated self test must never touch the real settings
		String name = System.getProperty("commandstudio.autotest") != null ? "commandstudio-autotest.json" : "commandstudio.json";
		return FabricLoader.getInstance().getConfigDir().resolve(name);
	}

	public static synchronized StudioState get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static StudioState load() {
		Path path = file();
		if (!Files.exists(path)) {
			return new StudioState();
		}
		try {
			StudioState state = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), StudioState.class);
			if (state == null) {
				throw new IllegalStateException("empty file");
			}
			if (state.entity == null) {
				state.entity = new khoa.studio.entity.EntityModel();
			}
			if (state.forms == null) {
				state.forms = new java.util.LinkedHashMap<>();
			}
			if (state.give == null) {
				state.give = new GiveModel();
			}
			if (state.give.components == null) {
				state.give.components = new java.util.ArrayList<>();
			}
			return state;
		} catch (Exception e) {
			System.err.println("[CommandStudio] cannot read " + path + ": " + e + " (kept as .broken)");
			try {
				Files.move(path, path.resolveSibling("commandstudio.json.broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// nothing more we can do; defaults are used
			}
			return new StudioState();
		}
	}

	public static void markDirty() {
		dirtyTicks = SAVE_DELAY_TICKS;
	}

	/** Client tick: saves once the state has been quiet for a second. */
	public static void tick() {
		if (dirtyTicks > 0 && --dirtyTicks == 0) {
			saveNow();
		}
	}

	public static synchronized void saveNow() {
		dirtyTicks = -1;
		if (instance == null) {
			return;
		}
		try {
			Path path = file();
			Path tmp = path.resolveSibling("commandstudio.json.tmp");
			Files.writeString(tmp, GSON.toJson(instance), StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			System.err.println("[CommandStudio] cannot save state: " + e);
		}
	}
}
