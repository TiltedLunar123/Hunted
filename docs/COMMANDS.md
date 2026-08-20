# Commands

Every command needs permission level 2, which means operator on a server and
cheats enabled in single player.

## Spawning

```
/hunted spawn
/hunted spawn <tier>
/hunted spawn <tier> <player>
/hunted spawn <tier> <player> <survival>
```

Puts a hunter on a random bearing at the configured distance, on whatever
surface is there, and points it at a player. With no arguments it uses the
default tier from the config and targets you.

The last form takes a true or false for survival mode, overriding the config for
that one hunter.

Tiers are `scout`, `stalker`, `rival`, `enforcer` and `relentless`. The numbers
1 to 5 work too, and `hunter` is accepted as an old name for `rival`.

`scout` and `stalker` only know what they can see and hear. `rival`, the default,
and everything above it always know where their target is.

## Managing

```
/hunted clear
```

Removes every hunter in every dimension.

```
/hunted status
```

Lists each active hunter with its tier, dimension, position, health, what it is
doing, and how many blocks it has broken and placed. In survival mode the
activity column shows what it is now working toward, so you can watch it
climb the ladder from wood to iron.

Underneath each hunter it prints the tactic it has chosen and why:

```
Rival in overworld at 118 63 -204, 20/20 hp, gathering oak_log, 12 broken, 0 placed
    counter_shield: they have a shield, going to make an axe
```

The tactics are `rush`, `press`, `engage`, `defend`, `counter_shield`, `gear_up`
and `withdraw`. This is the fastest way to work out why a hunter is standing in a
forest instead of coming for you.

## Settings

All of these write to `config/hunted.json` and take effect right away.

```
/hunted tier <tier>
```

The tier used when `/hunted spawn` is called without one.

```
/hunted survival <true|false>
```

Whether hunters spawn empty handed and gather their own equipment. On by
default, because handing a hunter free gear is a cheat like any other. Turn it
off if you want an immediate fight rather than a hunter that spends its first
few minutes in a forest.

```
/hunted taunts <true|false>
```

Whether the hunter talks to its target in chat. On by default. It speaks when
its plan changes rather than on a timer, with a hard floor of eight seconds
between lines, so a quiet chase stays mostly quiet.

```
/hunted terrain <true|false>
```

Whether hunters may break and place blocks at all. Turning this off leaves them
able to path, jump and fight, but they will walk around anything solid.

The vanilla `mobGriefing` game rule is also respected. Either one being off stops
terrain edits.

```
/hunted dimensions <true|false>
```

Whether hunters chase you into the Nether and the End.

```
/hunted glow <true|false>
```

Outlines every hunter through walls. Useful for recording or for working out why
one is stuck. Ruinous for tension.

```
/hunted distance <8-256>
```

How far from the target a newly spawned hunter appears, in blocks.

```
/hunted warn <true|false>
```

Whether the target gets a line in chat when a hunter spawns. On by default.
Turning it off means the first thing they know about it is the hunter.

```
/hunted maxhunters <1-16>
```

How many hunters one player may be chased by at once. One by default. Spawning
past the limit is refused rather than silently ignored, and hunters in other
dimensions still count against it.

## The config file

Everything above is stored in `config/hunted.json`, which is written whenever a
setting changes and read at startup. Editing it by hand works, but the file is
rewritten on the next command, so use the commands where you can.

| Key | Command | Default |
| --- | --- | --- |
| `defaultTier` | `/hunted tier` | `rival` |
| `survivalStart` | `/hunted survival` | `true` |
| `taunts` | `/hunted taunts` | `true` |
| `allowTerrainDamage` | `/hunted terrain` | `true` |
| `crossDimensions` | `/hunted dimensions` | `true` |
| `glowing` | `/hunted glow` | `false` |
| `spawnDistance` | `/hunted distance` | `48` |
| `announceSpawn` | `/hunted warn` | `true` |
| `maxHuntersPerPlayer` | `/hunted maxhunters` | `1` |
