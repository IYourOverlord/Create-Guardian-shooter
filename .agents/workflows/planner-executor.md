# Workflow: Planner → Executor (Antigravity → OpenCode)

## Problem this solves
When the planning agent runs out of tokens mid-task, no work gets done.
This workflow separates **planning** (Antigravity Agent) from **execution** (OpenCode + DeepSeek)
so that each agent does exactly one thing and never wastes tokens on the other's job.

---

## Stage 1 — Planner (Antigravity Agent)

**Input:** Free-form task description from the user.

**Steps:**
1. Read `project-index` skill (always first).
2. Read only the files actually needed for this task.
3. Use `create-brief` skill to produce `.agents/briefs/brief.md`.
4. Write the brief — complete, no TODOs, fully compilable code.
5. Tell the user: "Brief ready at `.agents/briefs/brief.md`. Run `execute-brief` in OpenCode."

**Rules:**
- Do NOT write any code to actual source files.
- Do NOT guess imports or class names — read the real file first.
- The brief is the only output.

---

## Stage 2 — Executor (OpenCode + DeepSeek)

**Input:** `.agents/briefs/brief.md`

**Steps:**
1. Read `brief.md` in full.
2. Execute every ACTION in order:
   - CREATE → write the full file as given.
   - MODIFY → find the exact FIND block, replace with REPLACE WITH block.
   - APPEND → add the snippet at the specified location.
3. Run `./gradlew build`.
4. If build fails → fix compilation errors only, do not redesign.
5. Mark each checklist item in the brief.

**Rules:**
- Zero interpretation. Execute exactly what is written.
- Do NOT rename, refactor, or "improve" the code.
- If a FIND block does not match exactly, stop and report to user — do not guess.

---

## Handoff command (OpenCode)

Add to `opencode.json` commands:
```json
"execute-brief": "cat .agents/briefs/brief.md"
```

Then in OpenCode terminal: `ctrl+p` → type `execute-brief`
This feeds the brief as the first message so DeepSeek executes it immediately.

---

## Token budget guidelines

| Phase | Model | Expected tokens |
|-------|-------|----------------|
| Planning | Gemini / Claude | 8k–20k (reading + writing brief) |
| Execution | DeepSeek | 4k–12k (writing files + build) |

Keep briefs under 4000 tokens. Split into `brief-1.md` / `brief-2.md` for large tasks.

---

## Troubleshooting

**"FIND block not found"** → Planner read a stale file. Re-run planning with fresh file read.

**"Build fails after execution"** → Check imports in CREATE files. Add a fix ACTION to brief.

**"Brief is ambiguous"** → Planner did not read the real file. Add explicit file reads to planning.
