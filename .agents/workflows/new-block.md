---
description: Add a new block with full registration and resources
---

# new-block

Context:
- Loader: NeoForge 21.1.227, MC 1.21.1, Java 21
- Mappings: Parchment 2025.12.20
- mod_id: cbc_autotarget, root package: com.yourname.cbcautotarget
- Registration: ModBlocks.java (DeferredRegister.createBlocks / createItems)
- Resources: src/main/resources/assets/cbc_autotarget/{blockstates,models/block,models/item}
- Lang: src/main/resources/assets/cbc_autotarget/lang/en_us.json (add key only, do not overwrite)
- Loot tables: src/main/resources/data/cbc_autotarget/loot_tables/blocks/
- Recipes: src/main/resources/data/cbc_autotarget/recipe/

Steps:
1. Ask: "Block ID (snake_case)? Hardness + resistance? Requires tool (pickaxe/axe/shovel/none)? Has BlockEntity? Creative tab?"
2. Generate block class in block/ package. Copy MapColor + SoundType from nearest existing block.
3. If BlockEntity needed — ask: "Ticker (server/client/both/none)? Needs menu/screen?" then trigger new-blockentity steps.
4. Register in ModBlocks.java using DeferredRegister pattern:
   ```java
   public static final DeferredBlock<MyBlock> MY_BLOCK =
       BLOCKS.register("my_block", () -> new MyBlock(BlockBehaviour.Properties.of()...));
   public static final DeferredItem<BlockItem> MY_BLOCK_ITEM =
       ITEMS.registerSimpleBlockItem("my_block", MY_BLOCK);
   ```
5. Add register call in CBCAutoTarget constructor if a new DeferredRegister was created (usually not needed — both BLOCKS and ITEMS are already registered).
6. Generate: blockstate JSON, block model JSON (parent: block/cube_all or cube), item model JSON (parent: block model).
7. Add loot table (self-drop).
8. Add en_us.json entry only — remind user to propagate to other 19 lang files or use update-lang skill.
9. Show all files labeled: // File: src/main/java/...
