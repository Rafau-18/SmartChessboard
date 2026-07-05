# `change.md` — Schema Reference

> This document reconstructs the `change.md` schema from the change-management skill
> definitions themselves (`10x-new`, `10x-plan`, `10x-implement`, `10x-impl-review`,
> `10x-archive`), which reference it ("See `docs/reference/change-md.md` for the full
> schema reference").

`change.md` is the tiny identity file of a change folder (`context/changes/<change-id>/`).
It is **record-only**: skills read and stamp it, but nothing enforces transitions at
runtime. The single source of truth for execution state is the `## Progress` section in
`plan.md` — never this file.

## Frontmatter

```yaml
---
change_id: <kebab-case-slug>     # must equal the folder name
title: <human-readable title>    # ≤ 80 chars, sentence case
status: <see lifecycle below>
created: <YYYY-MM-DD>
updated: <YYYY-MM-DD>            # bumped by every skill that stamps this file
archived_at: null                # set only by /10x-archive
---
```

Project-local extension (not in the upstream template, used in this repo):

- `roadmap_id: <F-NN | S-NN>` — links the change to its roadmap item. `/10x-archive`
  independently matches roadmap items by `Change ID`, so this field is for humans.

## Status lifecycle

| Status | Set by | When |
| --- | --- | --- |
| `new` | `/10x-new` | Folder + identity file created. |
| `planned` | `/10x-plan` | `plan.md` + `plan-brief.md` written. |
| `plan_reviewed` | `/10x-plan-review` | Plan readiness review done. |
| `implementing` | `/10x-implement` | On entry — only from `{planned, plan_reviewed}`. Idempotent across phases. |
| `implemented` | `/10x-implement` | Epilogue after the final phase (explicitly NOT setting `archived_at`). |
| `impl_reviewed` | `/10x-impl-review` | After `reviews/impl-review.md` is saved. |
| `archived` | `/10x-archive` | Folder moved to `context/archive/<created>-<change-id>/`, `archived_at` stamped. |

Gate semantics: `/10x-archive` is lenient warn-only — it hard-blocks only on uncommitted
files inside the change folder; a status outside `{implemented, impl_reviewed}` produces a
warning plus a confirmation prompt, not a refusal.

## Body

`## Notes` — free-form: links, ad-hoc context, decisions that don't belong in
research/frame/plan. A short descriptive paragraph with pointers to `plan.md`,
`plan-brief.md`, the roadmap item, and contract sections is a fine substitute.

## What is intentionally NOT in `change.md`

- Progress checkboxes or phase state (live in `plan.md` → `## Progress`).
- Plan content, research findings, review verdicts (live in their own artifacts).
- Any state-file sidecar — skills must not introduce one.
