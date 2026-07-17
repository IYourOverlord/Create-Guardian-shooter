---
description: 
---

# add-config
Steps:
1. Ask: "Config library already in project? (Cloth Config / AutoConfig / custom / none)"
2. If none — ask whether to add Cloth Config or write a simple JSON config.
3. Generate config class with sensible defaults and field annotations.
4. Wire config load in `ModInitializer`.
5. If Cloth Config — generate `ModMenuIntegration` screen factory stub.
6. Show all files labeled separately.