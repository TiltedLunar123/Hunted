# Which Minecraft versions this works on

Short answer: the released build targets **Minecraft 26.2** on Fabric. The
source also compiles clean against **26.1.2**, and the CI build proves it on
every push.

Older than that is a genuine break rather than laziness, and it is worth
explaining why, because the usual assumption is that a mod can support
everything with enough effort.

## What changed in the 26 series

Mojang changed three things at once between 1.21.11 and 26.1, and any one of
them alone would be enough to stop a single jar covering both.

**Class names moved.** `ResourceLocation` became `Identifier`. `Zombie` moved
into a `monster.zombie` package. `GameRules` moved to `world.level.gamerules`
and the rules themselves became typed `GameRule<T>` objects read through a
generic getter instead of `getBoolean`.

**Methods were renamed.** `Entity.moveTo` became `Entity.snapTo`.
`CommandSourceStack.hasPermission(int)` was replaced by
`Commands.hasPermission(level)` returning a predicate.

**The Java requirement moved from 21 to 25.** A jar compiled for Java 25 will
not load on a Java 21 runtime at all, regardless of what the Minecraft API looks
like.

There is a fourth change that is good news rather than bad. Minecraft 26.x ships
**deobfuscated**: the client jar contains real `net.minecraft.**` class names.
That is why Yarn mappings stop at 1.21.11 and why the build has no `mappings`
line, and it is why Loom has no remap step here.

## Building for another version

Both values live in `gradle.properties`:

```properties
minecraft_version=26.2
fabric_api_version=0.158.0+26.2
```

Change them together, then:

```
./gradlew build
```

Verified working:

| Minecraft | Fabric API | Result |
| --- | --- | --- |
| 26.2 | 0.158.0+26.2 | Builds, shipped |
| 26.1.2 | 0.155.2+26.1.2 | Builds clean |

If you build for 26.1.2 and want the installer to offer it, add it to
`supported_minecraft_versions` in the same file.

## Porting to 1.21.x

That one is a real port. The renames above have to be undone, `ValueInput` and
`ValueOutput` go back to `CompoundTag`, and the Java target drops to 21.

The sensible shape for that is a branch per Minecraft version, which is how
Baritone has handled the same problem for years. If you want to do it, the code
that would need touching is small and contained: `LevelWorldView`,
`HunterEntity`, `TargetTracker`, `HuntedCommand` and the client renderer.
Everything in `path` and `survival` is either pure logic or uses APIs that did
not move.
