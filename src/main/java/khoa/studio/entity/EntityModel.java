package khoa.studio.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** State of the Entity generator (saved as JSON). Equipment slots hold item text ({@code id[components]}), empty = none. */
public final class EntityModel {
	/** Flag names are the entity NBT keys, so the serializer needs no mapping table. */
	public static final String[] FLAGS = { "Silent", "NoAI", "Invulnerable", "Glowing", "NoGravity", "PersistenceRequired", "CustomNameVisible" };
	public static final String[] SLOTS = { "head", "chest", "legs", "feet", "mainhand", "offhand" };

	public String entity = "minecraft:zombie";
	public String pos = "~ ~ ~";
	public String name = "";
	public String tags = "";
	public List<String> flags = new ArrayList<>();
	public Map<String, String> equipment = new LinkedHashMap<>();
	/** Stack size per slot (missing = 1). */
	public Map<String, Integer> equipmentCount = new LinkedHashMap<>();
	/** Extra NBT as SNBT: merged over the fields above. "Load default NBT" fills it with the game's own full NBT for the type. */
	public String nbt = "";
	public int tab;

	public int count(String slot) {
		return equipmentCount == null ? 1 : equipmentCount.getOrDefault(slot, 1);
	}

	public String slot(String slot) {
		return equipment.getOrDefault(slot, "");
	}

	public void reset() {
		entity = "minecraft:zombie";
		pos = "~ ~ ~";
		name = "";
		tags = "";
		flags = new ArrayList<>();
		equipment = new LinkedHashMap<>();
		equipmentCount = new LinkedHashMap<>();
		nbt = "";
	}
}
