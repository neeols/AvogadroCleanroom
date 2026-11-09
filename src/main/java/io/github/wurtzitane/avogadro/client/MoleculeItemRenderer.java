package io.github.wurtzitane.avogadro.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.client.compat.DemonicaRenderScope;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.AtomElement;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Custom renderer for molecule item stacks, handling native item coordinates, transformation matrices, and
 * immediate-mode OpenGL state setup without triggering GUI clipping or depth-clear paths.
 */
@Mod.EventBusSubscriber(modid = Avogadro.MODID, value = Side.CLIENT)
public final class MoleculeItemRenderer extends TileEntityItemStackRenderer {

    /** Singleton instance of the item stack renderer. */
    private static MoleculeItemRenderer instance;

    /** Map caching resolved molecule definitions per item stack ID for the current frame. */
    private final Map<String, MoleculeDef> frameDefinitions = new HashMap<>();

    /** LRU cache storing calculated {@link ItemPose} transformations for molecule structures. */
    private final LinkedHashMap<MoleculeStructure, ItemPose> poses = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MoleculeStructure, ItemPose> eldest) {
            return size() > 256;
        }
    };

    /** Global counter tracking frame updates. */
    private static long frame;

    /** Cached rotation yaw for the current frame. */
    private static float frameYaw;

    /** Internal revision tracking counter for checking cache invalidation against {@link MoleculeStore}. */
    private long revision = Long.MIN_VALUE;

    /** Fallback structure used when a requested molecule definition is missing or unavailable. */
    private static final MoleculeStructure MISSING;

    static {
        var builder = MoleculeStructure.builder("avogadro:unavailable", "");
        builder.addAtom(new AtomElement("?", 0xFFFF44CC, 0.4f), 0, 0, 0);
        MISSING = builder.build();
    }

    /**
     * Registers model resource locations and assigns the custom tile entity item stack renderer during the model
     * registry event.
     *
     * @param event the forge model registry event
     */
    @SubscribeEvent
    public static void models(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(MoleculeItems.CATEGORY_ICON, 0,
                new ModelResourceLocation("avogadro:molecules_icon", "inventory"));
        ModelLoader.setCustomModelResourceLocation(MoleculeItems.MODEL, 0,
                new ModelResourceLocation("avogadro:molecule", "inventory"));
        instance = new MoleculeItemRenderer();
        MoleculeItems.MODEL.setTileEntityItemStackRenderer(instance);
    }

    /** Clears cached transformation poses and frame definitions. */
    static void clearBounds() {
        if (instance == null)
            return;
        instance.poses.clear();
        instance.frameDefinitions.clear();
    }

    /** Updates the global frame counter and rotation yaw for the new rendering frame. */
    static void beginFrame() {
        frame++;
        frameYaw = MoleculeRenderer.rotationAngle();
        if (instance != null)
            instance.frameDefinitions.clear();
    }

    /**
     * Renders a custom item stack using modern transformation matrices and raw OpenGL calls.
     *
     * @param stack the item stack to render
     * @param partialTicks tick delta time
     */
    @Override
    public void renderByItem(ItemStack stack, float partialTicks) {
        var view = MoleculeStore.view();
        if (revision != view.revision()) {
            revision = view.revision();
            poses.clear();
            frameDefinitions.clear();
        }
        String id = stack.getItem() == MoleculeItems.MODEL && stack.hasTagCompound()
                ? stack.getTagCompound().getString(MoleculeItems.ID_TAG)
                : "";
        MoleculeDef definition = frameDefinitions.get(id);
        if (definition == null && !frameDefinitions.containsKey(id)) {
            definition = MoleculeItems.resolve(stack, view);
            // Keep the short-lived identity cache bounded even for unusual custom item batches.
            if (frameDefinitions.size() >= 256)
                frameDefinitions.clear();
            frameDefinitions.put(id, definition);
        }
        MoleculeStructure molecule = definition == null ? MISSING : definition.structure();
        boolean cubic = definition == null || Config.CUBIC_ATOMS.get();
        ItemPose pose = poses.computeIfAbsent(molecule, ItemPose::new);
        Matrix4f matrix = pose.transform(molecule, cubic, frame, frameYaw);

        // Native item callers can have different transforms and depth/lighting state
        // even for adjacent copies. Keep each draw immediate and save only the state
        // we change; the geometry cache batches compatible primitives within the item.
        try (var scope = DemonicaRenderScope.open()) {
            GL11.glPushAttrib(
                    GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT);
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GL11.glShadeModel(GL11.GL_SMOOTH);
                MoleculeRenderer.drawTransformed(molecule, matrix, cubic);
            } finally {
                GL11.glPopAttrib();
            }
        }
    }

    /**
     * Caches and computes bounding radius scales and transformation matrices for rendering an item stack.
     */
    private static final class ItemPose {
        private final Matrix4f matrix = new Matrix4f();
        private final float sphere, cube;
        private long frame = Long.MIN_VALUE;
        private boolean cubic;

        /**
         * Calculates spherical and cubic outer bounds for a given molecule structure.
         *
         * @param molecule the structure to analyze
         */
        private ItemPose(MoleculeStructure molecule) {
            float sphere = 0, cube = 0;
            for (var atom : molecule.atoms()) {
                float x = atom.x() - molecule.centerX(), y = atom.y() - molecule.centerY(),
                        z = atom.z() - molecule.centerZ();
                float distance = (float) Math.sqrt(x * x + y * y + z * z);
                sphere = Math.max(sphere, distance + atom.element().radius() * 0.9f);
                cube = Math.max(cube, distance + atom.element().radius() * 1.56f);
            }
            this.sphere = sphere;
            this.cube = cube;
        }

        /**
         * Computes and returns the 4x4 matrix transformation for rendering the item.
         *
         * @param molecule the structure being rendered
         * @param cubic whether atoms are rendered as cubes or spheres
         * @param frame the current frame index
         * @param yaw the current rotation angle
         * @return updated matrix transformation
         */
        private Matrix4f transform(MoleculeStructure molecule, boolean cubic, long frame, float yaw) {
            if (this.frame != frame || this.cubic != cubic) {
                float scale = 0.44f / Math.max(cubic ? cube : sphere, 0.001f);
                matrix.translation(0.5f, 0.5f, 0.5f).rotateX((float) Math.toRadians(15))
                        .rotateY((float) Math.toRadians(yaw)).scale(scale)
                        .translate(-molecule.centerX(), -molecule.centerY(), -molecule.centerZ());
                this.frame = frame;
                this.cubic = cubic;
            }
            return matrix;
        }
    }
}
