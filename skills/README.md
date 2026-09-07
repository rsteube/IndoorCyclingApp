# Skills

Agent skills this project publishes for **other people's projects**.

A skill is a folder of instructions an AI coding agent loads when it recognises the task. These
live here rather than under `.claude/` for three reasons: they are project deliverables rather
than per-machine tool configuration, they are meant to be read by humans too, and the people
who need them are mostly working in a different repository from this one.

| Skill | For |
|---|---|
| [`indoorbike-companion/`](indoorbike-companion/) | Building the client side of the Companion API: a program on another device that owns the world, the rider's position and their virtual speed while Indoor Bike keeps the trainer, the gears and the recording |

## Using one

A skill folder is self-contained. Copy it, whole, into whichever of these the agent reads:

```bash
# Just for the companion project
cp -r skills/indoorbike-companion /path/to/companion-project/.claude/skills/

# Or for every project on this machine
cp -r skills/indoorbike-companion ~/.claude/skills/
```

Copy rather than symlink unless you are sure your tooling follows links: it does not on every
platform, and a skill that silently fails to load is worse than one that is obviously stale.

Anything a skill needs must be inside its own folder. `indoorbike-companion` therefore carries
its own copy of the protocol reference, because the companion developer will not have this
repository checked out.

## Keeping them true

`skills/indoorbike-companion/references/protocol.md` is the **source of truth** for what the
app does with every frame, not a copy of something in `docs/`. When the wire behaviour changes,
change it there, and change [`docs/COMPANION_API.md`](../docs/COMPANION_API.md) with it - that
document is the prose specification and the reasoning, this one is the normative description of
the implementation. Where the two disagree, the reference is right.

The obligation runs the same way as the one between
[`RouteStats.kt`](../shared/src/commonMain/kotlin/app/indoorbike/shared/route/RouteStats.kt) and
[`website/assets/routelib.js`](../website/assets/routelib.js): a published contract that
somebody has already shipped against is expensive to get wrong, so it is worth the discipline
of updating both halves in the same commit.
