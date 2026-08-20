package dev.tiltedlunar.hunted.registry;

import dev.tiltedlunar.hunted.Hunted;
import dev.tiltedlunar.hunted.hunter.HunterEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Registration for everything the mod adds to the game. */
public final class HuntedEntities {

	public static final ResourceKey<EntityType<?>> HUNTER_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Hunted.MOD_ID, "hunter"));

	public static final EntityType<HunterEntity> HUNTER = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			HUNTER_KEY,
			EntityType.Builder.<HunterEntity>of(HunterEntity::new, MobCategory.MONSTER)
					.sized(0.6f, 1.95f)
					.eyeHeight(1.74f)
					// Tracked from a long way off, because a hunter you cannot
					// see coming is a hunter that pops into existence next to
					// you, and that reads as a bug rather than a threat.
					.clientTrackingRange(16)
					.updateInterval(2)
					.noSummon()
					.build(HUNTER_KEY));

	private HuntedEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(HUNTER, HunterEntity.createAttributes());
		Hunted.LOG.debug("Registered entity {}", HUNTER_KEY.identifier());
	}
}
