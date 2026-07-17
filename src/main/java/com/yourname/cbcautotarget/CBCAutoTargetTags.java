package com.yourname.cbcautotarget;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

public class CBCAutoTargetTags {
    public static final TagKey<EntityType<?>> TARGETED_ENTITIES = TagKey.create(
        Registries.ENTITY_TYPE,
        ResourceLocation.fromNamespaceAndPath(CBCAutoTarget.MOD_ID, "targeted_entities")
    );
}
