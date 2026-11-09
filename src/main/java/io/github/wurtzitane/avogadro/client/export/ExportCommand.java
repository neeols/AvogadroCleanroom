package io.github.wurtzitane.avogadro.client.export;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.IClientCommand;

/**
 * Handles exports locally, while forwarding the existing server command unchanged.
 */
public final class ExportCommand extends CommandBase implements IClientCommand {

    /** List of supported command-line option flags for export customization. */
    private static final List<String> OPTIONS = List.of("--format", "--width", "--height", "--fps", "--speed",
            "--rotations", "--name", "--formula", "--background", "--direction", "--appearance", "--columns",
            "--output", "--encoder", "--text-scale");

    /**
     * Creates a {@link ExportCommand}.
     */
    public ExportCommand() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "avogadro";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getUsage(ICommandSender sender) {
        return "/avogadro export <molecule|all|3d molecule|item [held|item-id]|cancel|status|help> [options]";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0 || !args[0].equalsIgnoreCase("export")) {
            // EntityPlayerSP sends the packet directly; GuiScreen.sendChatMessage would recurse.
            if (Minecraft.getMinecraft().player == null) {
                throw new CommandException("Join a world to use /avogadro.");
            }
            Minecraft.getMinecraft().player
                    .sendChatMessage("/avogadro" + (args.length == 0 ? "" : " " + String.join(" ", args)));
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
            if (args.length > 2) {
                throw new CommandException("Use /avogadro export help without options.");
            }
            help(sender);
            return;
        }
        if (args[1].equalsIgnoreCase("cancel") || args[1].equalsIgnoreCase("status")) {
            if (args.length != 2) {
                throw new CommandException("Use /avogadro export " + args[1] + " without options.");
            }
            String message = args[1].equalsIgnoreCase("cancel")
                    ? MoleculeMediaExport.cancel() ? "Export cancellation requested." : "No export is running."
                    : MoleculeMediaExport.status();
            sender.sendMessage(new TextComponentString("Avogadro: " + message));
            return;
        }
        try {
            if (args[1].equalsIgnoreCase("3d")) {
                if (args.length < 3 || args[2].equalsIgnoreCase("all")) {
                    throw new IllegalArgumentException(
                            "Choose one molecule: /avogadro export 3d namespace:molecule --atom 1");
                }
                var selected = select(args[2], MoleculeStore.view().all()).getFirst();
                var parsed = AtomExportOptions.parse(selected, Arrays.copyOfRange(args, 3, args.length));
                MoleculeMediaExport.startAtom(parsed.media(), parsed.atom());
                return;
            }
            if (args[1].equalsIgnoreCase("item")) {
                int firstFlag = args.length > 2 && !args[2].startsWith("--") ? 3 : 2;
                String target = firstFlag == 3 ? args[2] : "held";
                String[] flags = Arrays.copyOfRange(args, firstFlag, args.length);
                ItemStack held = Minecraft.getMinecraft().player == null
                        ? ItemStack.EMPTY
                        : Minecraft.getMinecraft().player.getHeldItemMainhand();
                ItemExportSelection selection = ItemExportSelection.select(target, ItemExportOptions.metadata(flags),
                        held);
                MoleculeMediaExport.startItem(ItemExportOptions.parse(selection, flags));
                return;
            }
            List<MoleculeDef> definitions = select(args[1], MoleculeStore.view().all());
            ExportOptions options = ExportOptions.parse(Arrays.copyOfRange(args, 2, args.length), definitions.size());
            MoleculeMediaExport.start(options, definitions);
        } catch (IllegalArgumentException | IllegalStateException | IOException exception) {
            throw new CommandException(exception.getMessage());
        }
    }

    /**
     * Selects and validates molecule definitions matching the requested query string.
     *
     * @param requested the requested molecule name or ID string
     *
     * @param loaded list of all loaded {@link MoleculeDef} instances
     * @return list of matching molecule definitions
     * @throws IllegalArgumentException if no molecules match or multiple matches are ambiguous
     */
    static List<MoleculeDef> select(String requested, List<MoleculeDef> loaded) {
        List<MoleculeDef> ordered = loaded.stream().sorted(Comparator.comparing(def -> def.id().toString())).toList();
        if (requested.equalsIgnoreCase("all")) {
            return ordered;
        }
        List<MoleculeDef> matches = ordered.stream()
                .filter(def -> requested.indexOf(':') < 0
                        ? def.id().getPath().equals(requested)
                        : def.id().toString().equals(requested))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown molecule " + requested + ". Use Tab to list loaded molecule IDs.");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "More than one molecule is named " + requested + "; use its full namespace:id.");
        }
        return matches;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
            @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "export", "reload");
        }
        if (!args[0].equalsIgnoreCase("export")) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> choices = new ArrayList<>(List.of("all", "3d", "item", "cancel", "status", "help"));
            MoleculeStore.view().all().stream().map(def -> def.id().toString()).sorted().forEach(choices::add);
            return getListOfStringsMatchingLastWord(args, choices);
        }
        boolean atom = args[1].equalsIgnoreCase("3d");
        if (atom && args.length == 3) {
            return getListOfStringsMatchingLastWord(args,
                    MoleculeStore.view().all().stream().map(def -> def.id().toString()).sorted().toList());
        }
        boolean item = args[1].equalsIgnoreCase("item");
        if (item && args.length == 3 && !args[2].startsWith("--")) {
            List<String> choices = new ArrayList<>(List.of("held"));
            Item.REGISTRY.getKeys().stream().map(Object::toString).sorted().forEach(choices::add);
            return getListOfStringsMatchingLastWord(args, choices);
        }
        String previous = args[args.length - 2].toLowerCase(Locale.ROOT);
        List<String> values = switch (previous) {
            case "--format" -> List.of("gif", "mp4");
            case "--details", "--name", "--formula" -> List.of("true", "false");
            case "--direction" -> List.of("forward", "reverse");
            case "--appearance" -> List.of("sphere", "cube");
            case "--background" -> item
                    ? List.of("#100010", "#DED8C4", "#FFFFFF", "#000000")
                    : List.of("none", "#DED8C4", "#FFFFFF", "#000000");
            case "--fps" -> List.of("20", "25", "30", "50", "60", "120", "240", "360", "1000", "2000");
            case "--speed", "--seconds-per-rotation" -> List.of("6", "3", "12");
            case "--rotations" -> List.of("1", "2", "3");
            case "--columns" -> List.of("2", "4", "8", "16");
            case "--scale" -> List.of("1", "2", "3", "4", "8");
            case "--meta" -> List.of("0", "1");
            case "--text-scale" -> List.of("0.5", "1", "1.5", "2", "3");
            case "--width", "--height", "--output", "--encoder" -> List.of();
            default -> {
                if (atom) {
                    var flags = new ArrayList<>(OPTIONS);
                    flags.addAll(List.of("--atom", "--details"));
                    yield flags;
                }
                if (!item) {
                    yield OPTIONS;
                }
                List<String> flags = new ArrayList<>(OPTIONS);
                flags.remove("--columns");
                flags.addAll(List.of("--scale", "--meta"));
                yield flags;
            }
        };
        return getListOfStringsMatchingLastWord(args, values);
    }

    /**
     * Sends formatted export help information to the command sender.
     *
     * @param sender the command sender
     */
    private void help(ICommandSender sender) {
        for (String line : List.of(getUsage(sender),
                "--format gif|mp4 --output filename --width pixels --height pixels --fps positive-integer (MP4); 1..60 input for GIF",
                "GIF uses at most 50 fps to avoid 10 ms frames that players may stretch; MP4 has no fixed fps cap; the 7,200-frame limit still applies.",
                "--speed seconds-per-rotation (or --seconds-per-rotation) --rotations complete-turns",
                "--name true|false --formula true|false --background \"#RRGGBB\"|none",
                "--direction forward|reverse --appearance sphere|cube --columns 1..16",
                "--text-scale 0.5..3 (molecules use Appearance → Molecule Text Scale; item cards default to 1)",
                "3D atom: /avogadro export 3d namespace:molecule --atom 1 --details true|false (one-based atom index)",
                "3D defaults use Atom Settings: 1000 x 640, 24 fps, with details; requires supplied atomic configurations.",
                "Item cards: /avogadro export item [held|namespace:item] --scale 1..8 [--meta 0..32767]",
                "Item defaults: 192 × 192 pixels, scale 2, native item name, no formula, #100010 fill.",
                "--scale sets exact GUI pixels; canvas dimensions must be multiples of it. Long names grow automatic height.",
                "Held items retain their name, metadata and capabilities; --meta is only for registry IDs. Item cards need a solid fill.",
                "MP4 needs FFmpeg: --encoder \"path to ffmpeg\", AVOGADRO_FFMPEG, or ffmpeg on PATH.",
                "Molecule defaults: GIF, 20 fps, one turn, both labels, #DED8C4; speed/direction/shape use Appearance settings.",
                "Each turn needs at least two frames; increase --fps or seconds per turn when needed.",
                "All exports one animated gallery (up to 256 molecules). Each cell must be at least 96 × 96 pixels.",
                "Canvas: 64..4096 pixels per side, at most 8,388,608 pixels; at most 7,200 frames / 600 seconds.",
                "GIF transparency is on/off; MP4 requires a solid matte. Exports never overwrite existing files.",
                "Use /avogadro export status or /avogadro export cancel while exporting.")) {
            sender.sendMessage(new TextComponentString(line));
        }
    }
}
