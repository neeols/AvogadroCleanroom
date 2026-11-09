import java.util.List;
import java.util.Map;
import io.github.wurtzitane.avogadro.api.*;
import io.github.wurtzitane.avogadro.molecule.BondType;

/** Complete model recipes used by the documentation. Positions are authored schematics. */
public final class GuideModels {
    private GuideModels() {}

    // BEGIN water
    public static StructureFragment water() {
        // All atoms are explicit: StructureBuilder does not add hydrogens.
        return new StructureBuilder()
                .atom("O", 0, 0, 0)
                .atom("H", .7586, .5043, 0)
                .atom("H", -.7586, .5043, 0)
                .bond(0, 1).bond(0, 2)
                .attachment("oxygen", 0)
                .atomConfiguration(0, new AtomicConfiguration(8, 8, 0, List.of(2, 6)))
                .atomConfiguration(1, new AtomicConfiguration(1, 0, 0, List.of(1)))
                .atomConfiguration(2, new AtomicConfiguration(1, 0, 0, List.of(1)))
                .build();
    }
    // END water

    // BEGIN hydroxyl
    public static StructureFragment hydroxyl() {
        // An open O site for assembly; this piece alone is not an alcohol.
        return new StructureBuilder().atom("O", 1.43, 0, 0).atom("H", 1.8, .85, 0)
                .bond(0, 1).attachment("oxygen", 0).build();
    }
    // END hydroxyl

    // BEGIN methyl
    public static StructureFragment methyl() {
        return new StructureBuilder().atom("C", 0, 0, 0)
                .atom("H", -.36, 1.03, 0).atom("H", -.36, -.515, .892)
                .atom("H", -.36, -.515, -.892)
                .bond(0, 1).bond(0, 2).bond(0, 3)
                .attachment("carbon", 0).build();
    }
    // END methyl

    // BEGIN methanol
    public static StructureFragment methanol() {
        // Fragment positions already line up. link() only adds the O–C bond.
        return StructureAssembly.builder()
                .part("methyl", methyl()).part("hydroxyl", hydroxyl())
                .link("methyl/carbon", "hydroxyl/oxygen", BondType.SINGLE)
                .build().build(); // recipe first, then finite geometry
    }
    // END methanol

    // BEGIN ammonia
    public static StructureEditResult ammonia() {
        // Change O to N and add the third hydrogen: H2O -> NH3 as a graph edit.
        // This demonstrates authoring; it does not simulate a reaction.
        def editor = water().edit().replaceAtom(0, "N").moveAtom(0, 0, 0, .25);
        int hydrogen = editor.addAtom("H", 0, -.85, -.35);
        return editor.setBond(0, hydrogen, BondType.SINGLE).build();
    }
    // END ammonia

    // BEGIN peg
    public static PolymerDefinition peg() {
        // HO–(CH2–CH2–O)n–H. Caps supply both terminal hydrogens and the extra oxygen.
        def unit = new StructureBuilder().atom("C", 0, 0, 0)
                .atom("C", 1.5, 0, 0).atom("O", 2.9, .2, 0)
                .atom("H", -.3, .8, .65).atom("H", -.3, -.8, -.65)
                .atom("H", 1.7, .8, -.65).atom("H", 1.7, -.8, .65)
                .bond(0, 1).bond(1, 2).bond(0, 3).bond(0, 4).bond(1, 5).bond(1, 6)
                .attachment("head", 0).attachment("tail", 2).build();
        def head = new StructureBuilder().atom("O", -1.4, -.2, 0)
                .atom("H", -2.2, .2, 0).bond(0, 1).attachment("end", 0).build();
        def tail = new StructureBuilder().atom("H", 3.8, .2, 0).attachment("end", 0).build();
        return PolymerDefinition.usingAttachments(unit, "head", "tail",
                new PolymerDefinition.Vector(4.3, 0, 0), BondType.SINGLE,
                PolymerDefinition.Cap.usingAttachment(head, "end", BondType.SINGLE),
                PolymerDefinition.Cap.usingAttachment(tail, "end", BondType.SINGLE), 4);
    }
    // END peg

    // BEGIN rubber-unit
    public static StructureFragment rubberUnit() {
        // 1,4-polyisoprene repeat with one deliberately open site at C4.
        // A separate choice supplies either H or a shared sulfur bridge there.
        return new StructureBuilder()
                .atom("C", 0, 0, 0).atom("C", 1.5, 0, 0)
                .atom("C", 2.8, .4, 0).atom("C", 4.2, .4, 0).atom("C", 1.5, -1.5, 0)
                .atom("H", -.4, .7, .7).atom("H", -.4, .7, -.7)
                .atom("H", 2.9, 1.4, 0).atom("H", 4.4, .4, 1)
                .atom("H", .5, -1.9, 0).atom("H", 2, -1.9, .8).atom("H", 2, -1.9, -.8)
                .bond(0, 1).bond(1, 2, BondType.DOUBLE).bond(2, 3).bond(1, 4)
                .bond(0, 5).bond(0, 6).bond(2, 7).bond(3, 8)
                .bond(4, 9).bond(4, 10).bond(4, 11)
                .attachment("head", 0).attachment("tail", 3).attachment("cross", 3).build();
    }
    // END rubber-unit

    // BEGIN rubber
    public static PolymerNetworkDefinition rubber() {
        def sulfur = new StructureBuilder().atom("S", 4.2, 2.2, 0)
                .attachment("left", 0).attachment("right", 0).build();
        def hydrogens = new StructureBuilder().atom("H", 4.2, 1.4, 0)
                .atom("H", 4.2, 3.0, 0).attachment("left", 0).attachment("right", 1).build();
        def pair = StructureAssembly.builder()
                .part("left", rubberUnit()).part("right", rubberUnit().translate(0, 3.6, 0))
                .choice("bridge", "sulfur_bridge", "bridgeChance",
                        StructureAssembly.builder().part("connector", sulfur).build(),
                        StructureAssembly.builder().part("connector", hydrogens).build())
                .link("left/cross", "bridge/connector/left", BondType.SINGLE)
                .link("right/cross", "bridge/connector/right", BondType.SINGLE).build();
        def head = new StructureBuilder().atom("H", -1, 0, 0).atom("H", -1, 3.6, 0)
                .attachment("left", 0).attachment("right", 1).build();
        def tail = new StructureBuilder().atom("H", 5.2, .4, 0).atom("H", 5.2, 4, 0)
                .attachment("left", 0).attachment("right", 1).build();
        def assembly = StructureAssembly.builder()
                .repeat("pairs", pair, "length", StructureAssembly.translation(5.7, 0, 0))
                .neighbor("left/tail", "left/head", 1, BondType.SINGLE)
                .neighbor("right/tail", "right/head", 1, BondType.SINGLE)
                .endGroups(StructureAssembly.builder().part("cap", head).build(),
                        StructureAssembly.builder().part("cap", tail).build())
                .link("pairs/head/cap/left", "pairs[first]/left/head", BondType.SINGLE)
                .link("pairs/head/cap/right", "pairs[first]/right/head", BondType.SINGLE)
                .link("pairs[last]/left/tail", "pairs/tail/cap/left", BondType.SINGLE)
                .link("pairs[last]/right/tail", "pairs/tail/cap/right", BondType.SINGLE).build();
        return new PolymerNetworkDefinition(List.of(
                PolymerNetworkDefinition.Parameter.count("length", 1, 16, 4),
                PolymerNetworkDefinition.Parameter.probability("bridgeChance", .5)), assembly, 42L);
    }
    // END rubber

    // BEGIN realization
    public static PolymerPreview realization(int length, double chance, long seed) {
        // Seed controls the choices, not the authored atom positions.
        return rubber().preview(new NetworkPreviewRequest(
                Map.of("length", length), Map.of("bridgeChance", chance), seed));
    }
    // END realization

    // BEGIN nacl
    public static StructureFragment saltBasis() {
        // Conventional rock-salt cell, a = 5.64 Å. No covalent Na–Cl bonds.
        def basis = new StructureBuilder();
        double a = 5.64;
        double[][] sodium = [[0,0,0], [0,.5,.5], [.5,0,.5], [.5,.5,0]] as double[][];
        double[][] chlorine = [[.5,.5,.5], [.5,0,0], [0,.5,0], [0,0,.5]] as double[][];
        for (double[] p : sodium) {
            int atom = basis.addAtom("Na", p[0]*a, p[1]*a, p[2]*a);
            basis.atomConfiguration(atom, new AtomicConfiguration(11, 12, 1, List.of(2,8)));
        }
        for (double[] p : chlorine) {
            int atom = basis.addAtom("Cl", p[0]*a, p[1]*a, p[2]*a);
            basis.atomConfiguration(atom, new AtomicConfiguration(17, 18, -1, List.of(2,8,8)));
        }
        return basis.build();
    }
    // END nacl

    // BEGIN catalog
    public static MoleculeBatch catalog() {
        def batch = new MoleculeBatch();
        batch.register(AvogadroApi.builder("guide:water").name("Water").structure(water())
                .items("minecraft:water_bucket").fluids("water").generateItem(true));
        batch.register(AvogadroApi.builder("guide:methanol").name("Methanol").structure(methanol()));
        batch.register(AvogadroApi.builder("guide:ethanol").name("Ethanol").smiles("CCO"));
        batch.register(AvogadroApi.builder("guide:aspirin").name("Aspirin")
                .smiles("CC(=O)OC1=CC=CC=C1C(=O)O"));
        batch.register(AvogadroApi.builder("guide:caffeine").name("Caffeine")
                .smiles("CN1C=NC2=C1C(=O)N(C(=O)N2C)C"));
        batch.register(AvogadroApi.builder("guide:crown").name("18-crown-6")
                .smiles("C1COCCOCCOCCOCCOCCO1"));
        batch.register(AvogadroApi.builder("guide:ammonia").name("Ammonia").structure(ammonia().fragment()));
        batch.register(AvogadroApi.builder("guide:peg").name("Polyethylene glycol").polymer(peg()));
        batch.register(AvogadroApi.builder("guide:rubber").name("Sulfur-linked polyisoprene")
                .polymerNetwork(rubber()));
        batch.register(AvogadroApi.builder("guide:salt").name("Sodium chloride").structure(saltBasis())
                .crystalCell(new CrystalCell(5.64, 5.64, 5.64, 90, 90, 90)).formula("NaCl"));
        return batch;
    }
    // END catalog
}


// Common GroovyScript postInit registration.
def registry = mods.avogadro.molecules
registry.register(AvogadroApi.builder("guide:water").name("Water").structure(GuideModels.water())
        .items("minecraft:water_bucket").fluids("water").generateItem(true));
registry.register(AvogadroApi.builder("guide:methanol").name("Methanol").structure(GuideModels.methanol()));
registry.register(AvogadroApi.builder("guide:ethanol").name("Ethanol").smiles("CCO"));
registry.register(AvogadroApi.builder("guide:aspirin").name("Aspirin")
        .smiles("CC(=O)OC1=CC=CC=C1C(=O)O"));
registry.register(AvogadroApi.builder("guide:caffeine").name("Caffeine")
        .smiles("CN1C=NC2=C1C(=O)N(C(=O)N2C)C"));
registry.register(AvogadroApi.builder("guide:crown").name("18-crown-6")
        .smiles("C1COCCOCCOCCOCCOCCO1"));
registry.register(AvogadroApi.builder("guide:ammonia").name("Ammonia").structure(GuideModels.ammonia().fragment()));
registry.register(AvogadroApi.builder("guide:peg").name("Polyethylene glycol").polymer(GuideModels.peg()));
registry.register(AvogadroApi.builder("guide:rubber").name("Sulfur-linked polyisoprene")
        .polymerNetwork(GuideModels.rubber()));
registry.register(AvogadroApi.builder("guide:salt").name("Sodium chloride").structure(GuideModels.saltBasis())
        .crystalCell(new CrystalCell(5.64, 5.64, 5.64, 90, 90, 90)).formula("NaCl"));
