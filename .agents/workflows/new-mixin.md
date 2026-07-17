---
description: 
---

new-mixin
Steps:
1. Ask: "Target class? Goal (cancel event / inject logic / redirect call / modify return value)?"
2. Choose minimum-invasive injection point (@Inject before return, @ModifyVariable, @Redirect).
3. Generate Mixin class with correct @Mixin, @Shadow if needed, and @Unique for helpers.
4. Remind to add entry to `mixins.json` — show the exact line to insert.
5. Add one-line comment explaining why this Mixin exists.
