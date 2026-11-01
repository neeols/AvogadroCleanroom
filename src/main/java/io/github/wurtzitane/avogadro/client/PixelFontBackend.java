package io.github.wurtzitane.avogadro.client;

import com.cleanroommc.kirino.ICS;
import com.cleanroommc.kirino.engine.render.core.shader.ImmediateShaderAccess;
import com.cleanroommc.kirino.gl.shader.Shader;
import com.cleanroommc.kirino.gl.shader.ShaderType;
import com.cleanroommc.kirino.ui.simplegui.SimpleGuiRuntime;
import com.cleanroommc.kirino.ui.simpletext.SimpleTextRuntime;
import com.cleanroommc.kirino.ui.simpletext.atlas.Tex2DArrayGlyphAtlas;
import com.cleanroommc.kirino.ui.simpletext.backend.DefaultTextProducer;
import com.cleanroommc.kirino.ui.simpletext.backend.DefaultTextRenderer;
import com.cleanroommc.kirino.ui.simpletext.text.StyledText;
import com.cleanroommc.kirino.ui.simpletext.text.TextStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.*;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Backend pixel font management and Kirino text rendering integration. */
final class PixelFontBackend {

    /** Active vanilla source text runtime. */
    private static SimpleTextRuntime source;

    /** Active Kirino simple text runtime instance. */
    private static SimpleTextRuntime runtime;

    /** Simple GUI runtime for rendering decorations. */
    private static SimpleGuiRuntime decorations;

    /** Last failed text runtime source instance. */
    private static SimpleTextRuntime failedSource;

    /** Private constructor to prevent instantiation of utility class. */
    private PixelFontBackend() {}

    /**
     * Checks whether pixel font rendering is available.
     *
     * @return {@code true} if available, {@code false} otherwise
     */
    static boolean available() {
        return Minecraft.getMinecraft() != null && ICS.instance().textVanillaAvailable()
                && failedSource != ICS.instance().textVanilla();
    }

    /**
     * Retrieves the physical scale from the pixel grid.
     *
     * @return physical scale value, or {@code 0} if unavailable
     */
    static float physicalScale() {
        PixelGrid grid = PixelGrid.capture();
        return grid == null ? 0 : grid.scale();
    }

    /**
     * Renders styled text using the Kirino pixel text backend.
     *
     * @param original original font renderer
     * @param text     text string to render
     * @param x        x coordinate
     * @param y        y coordinate
     * @param color    text color value
     * @param shadow   whether to render shadow
     * @return {@code true} if successfully drawn, {@code false} otherwise
     */
    static boolean draw(FontRenderer original, String text, float x, float y, int color, boolean shadow) {
        if (!available()) return false;
        SimpleTextRuntime current = ICS.instance().textVanilla();
        if (failedSource == current) return false;
        try (State ignored = new State()) {
            if (source != current) rebuild(current);
            PixelGrid grid = PixelGrid.capture();
            if (grid != null) { x = grid.snapX(x); y = grid.snapY(y); }
            if ((color & 0xFC000000) == 0) color |= 0xFF000000;
            StyledText styled = new StyledText(text, shadow ? StyledText.Syntax.MINECRAFT_SHADOW_ON : StyledText.Syntax.MINECRAFT,
                    TextStyle.DEFAULT.withColor(color));
            String plain = styled.getPureText();
            List<Decoration> underlines = new ArrayList<>(), strikes = new ArrayList<>();
            runtime.begin();
            for (int pass = shadow ? 0 : 1; pass < 2; pass++) {
                boolean shadowPass = pass == 0;
                float cursor = x, line = y;
                for (int index = 0, glyphIndex = 0; index < plain.length(); glyphIndex++) {
                    int cp = plain.codePointAt(index);
                    int count = Character.charCount(cp);
                    String glyph = plain.substring(index, index + count);
                    index += count;
                    if (cp == '\n') { cursor = x; line += original.FONT_HEIGHT; continue; }
                    if (cp == '\r') continue;
                    TextStyle style = styled.get(glyphIndex);
                    boolean bold = (style.hint() & (1 << 25)) != 0;
                    float advance = original.getStringWidth(glyph) + (bold ? 1 : 0);
                    TextStyle ink = style.withHint(style.hint() & ~((1 << 25) | (1 << 27) | (1 << 28) | (1 << 29)));
                    if (shadowPass) ink = ink.withColor(style.color() & 0xFF000000 | (style.color() & 0xFCFCFC) >> 2);
                    StyledText character = new StyledText(glyph, StyledText.Syntax.KIRINO, ink);
                    float offset = shadowPass ? 1 : 0;
                    runtime.appendStyled(character, cursor + offset, line + offset);
                    if (bold) runtime.appendStyled(character, cursor + offset + 1, line + offset);
                    if (!shadowPass && style.underlineEnabled()) addDecoration(underlines, cursor - 1, cursor + advance,
                            line + original.FONT_HEIGHT - 1, style.color(), shadow);
                    if (!shadowPass && style.strikethroughEnabled()) addDecoration(strikes, cursor, cursor + advance,
                            line + original.FONT_HEIGHT / 2 - 1, style.color(), shadow);
                    cursor += advance;
                }
            }
            drawDecorations(underlines);
            drawDecorations(strikes);
            runtime.endDraw();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            failedSource = current;
            LogManager.getLogger("Avogadro Pixel Font").warn("Kirino pixel text is unavailable; using the resource-pack font", failure);
            release();
            return false;
        }
    }

    /**
     * Record representing a text decoration line run (underline or strikethrough).
     *
     * @param left   left x coordinate
     * @param right  right x coordinate
     * @param top    top y coordinate
     * @param color  decoration color
     * @param shadow whether decoration has shadow
     */
    private record Decoration(float left, float right, float top, int color, boolean shadow) {}

    /**
     * Adds or merges a text decoration run into the list.
     *
     * @param runs   list of decorations
     * @param left   left coordinate
     * @param right  right coordinate
     * @param top    top coordinate
     * @param color  color value
     * @param shadow shadow flag
     */
    private static void addDecoration(List<Decoration> runs, float left, float right, float top, int color, boolean shadow) {
        if (!runs.isEmpty()) {
            Decoration previous = runs.getLast();
            if (previous.top() == top && previous.color() == color && previous.shadow() == shadow
                    && previous.right() >= left && previous.left() <= left) {
                runs.set(runs.size() - 1, new Decoration(previous.left(), right, top, color, shadow));
                return;
            }
        }
        runs.add(new Decoration(left, right, top, color, shadow));
    }

    /**
     * Renders accumulated text decorations.
     *
     * @param runs list of decorations to render
     */
    private static void drawDecorations(List<Decoration> runs) {
        for (Decoration run : runs) decorations.append(stream -> {
            if (run.shadow()) stream.rect(run.left() + 1, run.top() + 1, run.right() - run.left(), 1,
                    run.color() & 0xFF000000 | (run.color() & 0xFCFCFC) >> 2);
            stream.rect(run.left(), run.top(), run.right() - run.left(), 1, run.color());
        });
    }

    /**
     * Rebuilds the pixel font runtimes and shader access wrappers.
     *
     * @param current current text runtime source
     */
    private static void rebuild(SimpleTextRuntime current) {
        release();
        ImmediateShaderAccess shaders = new TransformShaders();
        SimpleGuiRuntime underline = new SimpleGuiRuntime(shaders, ICS.instance().dummyVao());
        SimpleGuiRuntime strike = new SimpleGuiRuntime(shaders, ICS.instance().dummyVao());
        try {
            runtime = new SimpleTextRuntime((location, config) -> current.getFont(),
                    context -> new DefaultTextRenderer(context, new Tex2DArrayGlyphAtlas(1024, 1024), shaders, 1024),
                    context -> new DefaultTextProducer(context, context.getConfig().pixelSize(), 12f) {
                        @Override public float calcLineHeight(String text, float fontSize) {
                            return Math.round(super.calcLineHeight(text, fontSize));
                        }
                    },
                    shaders, underline, strike, current.getConfig(), current.getFontRl());
            source = current;
            decorations = underline;
        } catch (RuntimeException | Error failure) {
            underline.close();
            strike.close();
            throw failure;
        }
    }

    /** Releases all pixel font runtime resources and buffers. */
    static void release() {
        if (runtime != null) {
            try { runtime.close(); }
            catch (Exception failure) { LogManager.getLogger("Avogadro Pixel Font").warn("Unable to release pixel-text buffers", failure); }
        }
        runtime = null;
        decorations = null;
        source = null;
    }

    /** Shader access implementation applying custom coordinate transformations and SDF enhancements. */
    private static final class TransformShaders extends ImmediateShaderAccess {
        @Override public Shader makeShader(ResourceLocation location) {
            String path = location.getPath();
            if (!path.endsWith(".vert") && !path.endsWith(".frag")) return super.makeShader(location);
            try (var resource = Minecraft.getMinecraft().getResourceManager().getResource(location)) {
                String code = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .replaceFirst("(?m)(#version\\s+\\d+)\\s+core", "$1 compatibility");
                if (path.endsWith(".vert")) {
                    if (!code.matches("(?s).*void\\s+main\\s*\\(\\s*\\).*"))
                        throw new IllegalStateException("Missing Kirino vertex entry point: " + location);
                    code = code.replaceFirst("void\\s+main\\s*\\(\\s*\\)", "void avogadroVertex()");
                    boolean font = path.endsWith("simpletext_font.vert");
                    if (font) code += "\nout vec2 avogadroGuiPosition;\n";
                    code += "\nvoid main() { avogadroVertex(); vec2 p = vec2((gl_Position.x + 1.0) * scaledRes.x * 0.5, "
                            + "(1.0 - gl_Position.y) * scaledRes.y * 0.5); "
                            + (font ? "avogadroGuiPosition = p; " : "")
                            + "gl_Position = gl_ModelViewProjectionMatrix * vec4(p, 0.0, 1.0); }\n";
                } else if (path.endsWith("simpletext_font.frag")) {
                    code = code.replace("void main()", "in vec2 avogadroGuiPosition;\nvoid main()")
                            .replace("float w = max(fwidth(dist), 0.001);", """
                                    vec2 dx = dFdx(avogadroGuiPosition), dy = dFdy(avogadroGuiPosition);
                                    vec2 pitch = 1.0 / max(vec2(abs(dx.x), abs(dy.y)), vec2(1e-6));
                                    bool aligned = abs(dx.y) + abs(dy.x) < 1e-5
                                        && all(greaterThanEqual(pitch, vec2(0.9999)))
                                        && all(lessThan(abs(pitch - round(pitch)), vec2(0.001)));
                                    float w = max(fwidth(dist) * 0.25, 0.0001);
                                    """)
                            .replace("float fill = smoothstep(fillEdge - w, fillEdge + w, dist);",
                                    "float fill = aligned ? step(fillEdge, dist) : smoothstep(fillEdge - w, fillEdge + w, dist);")
                            .replace("smoothstep(outerEdge - w, outerEdge + w, dist) : fill;",
                                    "(aligned ? step(outerEdge, dist) : smoothstep(outerEdge - w, outerEdge + w, dist)) : fill;");
                }
                Constructor<Shader> constructor = Shader.class.getDeclaredConstructor(String.class, String.class, ShaderType.class);
                constructor.setAccessible(true);
                return constructor.newInstance(code, "avogadro:" + location, ShaderType.parse(path.endsWith(".vert") ? "vert" : "frag"));
            } catch (ReflectiveOperationException | java.io.IOException failure) {
                throw new IllegalStateException("Cannot prepare transformed Kirino shader " + location, failure);
            }
        }
    }

    /** AutoCloseable state wrapper capturing and restoring OpenGL state during font rendering. */
    private static final class State implements AutoCloseable {
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        private final int array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        private final int indirect = GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        private final int storage = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final int sampler = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 6);
        private final int[] indices = {0, 1, 2, 4};
        private final int[] bindings = new int[indices.length];
        private final long[] starts = new long[indices.length], sizes = new long[indices.length];
        private final int atlasTexture;

        /** Constructs a new state capture instance. */
        private State() {
            for (int i = 0; i < indices.length; i++) {
                bindings[i] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, indices[i]);
                starts[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, indices[i]);
                sizes[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, indices[i]);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 6);
            atlasTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            GL13.glActiveTexture(activeTexture);
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL33.glBindSampler(6, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_LSB_FIRST, 0);
        }

        /** Restores previously captured OpenGL states upon closing. */
        @Override public void close() {
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirect);
            for (int i = 0; i < indices.length; i++) {
                if (bindings[i] != 0 && sizes[i] > 0)
                    GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, indices[i], bindings[i], starts[i], sizes[i]);
                else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, indices[i], bindings[i]);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, storage);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 6);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, atlasTexture);
            GL33.glBindSampler(6, sampler);
            GL13.glActiveTexture(activeTexture);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }
}