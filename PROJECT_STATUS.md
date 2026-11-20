# Command Studio 26.3 status

- Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.18-SNAPSHOT, JDK 25, modid `commandstudio`, package `khoa.studio`, client only.
- Depends on KhoaAPILibs (`khoaapi`, compiled from `libs/KhoaAPILibs-1.0.0.jar`, a separate mod at runtime).
- Scaffold copied from khoaapilibs build files (same versions), not from the Fabric generator zip (generator is a JS app; its options match: no Kotlin, mojmap, no datagen, single source set).

## Done (2026-11-17), build OK, jar deployed, loads in the 26.3 profile (log shows `commandstudio 0.1.0`)
- `/commandstudio` opens the screen: generator sidebar (Give active, the other GamerGeeks generators listed as planned), Give panel, output panel.
- Give generator: item (KhoaItemPicker with all tabs, or the creative picker), count (shows real max stack), target (default @p), ItemStack text box <-> component rows (two way, invalid text kept + error, last valid model kept), component selector (searchable, from the live `DATA_COMPONENT_TYPE` registry, transient/no-codec types skipped), per component: value box, modified marker, reset to default, remove, per row validation.
- Syntax comes from the game: `ItemParser` parses, `ItemStack.CODEC` / component codecs encode to SNBT (`ItemText`). No templates or hardcoded component lists.
- Output: multiline read-only box, Copy (clipboard), Run (`connection.sendCommand`, server permissions apply, config kept), Reset (needs a second click).
- Persistence: `config/commandstudio.json` (local, not per world), debounced save (1 s), saved on screen close, after Run and on client stop; unreadable file is renamed `.broken`.

## NOT verified in game
Everything above is compile + load verified only. No GUI interaction, Run, Copy, persistence round trip, or layout at other resolutions was tested.

## Known gaps / next
- A newly added component without a default has an empty value: invalid until typed (no example values yet; component editors are plain SNBT text, not typed editors like checkbox/colour/list).
- Phase 4 Entity generator not started: needs Entity selector, common settings, equipment editor, item entity, summon serializer. Plan: full NBT per mob from a sample entity saved at runtime (`EntityType.create` + save), editor registry, `/summon` via the game's own parsers.
- Multi-command output works in Run (line by line) but there is no batch confirmation. Presets, expand/collapse persistence, scroll persistence, search text persistence: not done.
- Other generators: not started.

## 2026-11-17 (2): Entity generator slice (build OK, jar deployed, NOT tested in game)
- `/commandstudio` opens the last used generator; sidebar rows switch Give <-> Entity.
- Entity: selector from the live ENTITY_TYPE registry (canSummon only, via the generic list screen), position, name (Component codec), tags, flags (7 boolean NBT keys), equipment slots head/chest/legs/feet/mainhand/offhand (KhoaItemPicker, creative picker, or item text), NBT tab with "Load default NBT" (sample entity saved with `Entity.saveWithoutId` + `TagValueOutput`, Pos/UUID/Motion... removed), `/summon` built by `EntitySerializer` (TagParser + ItemStack/Component codecs), Run/Copy/Reset, persisted in config/commandstudio.json.
- UNVERIFIED: that `equipment.{head,chest,legs,feet,mainhand,offhand}` is the right NBT shape in 26.3 (check with "Load default NBT" on an armed mob and in game); flag keys are standard NBT names, not checked against a saved sample.
- Missing from the spec: entity-specific editors/registry (villager, armor stand, displays, area effect cloud...), item entity editor, passengers, attributes, rotation field, bidirectional equipment text <-> picker with item preview, validation of NBT keys against the type.

## 2026-11-18: verified NBT keys (javap -c -constants on the 26.3 merged jar)
- VERIFIED: Entity saves CustomName, CustomNameVisible, Glowing, Invulnerable, NoGravity, Silent, Tags; Mob saves NoAI, PersistenceRequired; LivingEntity saves `equipment`; EquipmentSlot names head/chest/legs/feet/mainhand/offhand (+body, saddle). So the Entity serializer keys are right; body/saddle slots are not exposed yet.
- Added: flags NoAI/PersistenceRequired only for Mob types, equipment only for LivingEntity (class taken from a sample instance); the serializer reports an error otherwise, the UI greys the flag / explains the tab. Build OK, deployed, still NOT tested in game.

## 2026-11-18: fixes + in-process GUI self test
- Fixed: crash after picking a component (callback order), per-screen sidebars (now `Sidebar`), overlapping layout on small windows (compact, height-based layout).
- `StudioSelfTest` drives the real screens with MouseButtonEvent / CharacterEvent / KeyEvent. `gradlew runClient -Pautotest` = auto join dev world + run + close; `/commandstudiotest` in a world. Result 2026-11-18: 29 passed, 0 failed (Give + Entity flows).
- Not covered by the test: visual look, other window sizes, multiplayer permission refusal, restart persistence across game launches (state file is only re-read via reopen in one session).

## 2026-11-18 (later)
- Typed component editors (ComponentKinds from DataComponents generics), enchantment picker + level dialog (1-255, notice above normal max), small-window adaptive layout, Saved Items tab via KhoaItemPicker.anyItem(). Creative picker buttons and /khoapicker /khoacreative commands removed (Khoa: not needed).
- Entity 'Fields' tab (EntityFields): typed editors derived from the sample entity NBT (bool/number keeping tag type/text/item picker). Self test: 60 passed, 0 failed.
- Still missing: passengers, attributes, rotation, presets, batch-run confirmation, other generators, typed editors for list/color/object components.

## 2026-11-18 (evening)
- 17 form generators (Forms.java / FormGeneratorScreen): setblock, fill, particle, attribute, effect, enchantment, tellraw, playsound, teleport, rotate, scoreboard, team, bossbar, damage, gamemode, kill, ride. Commands validated by the client command dispatcher (CommandCheck). Registry choices via KhoaListPicker.
- /runcommand <count> [delay] "<cmd>" (+stop); RunFlow confirm dialog (KhoaConfirmScreen) for >1 command; Sidebar scrolls; narrow window uses a next-generator button.
- khoaapilibs: ChatLimitMixin (256 limit lifted for / commands, 32767), KhoaConfirmScreen, KhoaListPicker, Saved Items tab.
- Self test (gradlew runClient -Pautotest, separate config file, respawns/creative/day): 99 passed, 0 failed. NOT done: visual review by screenshot (Minecraft has no main render target accessor in 26.3 here; use pyautogui+PIL, both installed), real OS mouse script, peaceful-difficulty check of Load default NBT (EntityType.create may return null for hostile mobs on peaceful: unverified).

## 2026-11-18 Big update: item-aware GUI
- khoaapilibs: KhoaEffectPicker + KhoaEffectDialog (level 0-255, duration -1 or >=1), KhoaBannerEditor (loom-like, real banner preview, game codec), KhoaTrimEditor (preview armor only, output has no item id), KhoaPane.
- Studio: ItemTools (tools chosen from item default components/tags), Give shows Effects/Banner/Trim buttons; CommandStudioScreen embedded mode (Use item) used by Entity item fields; item entity gets an Item field.
- Survey (run/config/commandstudio-survey.txt): thrown entities/arrows/potions have an ITEM field; no Owner key in sample NBT, so no owner tag added.
- Autotest: 122 passed (+1 harness step fixed, rebuilt, not re-run).
- Not done: arrow "effect?" checkbox shortcut, visual screenshot review, area_effect_cloud effects.
