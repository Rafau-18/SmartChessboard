---
name: handoff
description: Update context/SESSION_HANDOFF.md to reflect the current project state — what changed this session, sub-project status, and the concrete next step. Use at the end of a work session or when the user asks to refresh the handoff.
---

Update `context/SESSION_HANDOFF.md` so the next session can resume from cold.

1. Read the current `context/SESSION_HANDOFF.md` first — preserve its section structure and tone. Update it in place; don't rewrite from scratch.
2. Gather current state: `git status --short`, `git log --oneline -10`, and what changed during this session.
3. Refresh the parts that moved:
   - **Status / module progress** — what was completed this session, what's next.
   - **Sub-project state table** — only the rows that actually changed.
   - **Next step** — a concrete, actionable first move for the next session.
4. Convert relative dates to absolute (use today's date).
5. Keep it factual and concise — a resume-from-cold handoff, not a changelog. Capture what isn't derivable from code or `git log` (decisions, the "why", open threads); don't duplicate the commit list.

Do not invent state. If unsure whether something landed, verify with git or by reading the relevant file before claiming it's done.