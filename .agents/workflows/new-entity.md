---
description: Add a new mob entity with renderer and registration
---

# new-entity

Context:
- Loader: NeoForge 21.1.227, MC 1.21.1, Java 21
- Mappings: Parchment 2025.12.20
- mod_id: cbc_autotarget, root package: com.yourname.cbcautotarget
- No existing EntityType registry yet — needs ModEntities.java if first entity
- Client renderers: client/renderer/ package; register in ClientSetup.java
- Spawn eggs: register as DeferredItem in ModBlocks.ITEMS (same register as block items)

Steps:
1. Ask: "Entity ID (snake_case)? Extends which vanilla mob (Mob/PathfinderMob/Monster/other)? Needs custom renderer? Spawn egg? Any special AI goals?"
2. Generate entity class in entity/ package. Extend appropriate vanilla base.
3. If ModEntities.java does not exist — create it with DeferredRegister<EntityType<?>>. Add register call in CBCAutoTarget constructor.
4. Register EntityType:
   ```java
   public static final DeferredHolder<EntityType<?>, EntityType<MyEntity>> MY_ENTITY =
       ENTITIES.register("my_entity", () -> EntityType.Builder.<MyEntity>of(MyEntity::new, MobCategory.MONSTER)
           .sized(0.6f, 1.8f).build("cbc_autotarget:my_entity"));
   ```
5. If custom renderer — generate EntityRenderer subclass in client/renderer/. Register in ClientSetup:
   ```java
   EntityRenderers.register(ModEntities.MY_ENTITY.get(), MyEntityRenderer::new);
   ```
6. If spawn egg — register DeferredItem<SpawnEggItem> in ModBlocks.ITEMS.
7. Generate item model JSON for spawn egg if applicable.
8. Add en_us.json entry.
9. Show all files labeled: // File: src/main/java/...
