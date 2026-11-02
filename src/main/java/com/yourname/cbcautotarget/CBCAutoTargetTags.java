package com.yourname.cbcautotarget;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

public class CBCAutoTargetTags {
    public static final TagKey<EntityType<?>> TARGETED_ENTITIES = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(CBCAutoTarget.MOD_ID, "targeted_entities")
    );

    /**
     * Мобы, которые технически зарегистрированы с MobCategory.MONSTER (или наследуют
     * Monster), но по геймплею являются нейтральными — атакуют только в ответ на
     * провокацию (голем, пиглины, зомбифицированный пиглин и т.д.).
     * Для них турели проверяют реальное состояние агрессии (isAngry/getTarget)
     * вместо того чтобы всегда считать их враждебными.
     */
    public static final TagKey<EntityType<?>> NEUTRAL_ENTITIES = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(CBCAutoTarget.MOD_ID, "neutral_entities")
    );

    /** Блоки, которые не разрушаются кнопкой STRUCTURE DESTROY (по умолчанию сундуки и бочки; расширяется датапаком). */
    public static final TagKey<Block> STRUCTURE_DESTROY_WHITELIST = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(CBCAutoTarget.MOD_ID, "structure_destroy_whitelist")
    );
}
