# create-brief
Produce a machine-executable implementation brief for OpenCode/DeepSeek.

## When to use
Called at the end of every planning session before handing off to OpenCode.
The brief replaces vague descriptions — every action must be byte-precise.

## Output format

Write the brief to `.agents/briefs/brief.md` using EXACTLY this structure:

```markdown
# Brief: <one-line task title>
_Generated: <date>_

## Context (read-only, no changes needed)
- Key files involved: list paths
- Relevant classes: list class names + what they do
- Patterns to copy from: list an existing class as a reference

## Actions

### ACTION 1 — CREATE src/main/java/.../<ClassName>.java
<full file content — no placeholders, no TODOs, complete compilable code>

### ACTION 2 — MODIFY src/main/java/.../<ClassName>.java
FIND (exact string, ≥3 lines of context):
\`\`\`
<exact existing code block>
\`\`\`
REPLACE WITH:
\`\`\`
<new code block>
\`\`\`

### ACTION N — APPEND src/main/resources/assets/cbc_autotarget/lang/en_us.json
Add before the closing `}`:
\`\`\`
  "block.cbc_autotarget.xxx": "Display Name",
\`\`\`

## Validation checklist
- [ ] `./gradlew build` passes with no errors
- [ ] Block appears in creative tab
- [ ] Lang key exists in en_us.json
- [ ] Loot table drops the block
```

## Rules for writing a good brief

### FIND/REPLACE precision
- FIND block must be copy-pasted verbatim from the actual file
- Include 3+ lines of context so the location is unambiguous
- Never use `...` inside FIND blocks
- If inserting at end of a list/register, show the last existing entry + new entry

### Code completeness
- CREATE actions must contain the FULL file — no skeleton, no placeholder methods
- Import statements must be complete and correct
- Registration IDs must match project naming convention (snake_case)

### Self-contained
- The brief must be executable without reading any source file
- All class names, paths, resource locations must be spelled out explicitly
- Reference the project-index for existing IDs to avoid collisions

### Token budget for OpenCode
- Keep briefs under 4000 tokens where possible
- If the task needs 5+ CREATE files, split into brief-1.md and brief-2.md
