# Ephemeral notes

Drop one-off task notes, hand-off instructions, or short-lived work artifacts here.

## What goes in `ephemeral/`

- Task briefs the user pasted in for a specific piece of work — e.g. a one-off rollout runbook for a deploy that has since happened.
- Step-by-step rollout / migration runbooks tied to a single deploy.
- Investigation notes that captured something useful at the time but won't be relevant in three months.

## What doesn't

- Anything that documents how the codebase *currently* works — that goes in a regular `.claude/<topic>.md` reference doc (e.g. `../vetsmod_guild_system.md`).
- Memory entries — those live in the auto-memory system, not here.

## Lifecycle

Files in `ephemeral/` are expected to age out. When you finish a task, look at the notes you accumulated and either:
- Distil the durable parts into a regular `.claude/<topic>.md` doc, or
- Delete the file.

If a file has been sitting here for months and nobody's touched it, it's probably already obsolete — read it once and decide whether to graduate it or delete it.

## Convention

The folder is named `ephemeral/` in every repo in this workspace. (Earlier names — `temporary-ephemeral/`, `temporary-instructions/` — have all been unified.)
