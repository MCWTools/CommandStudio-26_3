package khoa.studio.entity;

import com.mojang.serialization.DataResult;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;

import khoa.studio.serialization.ItemText;
import khoa.studio.serialization.ValidationResult;

/** EntityModel -> {@code /summon <entity> <pos> {nbt}}. Every NBT value is encoded by the game's own codecs. */
public final class EntitySerializer {
	private EntitySerializer() {
	}

	public static ValidationResult serialize(EntityModel m) {
		Identifier id = Identifier.tryParse(m.entity);
		EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		if (type == null) {
			return ValidationResult.error("Unknown entity: " + m.entity);
		}
		if (!type.canSummon()) {
			return ValidationResult.error(m.entity + " cannot be summoned");
		}
		RegistryAccess access = ItemText.access();
		if (access == null) {
			return ValidationResult.error("Join a world first");
		}
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, access);
		CompoundTag nbt = new CompoundTag();
		if (!m.nbt.isBlank()) {
			try {
				nbt = TagParser.parseCompoundFully(m.nbt);
			} catch (Exception e) {
				return ValidationResult.error("NBT: " + e.getMessage());
			}
			stripDefaults(m.entity, nbt);
		}
		if (!m.name.isBlank()) {
			DataResult<Tag> name = ComponentSerialization.CODEC.encodeStart(ops, Component.literal(m.name));
			Tag nameTag = name.result().orElse(null);
			if (nameTag != null) {
				nbt.put("CustomName", nameTag);
			}
		}
		for (String flag : m.flags) {
			if (!flagApplies(m.entity, flag)) {
				return ValidationResult.error(flag + " does not apply to " + m.entity + " (mobs only): untick it");
			}
			nbt.putBoolean(flag, true);
		}
		boolean anyEquipment = m.equipment.values().stream().anyMatch(s -> !s.isBlank());
		if (anyEquipment && !isLiving(m.entity)) {
			return ValidationResult.error(m.entity + " has no equipment slots: clear the Equipment tab");
		}
		if (!m.tags.isBlank()) {
			ListTag list = new ListTag();
			for (String tag : m.tags.split("\\s*,\\s*")) {
				if (!tag.isBlank()) {
					list.add(StringTag.valueOf(tag.trim()));
				}
			}
			nbt.put("Tags", list);
		}
		CompoundTag equipment = new CompoundTag();
		for (String slot : EntityModel.SLOTS) {
			String text = m.slot(slot);
			if (text.isBlank()) {
				continue;
			}
			try {
				ItemStack stack = ItemText.parse(text);
				int count = m.count(slot);
				if (count < 1 || count > stack.getMaxStackSize()) {
					return ValidationResult.error("Equipment " + slot + ": count must be 1-" + stack.getMaxStackSize() + " for this item");
				}
				stack.setCount(count);
				DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(ops, stack);
				Tag tag = encoded.result().orElse(null);
				if (tag == null) {
					return ValidationResult.error("Equipment " + slot + ": cannot encode");
				}
				equipment.put(slot, tag);
			} catch (Exception e) {
				return ValidationResult.error("Equipment " + slot + ": " + e.getMessage());
			}
		}
		if (!equipment.isEmpty()) {
			CompoundTag existing = nbt.get("equipment") instanceof CompoundTag c ? c : new CompoundTag();
			existing.merge(equipment);
			nbt.put("equipment", existing);
		}
		String pos = m.pos.isBlank() ? "~ ~ ~" : m.pos.trim();
		return ValidationResult.ok("/summon " + id + " " + pos + (nbt.isEmpty() ? "" : " " + nbt));
	}

	private static final java.util.Map<String, Class<?>> CLASSES = new java.util.HashMap<>();

	/** The runtime class of the entity type (from a sample instance), so applicability follows the game's class hierarchy. Null if unknown. */
	private static Class<?> classOf(String entityId) {
		Minecraft mc = Minecraft.getInstance();
		Identifier id = Identifier.tryParse(entityId);
		if (mc.level == null || id == null) {
			return null;
		}
		Class<?> cached = CLASSES.get(entityId);
		if (cached == null) {
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
			Entity sample = type == null ? null : type.create(mc.level, EntitySpawnReason.COMMAND);
			if (sample == null) {
				return null;
			}
			cached = sample.getClass();
			sample.discard();
			CLASSES.put(entityId, cached);
		}
		return cached;
	}

	/** NoAI / PersistenceRequired are Mob fields, equipment belongs to LivingEntity (verified in the 26.3 bytecode). */
	public static boolean isMob(String entityId) {
		Class<?> c = classOf(entityId);
		return c != null && net.minecraft.world.entity.Mob.class.isAssignableFrom(c);
	}

	public static boolean isLiving(String entityId) {
		Class<?> c = classOf(entityId);
		return c != null && net.minecraft.world.entity.LivingEntity.class.isAssignableFrom(c);
	}

	public static boolean flagApplies(String entityId, String flag) {
		return !(flag.equals("NoAI") || flag.equals("PersistenceRequired")) || isMob(entityId);
	}

	/** The game's complete NBT for a fresh entity of this type (every key it saves, with real defaults), or an error text. */
	/** The command only carries what differs from a fresh entity of this type: keys equal to the game's own default are dropped. */
	private static void stripDefaults(String entityId, CompoundTag nbt) {
		String sample = defaultNbt(entityId);
		if (sample == null) {
			return;
		}
		try {
			CompoundTag defaults = TagParser.parseCompoundFully(sample);
			for (String key : new java.util.ArrayList<>(nbt.keySet())) {
				if (nbt.get(key).equals(defaults.get(key))) {
					nbt.remove(key);
				}
			}
		} catch (Exception e) {
			// no usable sample: keep everything the user wrote
		}
	}

	public static String defaultNbt(String entityId) {
		Minecraft mc = Minecraft.getInstance();
		Identifier id = Identifier.tryParse(entityId);
		if (mc.level == null || id == null) {
			return null;
		}
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		if (type == null) {
			return null;
		}
		Entity entity = type.create(mc.level, EntitySpawnReason.COMMAND);
		if (entity == null) {
			return null;
		}
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, mc.level.registryAccess());
		entity.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		for (String drop : new String[] { "Pos", "UUID", "Motion", "Rotation", "FallDistance", "Air", "OnGround", "PortalCooldown", "Fire" }) {
			tag.remove(drop);
		}
		entity.discard();
		return tag.toString();
	}
}
