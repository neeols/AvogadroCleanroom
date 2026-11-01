# Avogadro for Cleanroom — agent and integration reference

Use this file when generating molecule JSON, Java integration code, or GroovyScript
for this repository. It includes the usage/API reference so it can be pasted into
an LLM without additional files. The same reference is included in llms.txt.

## Working in this repository

- Read the version and dependency declarations in build.gradle before changing
  behavior. The reference below describes 1.0.0-cleanroom.57; update both this file
  and llms.txt when public signatures, data formats, or supported versions change.
- Preserve the io.github.wurtzitane.avogadro package, avogadro mod ID, saved IDs,
  and config names unless the requested change requires a migration.
- Generate requested pack content in the user's mod or pack location. Keep sample
  chemistry out of this library's shipped resources; the library starts empty.
- Keep optional integrations safe when absent and client references out of common
  code that loads on a dedicated server. Query serverView for server decisions.
- Use plain comments for non-obvious behavior, preserve upstream credits and the
  MIT license, and avoid unrelated edits or machine-specific files.
- Build with the included Java 25 Gradle wrapper: .\gradlew.bat build on Windows,
  ./gradlew build on macOS/Linux. runClient/runServer launch development games.
  Distributables are in build/libs; -mcp.jar is a development artifact.
- Check generated content against the schema and signatures below. Report the
  checks actually performed and any runtime behavior that remains untested.
- Leave commits and pushes to an explicit user request.

The API listings below are signature references, not complete Java compilation
units. Runnable examples name their context or give a complete host class.

Avogadro is a Minecraft 1.12.2 library mod for Cleanroom. It renders rotating
ball-and-stick molecule models in item/fluid tooltips, the Had Enough Items
(HEI) Molecules category, supported world HUDs, and optional generated molecule
items. It also provides an atomic examiner and GIF/MP4 exports. Original
Avogadro is by Trifectum Software; this port is maintained by wurtzitane under
the preserved MIT license.

This document can be pasted into an LLM to generate JSON definitions, Java
integration code, or GroovyScript scripts. The API below describes version
1.0.0-cleanroom.57 in this repository. Target Minecraft 1.12.2, Cleanroom
0.7.4-alpha, ModularUI 3.1.6, and Java 25. The build uses Gradle 9.8.1 and
CleanroomGradle 0.22.0. HEI 4.36.1, GroovyScript 1.4.3, HWYLA/Waila, and The
One Probe integrations are optional. This is a Cleanroom mod, not a NeoForge
1.21 mod. Use Forge 1.12 classes such as ResourceLocation and ItemStack.

## Instructions for generating content

- Give complete code and the destination path. JSON files contain strict JSON;
  do not put comments or Java/Groovy expressions inside them.
- Use lowercase namespaced IDs, such as `example:water`. File paths determine
  JSON molecule IDs; Java/Groovy builders take the ID as an argument.
- Use SMILES for a small conventional molecule, explicit atoms/bonds for a
  precise arrangement, and structure operations for repeated or lattice models.
  Explicit atom lists contain every atom that should appear, including hydrogen.
- Choose one geometry source. `structure` cannot coexist with `copy`, `atoms`,
  `bonds`, or `smiles`. For legacy combinations, `copy` wins over `atoms`, which
  wins over `smiles`; avoid those combinations in new definitions.
- Do not assume an existing water, benzene, or any other molecule definition.
  The library ships without chemistry definitions; the category icon is artwork.
  A copy/component target must be supplied by the pack or mod.
- Use exact signatures from this document. Do not invent convenience methods,
  modern registry APIs, Groovy aliases, or client calls on a dedicated server.
- Distinguish shape from chemistry: SMILES layout and electron paths are
  schematic. Use explicit coordinates for exact geometry and explicit atomic
  configurations for proton/neutron/electron data.

## Where molecule files live

Common server sources are merged in this order, with later valid definitions
winning when IDs collide:

1. Installed mod JARs/directories: `data/<namespace>/molecules/<path>.json`.
2. Retained Java registrations through `AvogadroApi.owner(...)`.
3. Instance JSON: `config/avogadro/server/data/<namespace>/molecules/<path>.json`.
4. World JSON: `<world>/avogadro/data/<namespace>/molecules/<path>.json`.
5. GroovyScript registrations.

Each path registers `<namespace>:<path>` without the `.json` extension.
For a standalone pack definition, the instance or world path is usually easiest.
Minecraft 1.12.2 has no vanilla modern datapack engine here: placing files under
`<world>/datapacks` does not make Avogadro discover them.

Connected clients receive the common server snapshot, then apply:

1. Client assets: mod `assets/<namespace>/molecules/<path>.json`, selected
   resource packs, server resource pack, then
   `config/avogadro/resources/assets/<namespace>/molecules/<path>.json`.
2. Local JSON: `config/avogadro/molecules/<path>.json`, which registers
   `avogadro:<path>`.

Before connecting, clients use local common resources/registrations as the base.
Client overrides do not change the server's authoritative view. Invalid
definitions are logged and skipped; an earlier valid same-ID definition can
survive a rejected override. Each resource file is limited to 256 KiB.

`/avogadro reload` reloads common server sources and sends them to connected
Avogadro clients; it requires permission level 2. F3+T reloads client assets and
local files. Client and server Avogadro versions must match when both are
installed. Java/Groovy registration commits also update the relevant views.

## File format

One JSON object per file. Geometry is required; associations are optional.
For `config/avogadro/server/data/example/molecules/acetone.json`:

```json
{
  "smiles": "CC(=O)C",
  "name": "Acetone",
  "formula": "C3H6O",
  "items": ["example:acetone_bottle"],
  "item_tags": ["example:ketones"],
  "fluids": ["example:acetone"],
  "fluid_tags": ["example:acetone"],
  "match_containers": true,
  "generate_item": false
}
```

Registry names above are placeholders for the user's installed content. Unknown
registry names do not create items or fluids. In 1.12, a fluid's registry name
is a String; the unqualified name `water` is normalized to `minecraft:water`
for matching. Do not invent a separate vanilla flowing-water fluid registration.

Field reference:

- `smiles`: supported SMILES string. Branches `( )`, disconnected fragments `.`,
  ring closures `0`–`9` and `%nn`, and bond symbols `-`, `=`, `#`, `:`, `/`, and
  backslash are accepted. Organic/aromatic symbols, recognized two-letter
  symbols, and bracket atoms are supported. Implicit hydrogens are added using
  the parser's valence rules, then a schematic layout is produced. Examples:
  `O` water, `C` methane, `CCO` ethanol, `O=C=O` carbon dioxide,
  `c1ccccc1` benzene. Chirality/reaction markers are not stereochemical
  instructions; bracket charge/isotope syntax does not supply examiner data.
- `atoms`: array of objects `{"e":"O","x":0,"y":0,"z":0}`. Coordinates must
  be finite numbers; element symbols are case-sensitive. Explicit atoms can
  also contain a `nuclear` atomic-configuration object (see below).
- `bonds`: explicit connectivity for top-level `atoms`. Each bond is
  `[from,to]` or `[from,to,type]` using zero-based indices. Types are `SINGLE`,
  `DOUBLE`, `TRIPLE`, `QUADRUPLE`, numeric `1`/`2`/`3`/`4`, or `-`/`=`/`#`.
  Omit the entire field to infer bonds from distance. `"bonds": []` disables
  inference. Explicit atoms do not gain implicit hydrogens.
- `structure`: generated geometry object with atoms, bonds, optional unit cell,
  and ordered operations. Its full schema and examples appear below.
- `copy`: ID of another supplied molecule. Reuses its geometry and inherits
  missing formula/name/generated-item/container settings. Association lists
  are unioned with the base; atomic maps and component/inspection data are
  inherited and extended. Missing targets and copy cycles are rejected.
- `formula`: display formula. If absent, it is computed from actual atom counts
  in Hill order. For generated items, its composition must equal rendered atom
  counts. Labels display ordinary numeric counts as chemical subscripts.
- `name`: optional display name, 1–256 characters.
- `items`: item selectors. `minecraft:coal` matches all metadata,
  `minecraft:coal@1` matches charcoal metadata, and
  `example:vial@0#{Contents:"water"}` requires matching partial compound NBT.
  Escape embedded quotes when placing that selector inside a JSON string.
- `item_tags`, `fluid_tags`: Avogadro tag dictionary IDs, with optional leading
  `#`. These are not modern vanilla tags or Forge ore-dictionary names.
- `fluids`: fluid selectors normalized to resource IDs, optionally followed by partial compound NBT, such as `example:fluid#{Grade:"pure"}`.
- `materials`: optional legacy GregTechCEu material IDs (see compatibility).
- `match_containers`: defaults to `true`; allows a matching fluid in an item's
  fluid capability to supply its molecule. `false` disables that container match.
- `generate_item`: defaults to `false`; exposes a molecule sample through the
  shared `avogadro:molecule` item. It stores the molecule ID rather than a new
  per-molecule item registry entry. Obtain a stack through `AvogadroApi.moleculeItem`.
- `elements`, `atom_configurations`, `components`, `inspection`: optional
  examiner data; exact format appears below.
- `diagram`, `tooltip`: old presentation fields are accepted but ignored.
  New definitions should use the current client settings instead.

Unknown top-level keys and unsupported atom/structure fields are rejected.
Do not put the molecule ID in an extra `id` key.

## Tag dictionaries

Use `data/<namespace>/tags/items/<path>.json` and
`data/<namespace>/tags/fluids/<path>.json` alongside common molecule files.
The same paths under `assets/` work for client resources. For example,
`config/avogadro/server/data/example/tags/items/water_containers.json`:

```json
{
  "replace": false,
  "values": ["minecraft:water_bucket", "#example:other_water_containers"]
}
```

The tag ID is `example:water_containers`. Values are string registry IDs or
`#tag` references. Later layers append unless `replace` is true. This is a small
Avogadro dictionary loader, not a general tag/datapack engine.

## Resolution order for a hovered item

1. A valid generated molecule stack uses its saved molecule ID.
2. Direct NBT-qualified selector; exact metadata comes before wildcard metadata,
   then more-specific NBT requirements, then later registration order.
3. Exact metadata selector, then registry-wide item selector.
4. Item tag match; earlier tag bindings win.
5. Contained fluid, if the selected fluid molecule permits `match_containers`.
6. Supported GregTech material binding, then material-path fallback.
7. Molecule-path fallback: `example:acetone` can match `anything:acetone`.

Full FluidStack lookup first uses matching NBT selectors (greater specificity, then
later registration), followed by direct fluid ID, fluid tag, supported GregTech
material binding/path, and fluid-path fallback. Bare Fluid lookup skips NBT rules. For duplicate
direct bindings and duplicate path fallbacks, later definitions win.
Item capability checks use Forge 1.12's item fluid-handler capability.
FluidStack tags are preserved through containers, views, tooltips, associations, reload and synchronization.

## GregTechCEu compatibility

The optional adapter targets GregTechCEu **1.12.2 2.8.10-beta** (v2.8.10).
Use `gregtech:water` in `materials`; `gtceu:water` is an explicit alias.
This does not imply support for GTCEu Modern or arbitrary GregTech versions.
When the adapter is unavailable, material matching is inactive and a diagnostic
is logged; ordinary item/fluid bindings and rendering still work.

```json
{
  "smiles": "Cc1ccccc1",
  "materials": ["gregtech:toluene"]
}
```

## Minimal JSON examples

Create `config/avogadro/server/data/example/molecules/water.json`:

```json
{
  "name": "Water",
  "formula": "H2O",
  "atoms": [
    {"e":"O","x":0,"y":0,"z":0},
    {"e":"H","x":-0.593,"y":0.459,"z":0},
    {"e":"H","x":0.593,"y":0.459,"z":0}
  ],
  "bonds": [[0,1],[0,2]],
  "items": ["minecraft:water_bucket"],
  "fluids": ["water"],
  "generate_item": true
}
```

After supplying the definition above, add a client-only binding in
`config/avogadro/molecules/extra_water.json` (ID `avogadro:extra_water`):

```json
{
  "copy": "example:water",
  "items": ["example:water_bottle@0#{Contents:\"water\"}"]
}
```

Sulfuric acid definition for a pack's existing acid bottle/fluid tag:

```json
{
  "smiles": "OS(=O)(=O)O",
  "items": ["example:acid_bottle"],
  "fluid_tags": ["example:sulfuric_acid"]
}
```

## Java API and lifecycle

The common API is in `io.github.wurtzitane.avogadro.api`. Geometry and loaded definitions use types in `io.github.wurtzitane.avogadro.molecule`. Client helpers are in `io.github.wurtzitane.avogadro.api.client` and must only be referenced by client code. These are Minecraft 1.12.2/Cleanroom APIs; use `net.minecraftforge.fml.common` lifecycle events and `net.minecraft.util.ResourceLocation`.

For a Java host mod, declare `required-after:avogadro` in its `@Mod` dependencies and register definitions during common initialization. Reuse an owner named after the host mod. Registration handles and their source layers survive molecule reloads and server restarts within the same game process. A runtime commit updates the server and synchronizes clients.

All signatures below omit repetitive `public` modifiers. `static` is shown where required. Record declarations give their public canonical constructor and accessor types; access them as methods, for example `definition.structure()`.


For Java host projects, compile against the mapped Avogadro development JAR in
an existing CleanroomGradle project, for example:

```groovy
// Host mod build.gradle; runtime Avogadro installation is separate.
dependencies {
    compileOnly files('libs/avogadro-1.12.2-1.0.0-cleanroom.57-mcp.jar')
}
```

This repository does not declare published Maven coordinates; do not invent a
remote dependency coordinate. Install the matching distributable Avogadro and
ModularUI JARs in the game. Development runs need mapped dependencies, and the
host mod's release JAR must pass through its normal Cleanroom reobfuscation.

### Common entry point: AvogadroApi

```java
package io.github.wurtzitane.avogadro.api;

static MoleculeBuilder AvogadroApi.builder(String id)
static StructureBuilder AvogadroApi.structureBuilder()
static MoleculeRegistration AvogadroApi.owner(String owner)
static MoleculeView AvogadroApi.registeredView()
static MoleculeView AvogadroApi.serverView()
static MoleculeWeight.Result AvogadroApi.weight(String formula)
static MoleculeWeight.Result AvogadroApi.weight(MoleculeDef molecule)
static ItemStack AvogadroApi.moleculeItem(MoleculeDef molecule)
static void AvogadroApi.excludeItemTooltip(ResourceLocation itemId)
static void AvogadroApi.excludeItemTooltipNamespace(String namespace)
static void AvogadroApi.excludeItemTooltipWhen(java.util.function.Predicate<ItemStack> predicate)
static boolean AvogadroApi.isItemTooltipExcluded(ResourceLocation itemId)
```

`registeredView()` contains startup resource definitions and retained registrations. It excludes world-specific JSON. `serverView()` is the authoritative server snapshot; it is empty until a server starts and again after it stops. Read client-visible definitions with the client API instead.

`moleculeItem` returns the generated molecule item stack for a definition that enables `generate_item`; null or disabled definitions throw IllegalArgumentException. Tooltip exclusion only suppresses the automatic item preview and preserves the item's molecule/HEI associations. There is no public method to remove an exclusion.

### MoleculeBuilder

```java
MoleculeBuilder(String id)
MoleculeBuilder(ResourceLocation id)
static MoleculeBuilder MoleculeBuilder.from(String id, Map<?, ?> fields)
ResourceLocation id()
MoleculeBuilder smiles(String value)
MoleculeBuilder copy(String value)
MoleculeBuilder copy(ResourceLocation value)
MoleculeBuilder formula(String value)
MoleculeBuilder name(String value)
MoleculeBuilder generateItem(boolean value)
MoleculeBuilder matchContainers(boolean value)
MoleculeBuilder items(Object... values)
MoleculeBuilder fluids(Object... values)
MoleculeBuilder itemTags(Object... values)
MoleculeBuilder fluidTags(Object... values)
MoleculeBuilder materials(Object... values)
MoleculeBuilder element(String symbol, int atomicNumber, int neutrons, int... shells)
MoleculeBuilder atomConfiguration(int index, int atomicNumber, int neutrons, int charge, int... shells)
MoleculeBuilder component(String id)
MoleculeBuilder inspectionMetadata(String key, String value)
MoleculeBuilder structure(StructureFragment fragment)
MoleculeBuilder structure(StructureBuilder builder)
int addAtom(String element, double x, double y, double z)
MoleculeBuilder atom(String element, double x, double y, double z)
MoleculeBuilder bond(int from, int to)
MoleculeBuilder bond(int from, int to, BondType type)
MoleculeBuilder bond(int from, int to, String type)
MoleculeBuilder bond(int from, int to, int type)
MoleculeBuilder noBonds()
MoleculeSpec build()
```

`addAtom` returns its zero-based index; `atom` supports fluent chaining. `bond(from, to)` adds a single bond. `noBonds()` writes an explicit empty bond list and disables automatic distance bonding. Each association method replaces that field's previous array. `component` appends a component reference; it does not add that component's geometry.

`items` accepts registry strings, `ResourceLocation`, `Item`, and nonempty `ItemStack`. An ItemStack preserves metadata unless it is the OreDictionary wildcard, and preserves NBT. `fluids` accepts registry strings, `ResourceLocation`, `Fluid`, and `FluidStack`; nonempty FluidStacks retain their NBT requirements, and qualified selector strings are supported. Tags and material references accept strings or `ResourceLocation`. Tags can begin with `#`.

`MoleculeBuilder.from` takes JSON field names such as `generate_item`, `match_containers`, and `atom_configurations`. Its association fields must contain a Collection or Object array. Fluent method names are camelCase.

`element` supplies one neutral atomic configuration for all atoms of a symbol. `atomConfiguration` supplies an index-specific override including charge. `structure` imports a finished fragment's atoms and bonds and rejects an already configured geometry source (`copy`, `smiles`, `structure`, `atoms`, or `bonds`). Choose the geometry source before building, and do not add a second source afterward.

`build()` validates and returns an immutable MoleculeSpec. For `generateItem(true)`, the formula's element counts must exactly match rendered atoms. A formula is a label, not a request to generate geometry. Every hydrogen that should be part of an explicit structure must appear in the atom list.

### Immutable source: MoleculeSpec

```java
static MoleculeSpec MoleculeSpec.fromJson(String id, String json)
static MoleculeSpec MoleculeSpec.fromJson(ResourceLocation id, String json)
ResourceLocation id()
String json()
MoleculeBuilder toBuilder()
```

The JSON contains the definition fields; the ID is supplied separately. JSON is normalized and validated before storage. Copy targets resolve after source layers merge. Early validation rejects self-copy and malformed fields, and registration validates against the real merged copy target. Use `toBuilder` to edit a source definition; it preserves existing geometry, so geometry replacement needs a fresh builder or JSON object.

### Owner-scoped updates: MoleculeRegistration and MoleculeBatch

```java
// Prefer AvogadroApi.owner rather than constructing the handle yourself.
MoleculeRegistration(String owner, boolean script)
String owner()
CompletableFuture<MoleculeView> register(MoleculeSpec definition)
CompletableFuture<MoleculeView> register(MoleculeBuilder definition)
CompletableFuture<MoleculeView> unregister(String id)
CompletableFuture<MoleculeView> clear()
CompletableFuture<MoleculeView> update(Consumer<MoleculeBatch> changes)
CompletableFuture<MoleculeView> replace(MoleculeBatch contents)

MoleculeBatch()
MoleculeBatch(MoleculeResources.Data initial)
MoleculeBatch register(MoleculeSpec definition)
MoleculeBatch register(MoleculeBuilder definition)
MoleculeBatch register(String id, Map<?, ?> fields)
MoleculeBatch remove(String id)
MoleculeBatch remove(ResourceLocation id)
MoleculeBatch itemTag(String id, boolean replace, String... values)
MoleculeBatch fluidTag(String id, boolean replace, String... values)
MoleculeBatch removeItemTag(String id)
MoleculeBatch removeFluidTag(String id)
MoleculeBatch clear()
MoleculeResources.Data data()
```

Owner names must match `[a-z0-9_.-]+`. Java integrations use `AvogadroApi.owner("yourmod")`; GroovyScript owns its own separate script layer. `update` begins with the owner's existing content. `replace` replaces the entire owner contribution. Removing/clearing an owner's entry reveals any lower resource layer; it does not remove that molecule from every source. Use one `update` callback for a dependent set of definitions and tags.

Commits validate the complete next snapshot before installing it. A rejected commit completes exceptionally and keeps the previous contribution. During an active server, calls from another thread are scheduled on the server thread. Do not block the server waiting for work that needs that same thread, and do not start another registration update inside an update callback. Handle the returned future to report failures. Before server startup it yields the registered view; during a server it yields the server view.

Low-to-high server precedence is mod JSON, Java registrations, instance server JSON, world JSON, GroovyScript registrations. The visible client view can add local overrides. Owner handles do not write files or edit another owner's layer.

`MoleculeResources.Data` is the immutable source container returned by `MoleculeBatch.data()`. Its package is `io.github.wurtzitane.avogadro.molecule`:

```java
record MoleculeResources.Data(
    Map<ResourceLocation, String> molecules,
    Map<ResourceLocation, String> itemTags,
    Map<ResourceLocation, String> fluidTags,
    Map<ResourceLocation, List<String>> moleculeFallbacks)

MoleculeResources.Data(Map<ResourceLocation, String> molecules,
                       Map<ResourceLocation, String> itemTags,
                       Map<ResourceLocation, String> fluidTags)
static MoleculeResources.Data MoleculeResources.Data.empty()
MoleculeResources.Data merge(MoleculeResources.Data later)
```

The String map values are JSON source texts, not parsed definitions. Prefer the batch methods for ordinary integrations.

### Complete Java registration example

This is a minimal host mod class. Change its mod ID, package, and version for your project. The atoms and bonds deliberately describe the same H2O composition as the generated item formula.

```java
package example;

import io.github.wurtzitane.avogadro.api.AvogadroApi;
import io.github.wurtzitane.avogadro.api.MoleculeBuilder;
import io.github.wurtzitane.avogadro.api.MoleculeRegistration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = "example", name = "Example Molecules", version = "1.0.0",
     dependencies = "required-after:avogadro", acceptedMinecraftVersions = "[1.12.2]")
public final class ExampleMod {
    private static final Logger LOGGER = LogManager.getLogger("example");
    private final MoleculeRegistration molecules = AvogadroApi.owner("example");

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        molecules.update(batch -> {
            MoleculeBuilder water = AvogadroApi.builder("example:water")
                    .name("Water")
                    .formula("H2O")
                    .generateItem(true)
                    .matchContainers(true)
                    .items("minecraft:water_bucket")
                    .fluids("minecraft:water");

            int oxygen = water.addAtom("O", 0, 0, 0);
            int firstHydrogen = water.addAtom("H", 0.96, 0, 0);
            int secondHydrogen = water.addAtom("H", -0.24, 0.93, 0);
            water.bond(oxygen, firstHydrogen).bond(oxygen, secondHydrogen);
            batch.register(water);
        }).whenComplete((view, failure) -> {
            if (failure != null) LOGGER.error("Could not register Example molecules", failure);
        });
    }
}
```

The API also accepts raw JSON when the caller already has a definition:

```java
MoleculeSpec source = MoleculeSpec.fromJson("example:methane",
        "{\"smiles\":\"C\",\"formula\":\"CH4\",\"name\":\"Methane\"}");
AvogadroApi.owner("example").register(source);
```

### Querying: MoleculeView

```java
static MoleculeView MoleculeView.empty()
long revision()
MoleculeDef get(ResourceLocation id)
MoleculeDef get(String id)
Map<ResourceLocation, MoleculeDef> definitions()
List<MoleculeDef> all()
List<MoleculeDef> sortedDefinitions()
List<MoleculeDef> generatedItemDefinitions()
Map<ResourceLocation, List<String>> itemTags()
Map<ResourceLocation, List<String>> fluidTags()
MoleculeDef find(ItemStack stack)
MoleculeDef find(Fluid fluid)
MoleculeDef find(FluidStack fluid)

MoleculeView(Map<ResourceLocation, MoleculeDef> definitions,
             Map<ResourceLocation, List<String>> itemTags,
             Map<ResourceLocation, List<String>> fluidTags, long revision)
MoleculeView(MoleculeStore.CompiledSnapshot compiled, long revision)
```

A view is an immutable snapshot and remains valid after a reload. Read a new view when current content is needed. `get` and `find` return null when unmatched; `find(FluidStack)` also returns null for null/nonpositive stacks. `sortedDefinitions` sorts string IDs, and `generatedItemDefinitions` is the sorted generated-item subset. Constructors are available but ordinary consumers use Avogadro's installed views.

Client resource reading, chemical layout and snapshot compilation run on bounded
background workers. `MoleculeClientStore.applyLocal` and `applyServerData` request
an asynchronous replacement; use the CLIENT `MoleculesChangedEvent` to observe
publication. The previous snapshot stays usable until the newest request is ready.
Superseded work is cancelled; publication and OpenGL calls stay on the client thread.
`MoleculeStore.replaceAll(MoleculeStore.CompiledSnapshot)` installs a precompiled
snapshot; ordinary integrations should register through `MoleculeRegistrations`.

```java
MoleculeView view = AvogadroApi.serverView();
MoleculeDef water = view.get("example:water");
if (water != null) {
    net.minecraft.item.ItemStack generated = water.generateItem()
            ? AvogadroApi.moleculeItem(water) : net.minecraft.item.ItemStack.EMPTY;
    MoleculeWeight.Result mass = AvogadroApi.weight(water);
    String formulaLabel = ChemicalFormula.display(water.formula());
}
```

### Change notifications: MoleculesChangedEvent

```java
enum MoleculesChangedEvent.Scope { REGISTRATIONS, SERVER, CLIENT }
MoleculesChangedEvent()
MoleculesChangedEvent(MoleculesChangedEvent.Scope scope, MoleculeView view, String owner)
MoleculesChangedEvent.Scope scope()
MoleculeView view()
String owner()
```

Listen on `MinecraftForge.EVENT_BUS` with Forge's `@SubscribeEvent`. Events fire after installation. REGISTRATIONS reports an owner contribution change, SERVER reports authoritative server publication, and CLIENT reports visible client publication. One operation can produce more than one scope; filter the scope needed. Owner is the updating owner's name on REGISTRATIONS and can be empty for reload/synchronization events. Events notify consumers and do not provide a cancellable pre-commit hook.

```java
import io.github.wurtzitane.avogadro.api.MoleculesChangedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

// During common initialization:
MinecraftForge.EVENT_BUS.register(new MoleculeListener());

// Put this class in its own file or inside your host mod class.
public final class MoleculeListener {
    @SubscribeEvent
    public void onMoleculesChanged(MoleculesChangedEvent event) {
        if (event.scope() != MoleculesChangedEvent.Scope.SERVER) return;
        // Refresh your mod's cached server lookup from event.view().
    }
}
```

### Loaded definition and geometry types

`MoleculeDef` is an immutable record in `io.github.wurtzitane.avogadro.molecule`:

```java
record MoleculeDef(
    ResourceLocation id, MoleculeStructure structure, String formula,
    List<String> items, List<String> itemTags, List<String> fluids,
    List<String> fluidTags, List<String> materials, boolean matchContainers,
    String name, boolean generateItem, MoleculeInspection inspection)

MoleculeDef(ResourceLocation id, MoleculeStructure structure, String formula,
            List<String> items, List<String> itemTags, List<String> fluids,
            List<String> fluidTags, List<String> materials, boolean matchContainers,
            String name, boolean generateItem)
MoleculeDef(ResourceLocation id, MoleculeStructure structure, String formula,
            List<String> items, List<String> itemTags, List<String> fluids,
            List<String> fluidTags, List<String> materials, boolean matchContainers)
```

The shorter constructors default inspection to EMPTY and name/generateItem to null/false. `name()` can be null. Direct definitions still enforce generated-item composition matching. Prefer MoleculeBuilder/MoleculeSpec for registered content so validation and layer merging follow normal loading.

`MoleculeStructure`, `AtomElement`, and `BondType` are in the same molecule package:

```java
String MoleculeStructure.id()
String MoleculeStructure.formula()
List<MoleculeStructure.Atom> MoleculeStructure.atoms()
List<MoleculeStructure.Bond> MoleculeStructure.bonds()
org.joml.Vector3f MoleculeStructure.center()
float MoleculeStructure.centerX()
float MoleculeStructure.centerY()
float MoleculeStructure.centerZ()
float MoleculeStructure.radius()
static MoleculeStructure.Builder MoleculeStructure.builder(String id, String formula)

record MoleculeStructure.Atom(AtomElement element, org.joml.Vector3f position)
float MoleculeStructure.Atom.x()
float MoleculeStructure.Atom.y()
float MoleculeStructure.Atom.z()
record MoleculeStructure.Bond(int from, int to, BondType type)

int MoleculeStructure.Builder.addAtom(AtomElement element, float x, float y, float z)
MoleculeStructure.Builder MoleculeStructure.Builder.bond(int from, int to)
MoleculeStructure.Builder MoleculeStructure.Builder.bond(int from, int to, BondType type)
MoleculeStructure.Builder MoleculeStructure.Builder.autoBond()
MoleculeStructure MoleculeStructure.Builder.build()

record AtomElement(String symbol, int color, float radius)
static AtomElement AtomElement.bySymbol(String symbol)
enum BondType { SINGLE, DOUBLE, TRIPLE, QUADRUPLE }
int BondType.order()
float BondType.radius()
```

Use MoleculeDef.formula() for a loaded molecule's display formula; its geometry's formula() can be empty. Position and center accessors return vector copies. Structure builders require at least one atom; coordinates must be finite, bond endpoints valid, and self-bonds are rejected. Low-level `autoBond()` adds single bonds according to distance and rendered radii. It does not solve chemical valence and should not replace explicit bonds when chemistry matters. This low-level builder creates render geometry; use the separate StructureBuilder API for procedural fragment composition and MoleculeBuilder for registration.

AtomElement includes these public static constants: HYDROGEN, CARBON, NITROGEN, OXYGEN, FLUORINE, CHLORINE, BROMINE, IODINE, SULFUR, SELENIUM, PHOSPHORUS, BORON, SILICON, SODIUM, POTASSIUM, LITHIUM, CALCIUM, MAGNESIUM, ALUMINIUM, IRON, COPPER, ZINC, TIN, LEAD, SILVER, GOLD, MANGANESE, CHROMIUM, NICKEL, COBALT, TITANIUM, TUNGSTEN, OSMIUM, URANIUM, PLUTONIUM, POLONIUM, ARGON, KRYPTON, HELIUM, NEON, XENON. `bySymbol` returns a default pink visual element for symbols without predefined styling; a custom AtomElement controls color/radius in direct geometry, while molecule JSON does not set per-atom render colors or radii. Global element colors use the separate palette file documented below.

### Formula formatting and mass

```java
static String ChemicalFormula.display(String formula)

static final double MoleculeWeight.AVOGADRO_CONSTANT = 6.02214076e23
static MoleculeWeight.Result MoleculeWeight.calculate(MoleculeDef molecule)
static MoleculeWeight.Result MoleculeWeight.calculate(MoleculeStructure structure)
static MoleculeWeight.Result MoleculeWeight.calculate(String formula)
static Map<String, Long> MoleculeWeight.composition(MoleculeStructure structure)
static Map<String, Long> MoleculeWeight.composition(String formula)
record MoleculeWeight.Result(double molarMass, double gramsPerEntity)
String MoleculeWeight.Result.molarMassText()
String MoleculeWeight.Result.entityMassText()
```

Mass units are g/mol and g/entity. Calculation from a definition uses its actual atom list, rather than its display formula. Atomic inspection isotope settings do not change these composition-based weights. Unsupported weight symbols throw IllegalArgumentException.

Formula mass parsing accepts ASCII or subscript counts, nested parentheses/brackets, and dot-separated hydrates such as `CuSO4·5H2O`. It requires 1..4096 characters, at most 32 nested groups, positive counts, and at most 1,000,000,000 atoms per element. It does not parse charges or isotope labels.

`ChemicalFormula.display` is presentation only. It makes counts subscript, charge/isotope annotations superscript, recognizes explicit `^`/`^{...}` superscripts, and preserves Minecraft section-sign formatting. Null and empty strings pass through. Keep plain formulas for storage/parsing and format them only for display.

```java
String label = ChemicalFormula.display("SO4^{2-}");
MoleculeWeight.Result hydrate = AvogadroApi.weight("CuSO4·5H2O");
Map<String, Long> composition = MoleculeWeight.composition("Ca(OH)2");
```

### Client entry point: AvogadroClientApi

```java
package io.github.wurtzitane.avogadro.api.client;

static MoleculeView AvogadroClientApi.view()
static void AvogadroClientApi.render(MoleculeStructure structure, int x, int y, int size, float angle)
static float AvogadroClientApi.rotationAngle()
static void AvogadroClientApi.withFluidTooltip(FluidStack fluid, Runnable draw)
```

Use these methods only in client setup or client GUI/render code. The view contains the client's visible molecule data. `render` draws a structure with the existing renderer; `rotationAngle` supplies its normal angle. `withFluidTooltip` supplies fluid context while the callback draws a tooltip, so Avogadro can match that fluid. Keep client classes out of common initialization and dedicated-server code.

```java
// Inside an existing client GUI's draw method, with an active render context:
MoleculeDef visible = AvogadroClientApi.view().get("example:water");
if (visible != null) {
    AvogadroClientApi.render(visible.structure(), 20, 20, 96,
            AvogadroClientApi.rotationAngle());
}
```

There is no public `AvogadroApi` method to open arbitrary molecule screens, edit player settings, or start media exports. Use the documented UI and `/avogadro export` commands for those actions rather than inventing API calls. `/avogadro reload` is the operator command for authoritative file reloads; ordinary owner updates use MoleculeRegistration directly.

## GroovyScript API

GroovyScript is optional. Place registrations in its reloadable `postInit` loader, for example `groovy/postInit/molecules.groovy`. The entry point is `mods.avogadro.molecules`, an instance of `io.github.wurtzitane.avogadro.compat.groovy.GroovyMolecules`.

```java
MoleculeBuilder builder(String id)
MoleculeBuilder copy(String id, String source)
StructureBuilder structureBuilder()
StructureFragment structure(groovy.lang.Closure<?> definition)
MoleculeWeight.Result weight(String formula)
MoleculeWeight.Result weight(MoleculeDef molecule)
ItemStack item(String id)
MoleculeSpec register(MoleculeSpec definition)
MoleculeSpec register(MoleculeBuilder definition)
MoleculeSpec register(String id, Map<?, ?> fields)
MoleculeSpec register(String id, groovy.lang.Closure<?> definition)
void remove(String id)
void clear()
void itemTag(String id, Collection<?> values)
void itemTag(String id, boolean replace, Collection<?> values)
void fluidTag(String id, Collection<?> values)
void fluidTag(String id, boolean replace, Collection<?> values)
void removeItemTag(String id)
void removeFluidTag(String id)
MoleculeView view()
MoleculeDef get(String id)
List<MoleculeDef> all()
void batch(groovy.lang.Closure<?> changes)
```

Builder/closure syntax uses the Java builder methods. Map syntax uses JSON field names. Closures delegate to their builder or registry with DELEGATE_FIRST and can instead accept the delegate as a parameter. Tag collections contain registry IDs or `#tag` references. `item(id)` obtains the generated molecule item; missing/disabled definitions throw IllegalArgumentException. Java's equivalent is `AvogadroApi.moleculeItem(definition)`.

Each script reload starts with an empty pending script contribution. On success it replaces the previous script contribution as one commit. A failed staged registry operation (register, remove, clear, tags, or batch) rejects the script contribution and retains the previous installed one. Standalone builder, structure, item, or weight helper failures do not themselves mark the run rejected. `batch` restores pending edits if its closure throws; the failed run is still rejected. `view()` previews the pending definitions merged with current resources and `get/all` query that preview. `remove/clear` affect this script contribution and can reveal lower JSON/Java layers.

Do not call the blacklisted `onReload` and `afterScriptLoad` hooks yourself. Do not bypass this registry with manual Java owner handles in reloadable scripts; that would lose the script lifecycle behavior.

### Complete Groovy postInit example

```groovy
// File: groovy/postInit/molecules.groovy
def molecules = mods.avogadro.molecules

molecules.register('example:water') {
    name('Water')
    formula('H2O')
    generateItem(true)
    matchContainers(true)
    items('minecraft:water_bucket')
    fluids('minecraft:water')
    atom('O', 0, 0, 0)
    atom('H', 0.96, 0, 0)
    atom('H', -0.24, 0.93, 0)
    bond(0, 1)
    bond(0, 2)
}

// A map uses JSON field names and normal Groovy lists.
molecules.register('example:methane', [
    smiles: 'C',
    formula: 'CH4',
    name: 'Methane',
    generate_item: true
])

// Copy inherits geometry and associations; this entry changes only the name.
molecules.register(molecules.copy('example:water_copy', 'example:water')
        .name('Water copy'))

// Custom tags are Avogadro binding tags, not vanilla 1.12 item tags.
molecules.itemTag('example:water_items', ['minecraft:water_bucket'])

def water = molecules.get('example:water')
def generatedWaterItem = molecules.item('example:water')
def mass = molecules.weight(water)
```

For a fragment closure, call `molecules.structure { ... }` with the StructureBuilder methods and pass its returned StructureFragment to a molecule builder's `.structure(fragment)`. For ordinary hand-authored coordinates, use `atom/bond` directly as above.

## Structure authoring API

Import `io.github.wurtzitane.avogadro.api.StructureBuilder` and `StructureFragment`. Bond types use `io.github.wurtzitane.avogadro.molecule.BondType`. Atom indices start at zero. Coordinates are Cartesian display coordinates. All bonds are undirected. The structure API preserves the connectivity supplied by the author; it never guesses bonds, hydrogens, valence, chemistry, or nuclear populations.

`AvogadroApi.structureBuilder()` returns a new `StructureBuilder`. The builder is mutable; its finished `StructureFragment` is immutable.

```java
// StructureBuilder core methods; additive attachment methods appear in the .32 section.
new StructureBuilder();
int addAtom(String element, double x, double y, double z);
StructureBuilder atom(String element, double x, double y, double z);
StructureBuilder bond(int from, int to);
StructureBuilder bond(int from, int to, String type);
StructureBuilder bond(int from, int to, BondType type);
StructureBuilder cell(double[][] vectors);
StructureBuilder link(int from, int to, int[] offset);
StructureBuilder link(int from, int to, int[] offset, String type);
StructureBuilder link(int from, int to, int[] offset, BondType type);
StructureFragment build();
```

`addAtom` returns the index of the added atom; `atom` returns the builder. `bond` and `link` default to `BondType.SINGLE`. String bond types are trimmed and case insensitive: `SINGLE`, `DOUBLE`, `TRIPLE`, `QUADRUPLE`, `-`, `=`, `#`, and strings `1` through `4`. StructureBuilder has no integer bond-type overload. MoleculeBuilder does have one.

`cell` takes one to three lattice vectors, each containing three coordinates. These vectors must be nonzero and linearly independent. Atom coordinates remain Cartesian. `link(from, to, offset)` connects an atom in the current cell to an atom in the cell shifted by the integer offset. The number of offset entries must match the number of lattice vectors. For example, offset `[1, 0]` means one cell along the first vector and none along the second. A link can connect the same basis atom to its counterpart in another cell.

```java
// StructureFragment: public constants, records, and every public method.
public static final int MAX_ATOMS = 4096;
public static final int MAX_BONDS = 8192;
public record Atom(String element, double x, double y, double z) {}
public record Bond(int from, int to, BondType type) {}
public record CellLink(int from, int to, List<Integer> offset, BondType type) {}
List<Atom> atoms();
List<Bond> bonds();
boolean isUnitCell();
StructureFragment translate(double x, double y, double z);
StructureFragment scale(double scale);
StructureFragment scale(double x, double y, double z);
StructureFragment rotate(double axisX, double axisY, double axisZ, double degrees);
StructureFragment affine(double[][] matrix);
StructureFragment append(StructureFragment other);
StructureFragment instances(List<double[][]> transforms);
StructureFragment repeat(int[] counts, boolean[] wrap);
StructureFragment wrapCylinder(double[] origin, double[] circumferenceAxis,
    double[] axialAxis, double circumference);
com.google.gson.JsonObject toJson();
io.github.wurtzitane.avogadro.molecule.MoleculeStructure toStructure(String id);
```

Records expose public constructors and their named accessors. Build fragments with StructureBuilder or the public StructureFragment constructors; additive constructors and attachment/edit APIs appear in the .32 section.

| Operation | Behavior |
| --- | --- |
| `translate` | Moves atoms by the supplied offset. Lattice vectors keep their direction and length. |
| `scale` | Multiplies coordinate axes by a uniform scalar or separate factors; transforms lattice vectors too. |
| `rotate` | Rotates around a nonzero axis through the origin, using degrees. |
| `affine` | Applies exactly three rows of four finite coefficients to column vectors `[x,y,z,1]`. Lattice vectors receive the linear part, without translation. |
| `append` | Adds the other fragment's atoms and bonds, adjusting their indices. It creates no bonds between fragments. |
| `instances` | Produces one independent copy per affine matrix. Include the identity matrix to retain a copy at the original position. It creates no bonds between copies. |
| `repeat` | Expands a unit cell, copying internal bonds and resolving the relative links. |
| `wrapCylinder` | Changes finished coordinates into a cylindrical arrangement while preserving existing bonds. It creates no seam bonds. |
| `toJson` | Returns a JSON object with finished `atoms` and `bonds`, suitable for a molecule definition; no metadata or `structure` wrapper. |
| `toStructure` | Compiles finished geometry into a renderable MoleculeStructure with the supplied ID. |

Unit cells can be translated, scaled, rotated, or transformed before repetition. Call `repeat` before `append`, `instances`, `wrapCylinder`, `toJson`, or `toStructure`. `repeat` returns a finished fragment, so a second repeat cannot apply to that result.

Repeat counts and wrap flags must have exactly one entry per lattice vector. Counts are positive integers. An open axis (`false`) omits links that leave the repeated region. A wrapped axis (`true`) maps destination cells modulo that axis's count. It connects the opposing boundaries without changing atom coordinates. Cell order varies the last axis fastest; atom order within each cell follows the original basis order. Small wrapped repeats can create invalid self-bonds or duplicate undirected bonds. For a one-site basis with a positive nearest-cell link, use at least three cells along a wrapped axis.

For `wrapCylinder`, supply three-coordinate arrays for origin, circumference direction, and axial direction. Directions are normalized and must be nonzero and perpendicular. The scalar circumference must be positive and finite; radius is circumference divided by `2*pi`. Displacement along the normal (circumference direction crossed with axial direction) changes radial distance and must leave it positive. Create any seam connections using explicit links and wrapped repetition before rolling the coordinates.

Every fragment and intermediate operation must contain 1–4096 atoms and at most 8192 bonds. Bonds must use existing indices, connect different atoms, and occur only once per undirected pair. Relative cell links also have an 8192-entry limit and cannot duplicate a link or its reversed equivalent. Element symbols match `[A-Z][a-z]?`; unknown symbols use the fallback render style. Coordinates must be finite and fit the renderer's float range, including the resulting center and radius. A transform that collapses cell vectors into dependent vectors is rejected.

### Java example: a finite chain

This creates six displayed carbon atoms with five bonds; it does not add hydrogens or claim a complete chemical species.

```java
import io.github.wurtzitane.avogadro.api.AvogadroApi;
import io.github.wurtzitane.avogadro.api.MoleculeSpec;
import io.github.wurtzitane.avogadro.api.StructureFragment;

StructureFragment chain = AvogadroApi.structureBuilder()
    .atom("C", 0, 0, 0)
    .cell(new double[][] {{1.42, 0, 0}})
    .link(0, 0, new int[] {1})
    .build()
    .repeat(new int[] {6}, new boolean[] {false});

MoleculeSpec definition = AvogadroApi.builder("mypack:carbon_chain")
    .name("Carbon chain")
    .structure(chain)
    .generateItem(true)
    .build();
AvogadroApi.owner("mypack").register(definition);
```

`MoleculeBuilder.structure(StructureFragment)` writes flattened `atoms` and `bonds`. It rejects an existing `copy`, `smiles`, `structure`, `atoms`, or `bonds` field. `structure(StructureBuilder)` builds immediately, so repeat a unit cell into a fragment before passing it to MoleculeBuilder.

### GroovyScript example: a finite chain

Place registrations in the reloadable `postInit` loader, such as `groovy/postInit/molecules.groovy`.

```groovy
def molecules = mods.avogadro.molecules
def chain = molecules.structure {
    atom('C', 0, 0, 0)
    cell([[1.42d, 0d, 0d]] as double[][])
    link(0, 0, [1] as int[])
}.repeat([6] as int[], [false] as boolean[])

molecules.register('mypack:carbon_chain') {
    name('Carbon chain')
    structure(chain)
    generateItem(true)
}
```

### JSON structure recipes

`io.github.wurtzitane.avogadro.molecule.StructureRecipe.parse(JsonObject recipe)` parses the object inside a molecule's `structure` field and returns a finished StructureFragment.

A root `structure` field cannot be combined with root `copy`, `smiles`, `atoms`, or `bonds`. Its allowed fields are `atoms`, `bonds`, `cell`, and `operations`. Unknown fields are rejected. `atoms` is required and contains 1–4096 objects, each with exactly `e`, `x`, `y`, `z`. Atom coordinates are required finite numbers. Inside a structure recipe, omitted `bonds` means no bonds. This differs from root explicit `atoms`, where omitting `bonds` enables automatic bonding; root `"bonds": []` disables it.

Recipe `bonds` entries are `[from,to]` or `[from,to,type]`. Type defaults to SINGLE and accepts the string aliases above or integer numbers 1–4. `cell` accepts only required `vectors` and optional `links`. Vectors are one to three arrays of three numbers. Links are `[from,to,[offsets]]` or `[from,to,[offsets],type]`; offset length must match the cell dimension. A recipe with a cell must execute a repeat operation before completion.

This complete molecule definition makes the same finite chain:

```json
{
  "name": "Carbon chain",
  "generate_item": true,
  "structure": {
    "atoms": [{"e": "C", "x": 0, "y": 0, "z": 0}],
    "bonds": [],
    "cell": {
      "vectors": [[1.42, 0, 0]],
      "links": [[0, 0, [1], "SINGLE"]]
    },
    "operations": [
      {"op": "repeat", "counts": [6], "wrap": [false]}
    ]
  }
}
```

Operations run in array order. Each structure accepts at most 64 operations. Appended child structures may nest up to depth 16. The following reference lists every supported operation with concrete valid field shapes; repeat requires a unit cell, while instances, append, and wrapCylinder require a finished fragment. This is a menu of operation examples, not one sequence to paste into a recipe:

```json
[
  {"op": "translate", "by": [1, 2, 3]},
  {"op": "scale", "by": 2},
  {"op": "scale", "by": [1, 2, 1]},
  {"op": "rotate", "axis": [0, 0, 1], "degrees": 90},
  {"op": "affine", "matrix": [[1, 0, 0, 2], [0, 1, 0, 0], [0, 0, 1, 0]]},
  {"op": "repeat", "counts": [6, 4], "wrap": [true, false]},
  {"op": "instances", "transforms": [
    [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0]],
    [[1, 0, 0, 5], [0, 1, 0, 0], [0, 0, 1, 0]]
  ]},
  {"op": "append", "structure": {
    "atoms": [{"e": "He", "x": 10, "y": 0, "z": 0}], "bonds": []
  }},
  {"op": "wrapCylinder", "origin": [0, 0, 0],
    "circumference": [1, 0, 0], "axis": [0, 1, 0], "length": 8.52}
]
```

For repeat, `wrap` is optional and defaults to all false. In wrapCylinder, `origin` is optional and defaults to `[0,0,0]`. Its `circumference` field is a direction vector, while `length` is the scalar circumference length. Every other shown field is required. Operation names are case sensitive. Affine and instance matrices contain exactly three rows of four finite numbers. `instances.transforms` must be nonempty and fit the total atom/bond limits. Nested append structures follow the same schema.

### Example: roll a repeated mesh

This is a generic square mesh rolled into a cylinder. It is a geometry example, not a carbon nanotube model, because nanotubes need a different basis and connectivity.

```json
{
  "name": "Rolled mesh",
  "structure": {
    "atoms": [{"e": "C", "x": 0, "y": 0, "z": 0}],
    "bonds": [],
    "cell": {
      "vectors": [[1.42, 0, 0], [0, 1.42, 0]],
      "links": [[0, 0, [1, 0]], [0, 0, [0, 1]]]
    },
    "operations": [
      {"op": "repeat", "counts": [6, 4], "wrap": [true, false]},
      {"op": "wrapCylinder", "circumference": [1, 0, 0],
        "axis": [0, 1, 0], "length": 8.52}
    ]
  }
}
```

## Atomic and inspection data

Atomic examination is optional and host-authored. The mod does not guess isotopes, electron populations, or provenance. Shell orbits are schematic drawings. AtomicConfiguration allows synthetic nuclei within its resource limits.

```java
// io.github.wurtzitane.avogadro.api.AtomicConfiguration
public record AtomicConfiguration(int atomicNumber, int neutrons,
    int charge, java.util.List<Integer> electronShells) {}
int electrons();                  // atomicNumber - charge
int massNumber();                 // atomicNumber + neutrons
com.google.gson.JsonObject toJson();
static AtomicConfiguration fromJson(com.google.gson.JsonObject value);

// io.github.wurtzitane.avogadro.api.MoleculeInspection
public record MoleculeInspection(
    java.util.Map<String, AtomicConfiguration> elements,
    java.util.Map<Integer, AtomicConfiguration> atoms,
    java.util.List<net.minecraft.util.ResourceLocation> components,
    java.util.Map<String, String> metadata) {}
public static final MoleculeInspection EMPTY;
AtomicConfiguration atom(
    io.github.wurtzitane.avogadro.molecule.MoleculeStructure structure, int index);

// MoleculeBuilder methods, also available through its Groovy closure delegate.
MoleculeBuilder element(String symbol, int atomicNumber, int neutrons, int... shells);
MoleculeBuilder atomConfiguration(int index, int atomicNumber, int neutrons,
    int charge, int... shells);
MoleculeBuilder component(String id);
MoleculeBuilder inspectionMetadata(String key, String value);
```

Records expose their public constructors and named accessors. Collection inputs are defensively copied. `element` defines a neutral configuration for all atoms with that symbol; use `atomConfiguration` for per-index isotope or charge overrides. `MoleculeInspection.atom` returns an index override, otherwise the configuration for that atom's symbol, otherwise null. Access inspection data through `MoleculeDef.inspection()`.

Atomic JSON requires integer `atomic_number`, `neutrons`, and an integer array `electron_shells`; `charge` defaults to zero. Atomic number must be 1–1000, neutron count 0–10000, and charge -1000 through atomic number. At most 32 shells are allowed. Shell populations must be nonnegative; shell n (counting from 1) holds at most `2*n*n` electrons. Their total must equal atomic number minus charge.

The following complete definition supplies neutral carbon-12 by symbol and carbon-13 for atom index zero:

```json
{
  "name": "Carbon pair",
  "atoms": [
    {"e": "C", "x": 0, "y": 0, "z": 0},
    {"e": "C", "x": 1.42, "y": 0, "z": 0}
  ],
  "bonds": [[0, 1, "SINGLE"]],
  "elements": {
    "C": {"atomic_number": 6, "neutrons": 6, "electron_shells": [2, 4]}
  },
  "atom_configurations": {
    "0": {"atomic_number": 6, "neutrons": 7, "charge": 0, "electron_shells": [2, 4]}
  },
  "inspection": {"source": "Host-authored geometry"}
}
```

`components` is a root array of molecule resource IDs used by the examiner to navigate constituent definitions, for example `"components": ["mypack:water", "mypack:salt"]` when both definitions exist. It does not compose geometry. MIXTURE definitions open with their constituent phase list visible. Selecting a crystalline phase opens its full referenced unit cell in the crystal examiner; overview fragments never replace that source geometry. Back returns to the parent mixture. The crystal examiner's Atoms action inspects the full source cell and returns to its crystal view. No phase proportions or combined molecular formula are inferred. `inspection` is a root object of string-valued metadata, without a fixed chemistry schema.

For root explicit `atoms`, an atom object can include `"nuclear": {"atomic_number": 6, "neutrons": 7, "electron_shells": [2,4]}`. That inline configuration takes precedence over `atom_configurations`, which takes precedence over `elements`. Inline nuclear is not allowed inside `structure.atoms`; use root `elements` or root `atom_configurations` for structure recipes. Per-index keys refer to the final expanded structure's zero-based atom order. Copy definitions inherit and merge atomic maps and metadata; component lists are combined without duplicates.

Java configuration example:

```java
var carbon13 = new AtomicConfiguration(6, 7, 0, java.util.List.of(2, 4));
var definition = AvogadroApi.builder("mypack:carbon_pair")
    .atom("C", 0, 0, 0)
    .atom("C", 1.42, 0, 0)
    .bond(0, 1)
    .element("C", 6, 6, 2, 4)
    .atomConfiguration(0, 6, 7, 0, 2, 4)
    .inspectionMetadata("source", "Host-authored geometry")
    .build();
```

Elemental samples can be classified with `MoleculeBuilder.classification(MoleculeClassification.ELEMENT)`
or `MOLECULE`; `AUTO` groups structures containing one element symbol as elemental, even when they
contain multiple atoms. JSON stores this as `"inspection":{"classification":"element"}`.
Classification affects creative tabs and their matching HEI collapsible groups. The host chooses the actual sample geometry.
Dynamic tooltip predicates receive the current stack and must remain fast and read-only; they do not
remove the structure or any HEI associations. Number sorting reads configured atomic data, not a
built-in chemistry catalog. Equal/missing proton counts use stable molecule IDs as a tie-breaker.

## Client settings, controls, and resource styling

Configuration is Forge 1.12 `config/avogadro.cfg`, not a modern TOML file. Use
the mod's configuration screen or edit the file while the game is closed.
Settings are grouped by category:

| Category | Property | Default and supported values |
| --- | --- | --- |
| tooltips | enableTooltips | true |
| tooltips | tooltipSize | 64; 16–128 GUI pixels |
| appearance | spinSeconds | 6.0; 0.5–60 seconds per rotation |
| appearance | rotationDirection | FORWARD; FORWARD or REVERSE |
| appearance | cubicAtoms | false |
| appearance | renderingMode | LIVE; LIVE or PRERENDERED |
| appearance | textScale | 1.0; 0.5–3 |
| appearance | creativeSort | NAME; NAME, FORMULA, ATOMIC_NUMBER, ID, or REGISTRATION |
| appearance | elementSort | ATOMIC_NUMBER; same modes as creativeSort; configured proton count, missing data last |
| appearance | splitCreativeTabs | true; separate Elements/Molecules tabs; restart to change |
| recipebrowser | jeiCategory | true; controls the HEI Molecules category |
| worldhud | enabled | true |
| worldhud | mode | HOLD; HOLD or TOGGLE |
| worldhud | provider | AUTO; AUTO, PROBE, or WAILA |

Tooltips, the HEI page, and world HUDs are independently enabled. PRERENDERED
uses a cached rotation with a fixed camera; large previews may fall back to
live geometry. Molecule-item rendering and media exports use live geometry.

CPU model and examiner preparation uses bounded workers; opened viewers take
priority over queued thumbnails. Immutable inspection data is reused by definition
identity. Prepared molecule-buffer payloads upload in chunks of at most 256 KiB, at most 1 MiB per frame,
and share a time allowance with at most one animation-cache capture per frame.
The allowance follows the target frame period, up to 2 ms. An indivisible driver
operation can exceed its estimate; the remaining work is deferred. Exports wait
for complete geometry rather than capturing a partially prepared model.

HEI models: left-drag rotates, right-drag pans, wheel zooms, middle-click resets,
Space pauses/resumes, and Shift+N opens the examiner while the preview is
hovered. Keys can be rebound under Controls → Avogadro. The examiner supports
atom selection, component navigation, camera reset, and Back. Escape closes
it and returns to the world. The examiner has its own Pause/Resume button and uses the same rebindable pause key; it freezes electron motion and automatic rotation together. Back is shown only for an atom focus or component history. Components opens a list of valid supplied child definitions and is hidden when there are none. Atomic details require supplied inspection data;
the library does not guess isotopes or exact electron configurations.

The World HUD Molecule key starts unbound. Assign it and install a supported
HWYLA/Waila or The One Probe provider. AUTO selects one provider; it does not
draw duplicate models in every installed HUD. Native HUD text is retained.

HEI indexes associated item subtypes and fluids under both recipe (R) and usage
(U) lookups. The category refreshes with molecule changes; do not assume its
entries are permanently fixed at initial HEI startup. Generated molecule items are
collapsed into the same categories as their creative tabs. Element, crystal and
polymer split preferences also control these groups; a disabled split merges its
samples into Molecules. Group names use the creative-tab translations. Groups
refresh after molecule reloads and contain only generated Avogadro samples,
never the associated items or fluids from other mods. HEI controls their
expansion and visibility through its native collapsible-group settings.

Override element colors in `config/avogadro/element-colors.json`, for example:

```json
{"C":"#444444","O":"#E04040","H":"#EEEEEE"}
```

Resource-pack path: `assets/avogadro/element-colors.json`. Local entries override
resource packs; partial maps retain other colors. Use known element symbols
and `#RRGGBB` values. Local file limit is 32 KiB. F3+T reloads these colors;
invalid reloads keep the previous palette. Colors do not change atom sizes.

The HEI category uses `assets/avogadro/textures/gui/molecules.png` and text
colors in `assets/avogadro/gui/molecules.json`:

```json
{"primaryTextColor":"#3F3A30","secondaryTextColor":"#6C6557"}
```

Use ordinary Minecraft resource-pack font overrides for fonts. Do not add
presentation keys to molecule JSON to change global palette or UI styling.

## Media exports and command reference

These commands run on the client. A molecule ID can omit its namespace only
when its path is unique. Tab completion lists loaded IDs. `all` exports one
gallery, sorted by full molecule ID.

```text
/avogadro export example:water --format gif --output water.gif
/avogadro export all --columns 4 --name true --formula true
/avogadro export example:water --format mp4 --speed 3 --direction reverse
/avogadro export item held --scale 2
/avogadro export item minecraft:water_bucket --meta 0 --name true
/avogadro export help
/avogadro export status
/avogadro export cancel
```

An item card requires a loaded molecule matching the selected item. `held`
preserves stack metadata, custom name, NBT, and capabilities. `--meta` applies
only to a registry item ID, not the held stack.

| Flag | Values and default |
| --- | --- |
| --format | gif (default) or mp4 |
| --output | Filename within `<game>/avogadro-exports`; existing files are never overwritten |
| --width, --height | 64–4096 pixels per side; single molecule defaults to 600×600 |
| --fps | MP4: positive integer; GIF: 1–60 input, capped at 50; default 20. All exports retain the 7,200-frame limit. |
| --speed | 0.1–600 seconds per full turn; default Appearance spinSeconds; alias --seconds-per-rotation |
| --rotations | 1–100 full turns; default 1 |
| --name, --formula | true or false; molecule defaults both true |
| --background | quoted #RRGGBB or none; default #DED8C4 |
| --direction | forward or reverse; default Appearance direction |
| --appearance | sphere or cube; default Appearance cubicAtoms |
| --columns | 1–16 for galleries; default 4, limited to molecule count |
| --text-scale | 0.5–3; default Appearance textScale for molecules, 1 for item cards |
| --encoder | FFmpeg executable path, for MP4 only |
| --scale | Item cards only: 1–8; default 2 |
| --meta | Registry item cards only: 0–32767 |

Item cards default to 192×192 pixels (height can grow for long labels), the
native item name, no formula, and background #100010. Canvas dimensions must
be multiples of --scale; each GUI pixel occupies scale×scale output pixels.
They require a solid background and do not accept --columns.

GIF encoding is included. MP4 requires external FFmpeg with libx264, selected
by --encoder, AVOGADRO_FFMPEG, or PATH. MP4 needs even width/height and a solid
background. GIF transparency is binary, with up to 256 colors per frame.
Exports do not change saved appearance settings. Limits: 8,388,608 pixels,
7,200 frames, 600 seconds; a turn needs at least 2 frames. Gallery limit:
256 molecules, at least 96×96 pixels per cell. Only one export runs at a time.

## Programmatic examiner and export access

The supported public client facade is `api.client.AvogadroClientApi`, described
above. The following public implementation entry points also exist in .20.
They are outside the facade and may change between versions. Use them only in
client code, on the client thread with a running client/world; never import
them in a common server initializer or ordinary Groovy postInit script.

```java
// io.github.wurtzitane.avogadro.client.MoleculeExaminer
static void open(MoleculeDef selected)

// io.github.wurtzitane.avogadro.client.export.ExportOptions
static ExportOptions parse(String[] arguments, int moleculeCount)

// io.github.wurtzitane.avogadro.client.export.ItemExportSelection
static ItemExportSelection select(String target, Integer metadata, ItemStack held)

// io.github.wurtzitane.avogadro.client.export.ItemExportOptions
static ItemExportOptions parse(ItemExportSelection selection, String[] arguments)

// io.github.wurtzitane.avogadro.client.export.MoleculeMediaExport
static void start(ExportOptions options, List<MoleculeDef> definitions) throws IOException
static void startItem(ItemExportOptions options) throws IOException
static boolean isRunning()
static Path lastOutput()
static String lastFailure()
static String status()
static boolean cancel()
```

ExportOptions.parse takes only alternating option/value strings, not the
`/avogadro export` prefix or molecule ID. Prefer parse over manually constructing
settings, so the command's bounds and validation apply. The moleculeCount must
equal the number of selected definitions. lastOutput and lastFailure may be
null. Capture and job progress run through the mod's registered client ticks;
do not block the client while waiting for completion.

Example inside a client-only button handler, declaring/catching IOException:

```java
import io.github.wurtzitane.avogadro.api.client.AvogadroClientApi;
import io.github.wurtzitane.avogadro.client.MoleculeExaminer;
import io.github.wurtzitane.avogadro.client.export.ExportOptions;
import io.github.wurtzitane.avogadro.client.export.MoleculeMediaExport;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import java.util.List;

MoleculeDef water = AvogadroClientApi.view().get("example:water");
if (water != null) {
    MoleculeExaminer.open(water);
    ExportOptions options = ExportOptions.parse(
            new String[]{"--format", "gif", "--fps", "20"}, 1);
    MoleculeMediaExport.start(options, List.of(water));
}
```

## Choosing an approach

- A few molecule definitions for a pack: use instance/world JSON.
- A personal visual override: use client asset/local JSON and F3+T.
- Reusable mod integration: use AvogadroApi owners, builders, batches, and views.
- Reloadable pack scripting: use mods.avogadro.molecules in Groovy postInit.
- Repeated cells, fragments, or shaped arrays: use StructureBuilder/Fragment or
  the equivalent JSON structure operations. Connectivity must be explicit.
- Custom widgets: use the client render facade from a client drawing callback.
- Export or examine: use the UI/commands, or the version-specific client entry
  points above from a client-only handler.

When generating a new result, include the complete imports or script location,
required dependencies, all referenced definitions, and how the user reloads or
registers it. Label generic structural meshes honestly: a rolled square grid is
not a chemically correct carbon nanotube. If a requested feature has no public
entry point, explain which implementation change would be needed instead of
inventing an API.

## Detailed atom cards and 3D export

Click an atom in the examiner to open its reference card. Selection uses the pose
at mouse press, with enlarged hit targets. Tab and Shift+Tab cycle through every
atom even when atoms overlap. Space (or the rebound pause key) pauses animation.

`/avogadro export 3d namespace:molecule --atom 1 --details true --format gif`
exports a rotating atom with its card. Atom numbers start at 1. Use
`--details false --background none` for a transparent atom-only GIF. The default
canvas is 1000 x 640 with details or 640 x 640 without. Existing media flags for
size, frame rate, rotation speed, output filename, direction and MP4 encoding
apply. Files are saved in the instance's avogadro-exports folder.
Atomic rendering requires host-supplied elements or atom_configurations; nuclear
and electron counts are never guessed from reference atomic weights.

All 118 element cards include category colors, atomic number, reference mass,
neutral electron configuration and name. The blue icons show higher-valence oxide
acid/base character, crystal structure and physical state. A double state line
means synthetically prepared. Missing reference icons are labeled not specified.
Crystal icons describe the reference elemental form, including cooled solids
for gases; they do not describe the molecule's geometry. Reference fields remain
neutral-element facts when inspecting ions. The particle count follows the host.

Basic element facts come from [PubChem's periodic table](https://pubchem.ncbi.nlm.nih.gov/rest/pug/periodictable/JSON).
Icon facts were transcribed from the saved [Britannica element cards](https://www.britannica.com/science/periodic-table).
Cards and icons are drawn by the mod; source images are not bundled. The ten
category colors use the requested palette. B, Si, Ge, As, Sb and Te use the
metalloid category; Sc and Y share rare-earth coloring with the lanthanoids.

Client-only additions (package io.github.wurtzitane.avogadro.client):
ElementCard.fact(String symbol, String key): String; keys number, name, mass,
configuration, state, category, color, crystal, oxide, reference_state. Missing
keys return an empty string. color(String symbol): int (ARGB).
ElementCard.orbitalLabel(String): String; converts orbital occupancies to superscripts.
ElementCard.draw(MoleculeDef, int zeroBasedAtom, int width, int height): void;
requires the GUI render thread and a current OpenGL context.
AtomicInspectionRenderer(MoleculeDef, ElementPalette.Snapshot) freezes colors;
the existing one-argument constructor follows the live palette.
render(int width, int height, MoleculeCamera, int focusedAtom, long animationNanos,
float automaticYaw): void allows deterministic capture; focus -1 shows all atoms.

Package io.github.wurtzitane.avogadro.client.export:
AtomExportOptions(MoleculeDef definition, int atom, boolean details) uses a
zero-based index and rejects missing host atomic configurations.
MoleculeMediaExport.startAtom(ExportOptions, AtomExportOptions): void throws IOException;
call on the client render thread. Existing status/cancel/output APIs apply.

The examiner's Settings button opens saved atom-view and export preferences.
Cloud, electron paths, nuclei and electrons can be toggled separately. Nucleons
form a packed nucleus with its own soft cloud. The viewer frame limit offers
60, 120, 240 or Unlimited; Unlimited follows the active monitor refresh rate.
`atoms.viewerFps=0` is the default; export FPS remains a separate setting. Export
preferences include details, text size, resolution and frame rate. Rotation speed
uses the existing Appearance setting. Restore atom defaults restores the atom
preferences; it leaves the shared Appearance rotation speed unchanged.
These preferences are also under Mods > Avogadro > Config > Atom viewer and exports.
3D exports default to 1000 x 640 at 24 fps with the same native element
card renderer and font as the examiner. Atom-only defaults to 640 x 640. Explicit command
flags override the saved defaults. --text-scale controls export card lettering.
In-game and exported element-card fonts follow the same resource-pack font.
Particle visibility is captured for each 3D export and does not change mid-job.

Config public additions: ATOMS category (atoms); ATOM_CLOUD, ATOM_ORBITS,
ATOM_NUCLEUS, ATOM_ELECTRONS, ATOM_DETAILS (Value<Boolean>); ATOM_WIDTH,
ATOM_HEIGHT, ATOM_FPS, VIEWER_FPS (Value<Integer>); ATOM_TEXT_SCALE (Value<Double>).
Saved keys in atoms: atomCloud, atomOrbits, atomNucleus, atomElectrons,
atomDetails, atomWidth, atomHeight, atomFps, viewerFps, atomTextScale.

## Crystal views (1.0.0-cleanroom.53)

Crystals have a separate creative tab by default (`appearance.separateCrystalTab`, restart required).
Existing sample IDs and `avogadro:molecule` item identities are unchanged. Mixture examiners show constituent identities without misleading atom-count ratios. Shift+N routes a
crystalline definition to the crystal examiner: cell outlines, 1–5 repeats along each
axis, optional authored local bonds and pan/zoom/rotation. The preview
is capped at 65,536 atoms; neighbours/bonds across cells are never guessed.
The 4 × 4 × 4 and 5 × 5 × 5 choices ask for confirmation before loading.
Conditions, sources and notes remain in authored inspection metadata, but are
hidden in game; no notes control is shown.

Common API `CrystalCell(a,b,c,alpha,beta,gamma)` or the nine-argument constructor with
`originX,originY,originZ`: lengths/Cartesian coordinates are angstroms; angles are degrees.
Origin must use the same frame as the authored atoms. `MoleculeBuilder.crystalCell(cell)`
sets CRYSTAL classification. JSON equivalent: `"crystal":{"a":5,"b":5,"c":5,
"alpha":90,"beta":90,"gamma":90,"origin_x":0,"origin_y":0,"origin_z":0}`.
`formula` is the formula unit; the supplied atoms must be whole multiples of it.
A molecular crystal can retain a molecular formula such as H2O2, rather than reducing
it to HO. Cell geometry remains separate from the formula unit. Definition mass is
formula-unit mass, and crystal item tooltips label its molar mass explicitly.

`MoleculeClassification` now includes CRYSTAL and MIXTURE. MIXTURE describes variable
constituents: no generated molecule item or unique molecular mass is permitted.
Saved sample items still resolve their stable IDs even if generation is now disabled.
`CrystalGeometry.repeat(structure,cell,count,bonds)` returns a bounded finite preview.
`CrystalCell.of(definition)` returns its cell or null; `toJson()/fromJson(JsonObject)`
round-trip the common cell format. Client `CrystalExaminer.open(MoleculeDef)` opens the
new view; its overload `open(MoleculeDef, Runnable onReturn)` runs the callback after closing, enabling parent-phase navigation. Ordinary `MoleculeExaminer.open` routes automatically. Generic Avogadro ships
no GT or other pack-specific chemistry or crystal definitions.

### Element inspector presentation

The element inspector uses a neutral surface with a small square of category
color behind the element symbol. Reference symbols keep their proportions and
use the saved oxide, crystal and state facts. The in-game page and exports share
the same renderer and resource-pack font. Configuration occupancies are measured
and raised as digits rather than relying on a separate Unicode font. Long
configurations wrap between complete orbitals. The compact in-game reference card fits the full column without scrolling.

Export card text responds to --text-scale and the saved atom text preference;
rows grow and wrap so enlarged text remains inside the card. The inspector keeps
its normal readable text size. These presentation corrections do not bump the mod version.

Additional client presentation signatures:
- ElementCard.WIDTH = 188, HEIGHT = 270 (default logical units), PAPER (ARGB).
- ElementCard.draw(MoleculeDef, int atom, int width, int height, float textScale): void.
  Uses uniform scaling and expands its logical content height for larger text.
- ElementCard.contentHeight(String symbol, float textScale): int. Requires an
  initialized client font; use for matching a scaled reference-card layout.
- ElementCard.ConfigurationRun(String text, boolean superscript): record.
- ElementCard.configurationLines(String configuration, int width,
  java.util.function.ToIntFunction<String> measure): List<List<ConfigurationRun>>.
  Keeps an orbital and its occupancy on the same line; occupancies use ASCII
  digits rendered at 0.7 of the baseline size.
- ElementCardIcons.drawOxide(String code, float x, float y, float size, int color): void.
- ElementCardIcons.drawCrystal(String code, float x, float y, float size, int color): void.
- ElementCardIcons.drawState(String code, float x, float y, float size, int color): void.
  These draw into square bounds in the current GUI transform, on the render
  thread with a current OpenGL context. Unknown codes draw no invented symbol.

## Periodic table and isotope reference API

One permanent `avogadro:periodic_table` item opens the code-drawn table with
Shift + right-click and places a hanging wall poster with ordinary right-click.
Crouch + scroll resizes the held item only: width 3–12 blocks, height rounded from
`width * 2 / 3`, default 4 × 3. Size survives placement, breaking and saving.
Right-click or Shift + right-click a placed poster to open the table. The survival recipe is eight
sticks around paper; `periodictable.enableCrafting=false` disables it after a
restart without removing the item. Creative access uses the Elements tab when
split, otherwise the main Avogadro tab.

The GUI fits all 118 atomic numbers and symbols on screen. Colors come from the
same facts as ElementCard. It has no hover details, zoom or pan. La and Ac remain
in the main grid; Ce–Lu and Th–Lr occupy the two detached rows. Selecting a cell
opens that atom already focused. Back, Escape and Close return to the table;
its isotope choices remain in memory for that session.

Common package `io.github.wurtzitane.avogadro.api`:

```java
ElementCatalog.elements(): List<ElementCatalog.Element>
ElementCatalog.bySymbol(String symbol): ElementCatalog.Element
ElementCatalog.byAtomicNumber(int atomicNumber): ElementCatalog.Element
ElementCatalog.fact(String symbol, String key): String
ElementCatalog.color(String symbol): int
ElementCatalog.configuration(String symbol, int massNumber): AtomicConfiguration
```

`Element` is an immutable record with `atomicNumber`, `symbol`, `name`, `mass`
(reference atomic-weight text), `configuration` (neutral orbital text), `category`,
`color` (ARGB), `referenceMassNumber`, `electronShells`, and `isotopes`.
`Isotope` is an immutable record with `massNumber`, `massEstimated`,
`halfLifeEstimated`, `halfLife` and `halfLifeUnit`. Isotope lists are sorted by
mass number. Shell lists and all catalog collections are immutable.

Symbol lookup is case-sensitive; unknown symbols, atomic numbers and isotope
choices throw IllegalArgumentException. `fact` instead returns an empty string
for missing facts, and `color` retains the neutral gray fallback. Existing
ElementCard.fact/color signatures remain available as client wrappers.

The bundled NUBASE2020 snapshot contains 3,339 ground-state records covering all
118 elements. It excludes unobserved entries using the source's discovery-year
field, while preserving estimated-property flags for listed nuclei. Defaults
are explicit CIAAW-based references (including Pb-208); atomic weights are never
rounded into neutron counts. NUBASE2020 is a dated evaluation, not a claim to
include every discovery after publication. Neutral shell populations follow the
reference configurations; Lr uses `[Rn]5f14 7s2 7p1`.

Client package `io.github.wurtzitane.avogadro.client`:

```java
PeriodicTableScreen.open(): void
MoleculeExaminer.openElement(String symbol, Runnable onReturn): void
MoleculeExaminer.openElement(String symbol, int massNumber,
    Runnable onReturn, java.util.function.IntConsumer onIsotopeChanged): void
```

These require the client with an active world. They open the existing examiner
using temporary single-atom definitions outside MoleculeStore; they do not
register molecules or generated samples. The isotope selector is confined to
these reference atoms and never changes host-authored isotope or charge data.
Changing isotope preserves camera position, pause state and neutral electrons.
The selection callback receives its mass number; the return callback restores
the caller's table session. Existing `open(MoleculeDef)` behavior is unchanged.

Additional config API: `Config.PERIODIC_TABLE = "periodictable"` and
`Config.PERIODIC_TABLE_CRAFTING` (`Value<Boolean>`), corresponding to the
restart-required `enableCrafting` key. Client table artwork is generated from
code, cached for items/posters, and invalidated when fonts/resources reload.
Source attribution and data terms are shipped separately from the MIT code
license in `META-INF/avogadro-data-sources.txt`.


## Linear polymer definitions

`MoleculeBuilder.polymer(PolymerDefinition)` supplies a separate geometry source;
`polymer` cannot coexist with root `copy`, `atoms`, `bonds`, `smiles`, `structure`
or `crystal`. Existing definitions without `polymer` keep their previous behavior.
The host authors the repeat fragment, two connection atoms, a displacement between
units and both terminal groups. No monomer conversion, bond inference or termination
chemistry is guessed. The bulk formula is `(repeat-unit-formula)n`; the displayed
finite chain has its own exact formula including both terminal groups.

Public common API in `io.github.wurtzitane.avogadro.api`:

```java
record PolymerDefinition(StructureFragment repeatUnit, int head, int tail,
    PolymerDefinition.Vector translation, BondType linkType,
    PolymerDefinition.Cap headCap, PolymerDefinition.Cap tailCap, int previewUnits)
record PolymerDefinition.Vector(double x, double y, double z)
record PolymerDefinition.Cap(StructureFragment structure, int attachment, BondType bondType)
String PolymerDefinition.repeatUnitFormula()
String PolymerDefinition.formula()
String PolymerDefinition.chainFormula(int units)
JsonObject PolymerDefinition.toJson()
static PolymerDefinition PolymerDefinition.fromJson(JsonObject json)
static PolymerDefinition PolymerDefinition.of(MoleculeDef definition)
static MoleculeStructure PolymerGeometry.chain(PolymerDefinition polymer, int units)
static final int PolymerGeometry.MAX_UNITS = 64
static final int PolymerGeometry.MAX_ATOMS = 4096
MoleculeBuilder MoleculeBuilder.polymer(PolymerDefinition polymer)
```

`BondType` is `io.github.wurtzitane.avogadro.molecule.BondType`. Repeat and cap
fragments must be expanded `StructureFragment` objects. Head/tail are zero-based
indices in the repeat fragment; each cap attachment is an index in that cap.
Cap coordinates share the first/last repeat unit's coordinate frame. Translation
uses angstroms. Each neighbouring pair receives one authored tail-to-head bond;
cap bond types and local fragment connectivity are preserved. Construction is
bounded to 1–64 units, 4096 atoms and 8192 bonds. Cache a selected chain instead
of reconstructing geometry in a render callback.

The JSON `polymer` object contains exactly `repeat_unit`, `head`, `tail`,
`translation`, `link_type`, `head_cap`, `tail_cap`, `preview_units`. `repeat_unit`
uses the existing structure-recipe schema; `translation` is three finite numbers.
Each cap contains exactly `structure`, `attachment`, `bond_type`. Bond types are
uppercase enum names. Unknown fields, nonintegral indices, zero displacement,
invalid connections and previews exceeding the limits are rejected by the normal
`MoleculeBuilder.build()` registration path.

`MoleculeClassification.POLYMER` routes the definition to the polymer examiner.
Its ordinary structure is the authored finite preview, while its formula describes
the variable chain. Generated samples keep their existing molecule ID. Their mass
label is the repeat-unit molar mass. `MoleculeWeight.calculate(MoleculeDef)` rejects
a polymer's unspecified total mass; calculate `repeatUnitFormula()` or a finite
`PolymerGeometry.chain(...)` explicitly when that quantity is needed. The library
ships no polymer chemistry: host mods supply the units, caps and source metadata.

### Optional reference samples and generic creative organization

The reference catalog remains available independently of generated sample items.
Restart-required config category `elements` contains `autoLoadElements` and
`autoLoadIsotopes`, both false by default. Public fields are
`Config.ELEMENTS`, `Config.AUTO_LOAD_ELEMENTS` and `Config.AUTO_LOAD_ISOTOPES`.
Enabling only elements generates 118 representative neutral samples; isotopes
alone generate 3,339 samples; enabling both deliberately generates 3,457 IDs.
Representative IDs are `avogadro:elements/c`; isotope IDs are
`avogadro:elements/c_13`. These use the shared molecule sample item and exact
AtomicConfiguration data, not new per-element registry entries.

`MoleculeResources.readCommonDefaults(): MoleculeResources.Data` combines the
optional generated references before mod-provided common resources. This is
now the lowest-priority common source at initial load and reload; installed mod
resources and all existing later layers can override the same IDs. Connected
clients use the server snapshot rather than reinjecting locally enabled samples.
The library includes no GT:wurtzitane chemistry catalog.

Generated user samples are grouped from generic definition metadata, independent
of their source: ELEMENT, CRYSTAL and POLYMER go to enabled specialized tabs;
other samples go to Molecules. Disabling a specialized tab folds its samples into
Molecules, without duplicate entries. `generate_item` remains explicit for user
content. Changes to definitions regroup the existing stable IDs after reload.
Known bare element symbols use the catalog atomic number when sorting by proton
count; an authored AtomicConfiguration takes priority. Unknown custom symbols
retain their prior fallback behavior.

New creative API: `MoleculeItems.POLYMERS_TAB` (CreativeTabs, null when disabled),
`Config.SPLIT_POLYMER_TAB` (Value<Boolean>, default true), corresponding to the
restart-required `appearance.separatePolymerTab` preference.

Avogadro's ModularUI screens hide the hotbar and the local first-person table
item while open; item stacks and other players are unchanged. The periodic table
uses native parent navigation: Back, Escape and Close on a reference atom return
directly to the same table session without an intervening world frame.

Client presentation helper: `ExaminerPresentation.drawInfoIcon(int x, int y,
boolean hovered, int color)` draws the shared 14 x 14 information icon in the
current GUI coordinate system. `color` is an RGB integer. This is a client-only
helper; it does not create a tooltip or install input handlers. Avogadro menus
use it with left-opening hover help, the active resource-pack font, and shared
paper surfaces and controls.

Settings and isotope overlays preserve the camera; Escape returns to the view.
Single-atom component navigation returns directly to its parent. Composition
uses a compact header, and the model fills the view until an atom card or
component list is open. Structure
property sheets wrap and scroll without moving their preview controls. Changing
crystal or polymer preview size preserves the camera and animation pause state.
Element-card font measurements are cached and invalidated on resource reload.

### Background molecule generation and exact SMILES cache

`MoleculeBuilder.buildAsync(): CompletableFuture<MoleculeSpec>` snapshots the builder's
current JSON and validates it on Avogadro's bounded background workers. Later builder
changes do not affect that request. `MoleculeSpec.fromJsonAsync(String id, String json)`
and `MoleculeSpec.fromJsonAsync(ResourceLocation id, String json)` provide the same
background validation for JSON. Validation failures complete the future exceptionally;
a full queue fails with `RejectedExecutionException`. Cancelling the future interrupts
its task. Completion callbacks may run on a worker: schedule Minecraft interactions
and registration commits on the proper client/server thread. Existing `build()` and
`fromJson()` remain synchronous; registration commits retain their authority-thread
validation/publication semantics.

Exact SMILES layouts are reused across IDs, names and association changes. Their
bounded memory cache holds at most 32 MiB (estimated storage) / 4,096 entries;
`config/avogadro/cache/smiles-v1` holds at most 256 MiB / 4,096 disk entries. Cache
failures and incompatible entries cause recomputation. Cached layouts retain the
original solver's exact coordinates, atom order and bond order. No definition is
registered merely because its layout is cached.

### Export pacing

Config > Exports > Performance Mode (`exports.performanceMode`) defaults to
`SMOOTH_GAMEPLAY`. It cooperatively spreads preparation, uploads, drawing and pixel
readback across game frames. `FASTEST_EXPORT` prioritizes completion until bounded
pipeline backpressure applies. Changes take effect on the next render frame. Both
modes preserve output settings, frame count, duration, geometry and image quality.
Smooth mode is a scheduling allowance, not a hard guarantee against graphics-driver
stalls. OpenGL work remains on the client thread; pixel processing and encoding run
in bounded background workers.

## Additive structure, network, and fluid APIs (1.0.0-cleanroom.53)

The APIs in this section supplement the earlier signatures. Geometry remains authored:
attachments, assembly, editing and random patterns do not reposition fragments, repair
valence, add/remove hydrogens, infer bonds, or optimize chemistry.

### Named attachments and editable fragments

```java
// Additional StructureBuilder methods; names resolve to zero-based atom indices.
StructureBuilder attachment(String name, int atom);
StructureBuilder bond(String from, String to);
StructureBuilder bond(String from, String to, BondType type);
StructureBuilder bond(String from, String to, String type);
StructureBuilder atomConfiguration(int atom, AtomicConfiguration configuration);
StructureBuilder randomLinkCapacity(int atom, int additionalLinks);
StructureBuilder randomLinkCapacity(String attachment, int additionalLinks);

// Additional StructureFragment methods.
Map<String, Integer> attachments();
int attachment(String name);
Map<Integer, AtomicConfiguration> configurations();
Map<Integer, Integer> randomLinkCapacities();
StructureFragment qualified(String prefix);
StructureFragment append(String instance, StructureFragment other);
StructureFragment instances(String prefix, List<double[][]> transforms);
StructureEditor edit();
static StructureFragment from(MoleculeStructure structure);
static StructureFragment from(MoleculeDef definition);
```

Attachment/configuration/capacity maps are immutable. Duplicate attachment names and
unresolved references fail. Names are slash-separated segments of letters, numbers,
underscore, dot or hyphen, optionally followed by numeric instance brackets; dot-only
segments and wildcards are not attachment names. Assembly selectors may use wildcards.
`qualified("cap")` produces names such as `cap/head`; named append and instances qualify
names before merging. All transforms retain these maps. Unit cells must be expanded
before topology editing. Both original index-based fragment constructors and the
following constructors are public:

```java
new StructureFragment(List<StructureFragment.Atom> atoms,
    List<StructureFragment.Bond> bonds, Map<String,Integer> attachments,
    Map<Integer,AtomicConfiguration> configurations, Map<Integer,Integer> capacities);
new StructureFragment(List<StructureFragment.Atom> atoms,
    List<StructureFragment.Bond> bonds, double[][] vectors,
    List<StructureFragment.CellLink> links, Map<String,Integer> attachments,
    Map<Integer,AtomicConfiguration> configurations, Map<Integer,Integer> capacities);

new StructureEditor(StructureFragment source);
StructureEditor replaceAtom(int index, String element);
StructureEditor moveAtom(int index, double x, double y, double z);
int addAtom(String element, double x, double y, double z);
StructureEditor atom(String element, double x, double y, double z);
StructureEditor setBond(int from, int to); // SINGLE; adds or changes a bond
StructureEditor setBond(int from, int to, BondType type);
StructureEditor removeBond(int from, int to);
StructureEditor removeAtom(int index);
StructureEditor removeAtoms(int... indices);
StructureEditor append(StructureFragment fragment);
StructureEditor append(String instance, StructureFragment fragment);
StructureEditor connect(String from, String to); // SINGLE; rejects an existing bond
StructureEditor connect(String from, String to, BondType type);
StructureEditor attachment(String name, int index);
StructureEditor removeAttachment(String name);
StructureEditor atomConfiguration(int index, AtomicConfiguration configuration);
StructureEditor clearAtomConfiguration(int index);
StructureEditor randomLinkCapacity(int index, int additionalLinks);
StructureFragment fragment();
StructureEditResult build();
record StructureEditResult(StructureFragment fragment, List<Integer> oldToNew);
int StructureEditResult.remap(int originalIndex);
```

An editor owns a private copy. Each operation uses current indices. Bulk deletion
resolves its whole selection first, then remaps surviving bonds, attachments, atomic
configurations and capacities. `oldToNew` covers source atoms; deleted atoms map to -1.
Changing an element clears its old atom-specific configuration; set a replacement
configuration explicitly afterward. A result must retain at least one atom.

`StructureEdits.builder()` offers the same serializable operations: `replaceAtom`,
`moveAtom`, `atom`, `setBond`, `removeBond`, `removeAtoms`, named `append`, `connect`,
`attachment`, `removeAttachment`, `atomConfiguration`, `clearAtomConfiguration`, and
`randomLinkCapacity`, then `build()`. `StructureEdits.fromJson(JsonArray)` validates
syntax immediately. `toJson()` returns a copy; `apply(StructureFragment)` resolves
indices against the real source and returns `StructureEditResult`.

`MoleculeEdits.apply(String newId, MoleculeDef source, StructureEdits edits,
boolean materialize)` and `StructureEdits.apply(String, MoleculeDef, boolean)` retain
names, associations, components and ordinary inspection metadata. Composition edits
recalculate finite formulas. `MoleculeEdits.snapshot(MoleculeDef)` creates independent
source JSON. Nonempty edits on polymer, network or crystal previews require explicit
`materialize=true`; that removes template/classification metadata before editing.
`AvogadroApi.edit(MoleculeDef)` returns a geometry-only editor; use MoleculeEdits when
preserving the full molecule definition is required.

The molecule builder adds `edits(StructureEdits)` and `materialize(boolean)` for copy
recipes. JSON uses `copy` plus a root `edits` array and optional `materialize` boolean:

```json
{
  "copy": "example:authored_source",
  "materialize": true,
  "edits": [
    {"op":"replaceAtom","index":0,"e":"N"},
    {"op":"moveAtom","index":0,"x":0,"y":1,"z":0},
    {"op":"setBond","from":0,"to":1,"type":"DOUBLE"},
    {"op":"removeAtoms","indices":[2,3]}
  ]
}
```

Other edit JSON shapes are `{op:atom,e,x,y,z}`, `{op:removeBond,from,to}`,
`{op:append,instance,structure}`, `{op:connect,from,to,type}` (string attachment paths),
`{op:attachment,name,index}`, `{op:removeAttachment,name}`,
`{op:atomConfiguration,index,nuclear}`, `{op:clearAtomConfiguration,index}`, and
`{op:randomLinkCapacity,index,capacity}`. These braces describe field names, not valid
JSON literals; actual JSON requires quoted keys. Bond `type` defaults to `SINGLE`.
At most 64 edit operations are accepted. The structure recipe's operations list also
accepts `{"op":"edit","edits":[...]}`. Existing `append` operations may add an
`instance` string, and `instances` operations may add a `prefix` string.

Structure JSON additionally accepts `"attachments":{"head":0,"tail":1}` and
`"random_link_capacities":{"0":1}`. Atom-local `nuclear` objects retain the existing
AtomicConfiguration schema. These maps survive registration, reload and synchronization.

The existing PolymerDefinition record is unchanged. Named conveniences are
`PolymerDefinition.usingAttachments(StructureFragment, String head, String tail,
PolymerDefinition.Vector, BondType, PolymerDefinition.Cap headCap,
PolymerDefinition.Cap tailCap, int previewUnits)` and
`PolymerDefinition.Cap.usingAttachment(StructureFragment, String, BondType)`.
`PolymerGeometry.chainFragment(PolymerDefinition, int)` retains attachments as
`repeat[i]/name`, `head/name`, and `tail/name`, with per-instance nuclear overrides.

### Finite assembly and adjustable networks

`AvogadroApi.assemblyBuilder()` and `StructureAssembly.builder()` return the same
builder. `MoleculeBuilder.structure(StructureAssembly)` writes a finite
`structure.assembly` recipe. A literal-count assembly can be expanded with
`StructureAssembly.build()`. `toJson()` returns a defensive copy; `fromJson(JsonObject)`
validates a recipe. All transforms are authored finite 3-by-4 affine matrices.

```java
StructureAssembly.Builder part(String name, StructureFragment fragment);
StructureAssembly.Builder part(String name, StructureFragment fragment, double[][] transform);
StructureAssembly.Builder group(String name, StructureAssembly body, double[][] transform);
StructureAssembly.Builder repeat(String name, StructureAssembly body, int count, double[][] step);
StructureAssembly.Builder repeat(String name, StructureAssembly body, String countParameter, double[][] step);
StructureAssembly.Builder transformLast(double[][] transform);
StructureAssembly.Builder neighbor(String from, String to, int offset, BondType type);
StructureAssembly.Builder endGroups(StructureAssembly head, StructureAssembly tail);
StructureAssembly.Builder link(String from, String to, BondType type);
StructureAssembly.Builder choice(String name, String ruleId, double probability,
    StructureAssembly present, StructureAssembly absent);
StructureAssembly.Builder choice(String name, String ruleId, String probabilityParameter,
    StructureAssembly present, StructureAssembly absent);
StructureAssembly.Builder randomLinks(String ruleId, String from, String to,
    String pairing, double probability, BondType type);
StructureAssembly.Builder randomLinks(String ruleId, String from, String to,
    String pairing, String probabilityParameter, BondType type);
StructureAssembly StructureAssembly.Builder.build();
static double[][] StructureAssembly.translation(double x, double y, double z);

new PolymerNetworkDefinition(List<PolymerNetworkDefinition.Parameter> parameters,
    StructureAssembly assembly, long seed);
record PolymerNetworkDefinition.Parameter(String name, String label, String kind,
    double min, double max, double defaultValue);
static Parameter PolymerNetworkDefinition.Parameter.count(String name, int min, int max, int value);
static Parameter PolymerNetworkDefinition.Parameter.probability(String name, double value);
List<Parameter> PolymerNetworkDefinition.parameters();
StructureAssembly PolymerNetworkDefinition.assembly();
String PolymerNetworkDefinition.fingerprint();
NetworkPreviewRequest PolymerNetworkDefinition.defaults();
PolymerPreview PolymerNetworkDefinition.preview(NetworkPreviewRequest request);
List<String> PolymerNetworkDefinition.validate(NetworkPreviewRequest request);
JsonObject PolymerNetworkDefinition.toJson();
static PolymerNetworkDefinition PolymerNetworkDefinition.fromJson(JsonObject json);
static PolymerNetworkDefinition PolymerNetworkDefinition.of(MoleculeDef molecule); // null when absent
record NetworkPreviewRequest(Map<String,Integer> counts, Map<String,Double> probabilities, long seed);
StructureFragment PolymerPreview.fragment();
MoleculeStructure PolymerPreview.structure();
NetworkPreviewRequest PolymerPreview.request();
Map<String,Integer> PolymerPreview.diagnostics();
String PolymerPreview.formula();
MoleculeWeight.Result PolymerPreview.weight();
JsonObject PolymerPreview.reproduction();
```

`neighbor` and `endGroups` configure the builder's last repeat; `transformLast`
configures its last node. Neighbor endpoints are relative to repeat bodies and
out-of-bound offsets are omitted. End groups appear at `repeatName/head/...` and
`repeatName/tail/...`, using the first/last repeat frames. Transforms compose as
parent * node * step^index * body. Parts never move to satisfy a link.

Paths include `chain[0]/unit/head`, `chain[first]/unit/head`,
`chain[last]/unit/tail`, and `chain[*]/branch[*]/unit/site`. Ordinary links pair equal
selection lengths or broadcast a singleton. Other sizes fail. Selection order is
natural indexed path order, independent of node insertion order. Optional choices
retain declared positions while pairing; a link with an absent endpoint is omitted,
without shifting later pairs. Unknown paths fail even in absent alternatives.
Choice alternatives sharing a repeat path must declare the same extent.

`MoleculeBuilder.polymerNetwork(PolymerNetworkDefinition)` registers a family and its
validated default preview. A request may omit parameters to use defaults, but unknown
or wrong-kind names fail. The seed is always explicit in a request. Finite preview
formulas and standard-weight masses describe that realization only. Registered
network formula/mass displays are labeled default-preview values, including generated
items through `MoleculeItems.displayFormula`. There is no universal bulk network mass.
Network definitions use the standard molecule examiner. The dedicated adjustable
network viewer has been removed. Programmatic preview APIs remain available.

JSON uses root `polymer_network` instead of another geometry source:

```json
{
  "polymer_network": {
    "random_version":1,
    "seed":"42",
    "parameters":[{"name":"n","label":"Units","kind":"count","min":1,"max":8,"default":3}],
    "assembly":{
      "nodes":[{
        "name":"chain","kind":"repeat","count":"n",
        "step":[[1,0,0,2],[0,1,0,0],[0,0,1,0]],
        "body":{"nodes":[{"name":"unit","kind":"fragment","structure":{
          "atoms":[{"e":"C","x":0,"y":0,"z":0}],"bonds":[],"attachments":{"site":0}
        }}]},
        "neighbor_links":[{"from":"unit/site","to":"unit/site","offset":1,"type":"SINGLE"}]
      }]
    }
  }
}
```

An assembly object has required `nodes` and optional `links`/`random_links` arrays.
Every node requires `name`, `kind`, and the following kind-specific fields:
`fragment` has `structure`; `group` has `body`; `repeat` has `body`, `count`, and `step`,
plus optional `neighbor_links`, `head`, and `tail`; `choice` has `rule_id`,
`probability`, `present`, and `absent` (both assembly objects, allowed to have no nodes).
Each node may have `transform`. Links require `from`, `to`, and enum-name `type`.
Random links additionally require `rule_id`, `pairing`, and `probability`. Pairing is
`paired`, `broadcast` (at least one selection must be a singleton), or `cartesian`.
Count/probability values are JSON numbers or declared parameter-name strings. Parameter
bounds/defaults are JSON numbers, not booleans or numeric strings. Nuclear overrides
belong on fragment-local atoms; network-level flattened `atom_configurations` fail.

Limits are 4,096 realized atoms, 8,192 realized bonds, eight parameters, repeat counts
1..64, 64 authored nodes including both choice alternatives, 16 nesting levels, and
65,536 candidate pairs per preview. Expansion/declaration guards also cap instances,
attachment slots and expanded link rules at 65,536. Defaults must produce valid
nonempty geometry. Oversized realizations fail without truncation or reseeding.

Each choice independently samples every occurrence and expands its authored present
or absent alternative. Caps can be explicit absent alternatives. Random links only
use selected authored endpoint pools. Every participating atom needs explicit
additional-random-link capacity, including when probability is zero. Aliases share
one atom's capacity; ordinary bonds do not consume that additional capacity. Self,
duplicate and already-bonded candidate pairs fail. Passing candidates are processed
in seeded order until capacities prevent further links; requested probability is not
an exact final density. Diagnostics report `eligible_branches`, `selected_branches`,
`eligible_crosslinks`, `passing_crosslinks`, `accepted_crosslinks`, `capacity_blocked`,
`omitted_links`, `atoms`, and `bonds`.

Random algorithm version 1 hashes the following byte sequence with SHA-256: big-endian
32-bit version 1; big-endian signed 64-bit seed; then phase, rule, and identity strings,
each encoded as big-endian 32-bit UTF-8 byte length followed by those bytes. Interpret
the first seven digest bytes as an unsigned big-endian integer, discard its three low
bits, and multiply by 2^-53. A sample passes when it is strictly below probability.
For branches the phase is `branch`, rule is the authored rule ID, and identity is the
choice's stable instance path. For links the phases are `crosslink` and
`crosslink-order`; rule is the scope-qualified rule ID. Endpoint identities are
`fragmentPath@localAtomIndex`, sorted lexicographically and joined with `|`. Passing
links sort by the order sample, then endpoint identity. Persist `random_version:1`
and a signed 64-bit decimal-string seed. `reproduction()` includes the template,
fingerprint, resolved counts/probabilities, and exact seed; returned JSON is defensive.

Groovy's `mods.avogadro.molecules` adds `assemblyBuilder()`, `assembly(Closure)` and
`edits()`. The assembly closure delegates to StructureAssembly.Builder. Existing
`structure(Closure)` delegates to StructureBuilder, including named attachments.
Use the Java network constructors from Groovy and pass the result to the molecule
builder's `polymerNetwork(...)`; JSON maps use the same schema.

### Fluid selectors and full stacks

A fluid selector may include partial compound NBT, for example
`example:fluid#{Grade:"pure"}`; escape inner quotes in JSON. Fluid selectors have no
item-style metadata suffix. `MoleculeBuilder.fluids(Object...)` accepts qualified
strings, ResourceLocation, Fluid, and nonempty FluidStack; stack tags are copied into
the selector. Amount is irrelevant after the nonempty check.

Full-stack lookup first checks matching NBT-qualified selectors, preferring greater
recursive NBT-node specificity and then later registration. It then uses the existing
unqualified fluid-ID, tag, GregTech material/path, and fluid-path fallbacks. Matching
uses the same partial compound/list comparison as item NBT selectors; unrelated
actual tags are allowed, and absent tags do not satisfy a required compound. Bare
Fluid queries skip all NBT-qualified rules. Item metadata/NBT precedence is unchanged.

MoleculeView.find(FluidStack), contained-fluid item lookup, tooltip contexts, and HEI
associations retain tags. Associated examples keep distinct NBT variants and are
returned as independent stacks. Source selectors and their tags survive reload and
synchronization. Empty/null fluid stacks do not match.

### Client rendering and typed export jobs

Client code uses `io.github.wurtzitane.avogadro.api.client.AvogadroClientApi`.
`render(structure, x, y, width, height, options, elapsedSeconds)` accepts immutable
`MoleculeRenderOptions`: orientation, pan, zoom, atom/bond scale, background,
palette overrides, and rotation speed. Options are local to that draw and do not
change saved configuration. Keep these client classes out of common/server code.

`AvogadroClientApi.exports().submit(MediaExportRequest)` returns an `ExportJob`.
Submit on the client thread; invalid or busy requests fail explicitly. Requests
support definitions, item cards, atoms, finite structures, and `PolymerPreview`.
`options(ExportOptions)` controls media settings; `renderOptions(...)` supplies
local appearance. A polymer request retains its exact successful realization and
writes a reproduction sidecar. Progress and cancellation are thread-safe. A handle
cannot cancel a later job. Completion listeners run once on the client thread,
after graphics/encoder cleanup; late listeners receive the retained terminal
result. Existing output is never overwritten silently.

### Host-defined periodic-table entries

`ElementCatalog.register(ElementCatalog.Element, Map<String,String>)` registers
additional elements above atomic number 118, or a neutral neutron-only reference at atomic number zero. Register on both sides during mod
initialization. The original `elements()` list remains the bundled 118-element
reference. `additionalElements()` lists host entries in atomic-number order;
`bySymbol`, `byAtomicNumber`, `fact`, `color`, and `configuration` also resolve them.
Registration validates the isotope list, nuclear configuration, and symbol/number
uniqueness. Facts use the same keys as bundled entries (`state`, `reference_state`,
`crystal`, `oxide`, etc.). Repeated identical registrations are idempotent.

Additional entries appear in a separate row on the table, poster and held item.
Poster artwork refreshes when registrations change. Custom atoms without a palette
entry use their registered color, or the neutral fallback for unregistered symbols.

MoleculeWeight uses a positive finite numeric mass supplied by a host-defined element at atomic number zero or above 118 when no bundled standard weight exists. Built-in element weights retain precedence, and unknown or invalid custom masses remain rejected. Host registrations are resolved at calculation time.

AtomicConfiguration also accepts atomic number zero only for a nonempty, neutral neutron cluster: positive neutrons, zero charge and zero electrons. Such a host reference may use an empty electron-shell list. Neutron-only inspection draws the nucleus without electron orbits, particles or cloud envelopes; it does not describe an ordinary chemical atom.

Host element facts may include `isotope_name_<massNumber>` to append a human-readable name to that isotope in the examiner and isotope list.

`atoms.predictNeutralElectronConfigs` is a client presentation setting, disabled by default. It controls display of predicted, calculated and host-authored neutral configurations; atomic inventories remain unchanged. `-Davogadro.predictNeutralElectronConfigs=true` seeds the startup value for a development session without locking its in-game toggle.

Client hosts may call `ElementActions.registerMaterialForms(Predicate<String>, BiConsumer<String, Runnable>)` to expose a Material forms button for supported element symbols. Invoke the supplied Runnable to return to the examiner. `ElementActions.closeAll()` closes the navigation chain without return callbacks. These hooks are client-only and optional.

## Optional desktop viewers (1.0.0-cleanroom.57)

OffscreenCleanroom 0.1.1 adds a separate-window action to the molecule, crystal,
polymer and periodic-table viewers on Cleanroom 0.7.13-alpha. Install its regular
JAR separately on the client. The optional adapter is client-only; absent-mod
operation retains the existing in-game viewers. It adds no server channel or
chemistry data. The compile-only development API is pinned in libs/ and is not
bundled in Avogadro's runtime JAR.

The arrow button opens an independent viewer copy without closing the main game
screen. Detached navigation stays in that window; Back returns to its retained
parent. Escape closes the current detached view (or its local menu first), and
closing the desktop window disposes its entire viewer stack without main-screen
return callbacks. Table selections and isotopes are copied when detaching.

## Pixel text (1.0.0-cleanroom.57)

Client code can use `PixelFont.renderer()` or `PixelFont.wrap(FontRenderer)` from
`io.github.wurtzitane.avogadro.client`. The adapter uses Kirino's resource-pack
Minecraft glyphs with the caller's GL transform, including fractional scales,
world labels and export framebuffers. It preserves vanilla layout metrics,
formatting and custom item renderers. Kirino requires OpenGL 4.6 and successful
font loading; unavailable rendering, bidirectional text and forced Unicode mode
use the game font.
`PixelFont.available()` reports Kirino service availability.

Call `PixelFont.registerUiOwner(modId)` from client initialization to use the same
font in that owner's ModularUI text widgets. Other owners retain their renderer.
The borrowed Kirino runtime can change on resource reload; never retain its font
handle independently. These APIs are client-only and require Avogadro .54 or later.

Element cards choose whole framebuffer-pixel glyph sizes before measuring and wrapping
labels, including raised orbital counts. Other callers retain their requested size.
PixelFont.physicalScale() returns framebuffer pixels per GUI unit for an axis-aligned,
uniform transform, or zero when pixel sizing is not applicable (Avogadro .55+).

Molecule viewers keep atom balls opaque until `atoms.atomRevealZoom` (default 1.5, range 1–16). Over the next zoom unit the balls fade out to reveal atomic components. This threshold is independent of viewport size. Atomic components render in front of bonds. Configure it under Atoms → Atomic detail reveal zoom.
