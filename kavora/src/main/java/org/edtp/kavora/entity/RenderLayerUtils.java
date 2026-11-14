/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.entity;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.edtp.kavora.sdk.MaterialInfo.GlintMode;

/**
 * Reads the layer-level facts the capture path needs from a {@link RenderType}.
 *
 * <p>A renderer requests its {@code VertexConsumer} by layer,
 * {@code provider.getBuffer(RenderLayer.getEntityCutout(textureId))}, so the
 * layer is where geometry and material meet and the one place material
 * identity is read. Its {@code RenderSetup} state, reached through the access
 * widener, gives the texture the layer is submitted under, if any, the
 * {@link SurfaceMode} Remix shades with, and whether the geometry is streamed
 * afresh every frame; its draw mode gives the {@link Topology} and whether an
 * untextured layer carries geometry worth submitting at all.
 */
final class RenderLayerUtils {

    /** Material behavior derived from Minecraft render phases and block-atlas optics. */
    enum SurfaceMode {
        OPAQUE,
        CUTOUT,
        ARMOR_TRIM,
        TRANSLUCENT,
        EMISSIVE,
        /** Full-bright alpha-tested coverage, retaining the opaque core of a beam. */
        EMISSIVE_CUTOUT,
        /** Refracting clear glass on the block atlas. */
        GLASS,
        STAINED_GLASS_BLOCK,
        END_PORTAL,
        END_GATEWAY,
        ITEM_GLINT_CUTOUT(CUTOUT, GlintMode.ITEM),
        ITEM_GLINT_TRANSLUCENT(TRANSLUCENT, GlintMode.ITEM),
        ENTITY_GLINT_CUTOUT(CUTOUT, GlintMode.ENTITY),
        ENTITY_GLINT_OPAQUE(OPAQUE, GlintMode.ENTITY),
        ENTITY_GLINT_TRANSLUCENT(TRANSLUCENT, GlintMode.ENTITY),
        ARMOR_GLINT_CUTOUT(CUTOUT, GlintMode.ARMOR),
        ARMOR_GLINT_TRIM(ARMOR_TRIM, GlintMode.ARMOR);

        private final SurfaceMode base;
        private final GlintMode glint;

        SurfaceMode() { this.base = this; this.glint = null; }

        SurfaceMode(SurfaceMode base, GlintMode glint) {
            this.base = base;
            this.glint = glint;
        }

        SurfaceMode base() { return base; }
        GlintMode glint() { return glint; }

        SurfaceMode withGlint(GlintMode mode) {
            if (mode == null || glint == mode) return this;
            return switch (base) {
                case OPAQUE -> mode == GlintMode.ENTITY ? ENTITY_GLINT_OPAQUE : this;
                case CUTOUT -> switch (mode) {
                    case ITEM -> ITEM_GLINT_CUTOUT;
                    case ENTITY -> ENTITY_GLINT_CUTOUT;
                    case ARMOR -> ARMOR_GLINT_CUTOUT;
                };
                case TRANSLUCENT -> switch (mode) {
                    case ITEM -> ITEM_GLINT_TRANSLUCENT;
                    case ENTITY -> ENTITY_GLINT_TRANSLUCENT;
                    case ARMOR -> this;
                };
                case ARMOR_TRIM -> mode == GlintMode.ARMOR ? ARMOR_GLINT_TRIM : this;
                default -> this;
            };
        }

        boolean isEmissive() { return this == EMISSIVE || this == EMISSIVE_CUTOUT; }

        int endPortalLayers() {
            return switch (this) {
                case END_PORTAL -> 15;
                case END_GATEWAY -> 16;
                default -> 0;
            };
        }
    }

    /** How a layer's captured vertex stream turns into Remix triangles. */
    enum Topology {
        QUADS,
        TRIANGLE_STRIP
    }

    /**
     * Material identity for layers that draw without a texture. They have no
     * {@link Identifier} of their own, but the submitter keys materials and mesh
     * pools by one, so they share this.
     */
    static final Identifier UNTEXTURED_LAYER_ID =
            Identifier.fromNamespaceAndPath("kavora", "untextured_entity_layer");

    private RenderLayerUtils() {}

    /**
     * Cache of RenderLayer -> capturable texture. Most RenderLayer instances are reused,
     * but weak keys avoid retaining dynamic layers created by mods or skin features.
     */
    private static final Map<RenderType, Optional<Identifier>> capturableTextureCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cache of RenderLayer -> streamed-per-frame verdict, weak-keyed like {@link #capturableTextureCache}. */
    private static final Map<RenderType, Boolean> streamedPerFrameCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<RenderType, SurfaceMode> surfaceModeCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<RenderPipeline, SurfaceMode> PIPELINE_SURFACE_MODES = pipelineSurfaceModes();
    private static final Set<String> ARMOR_TRIM_LAYER_NAMES = Set.of("armor_trim", "armor_trim_decal");

    private static Map<RenderPipeline, SurfaceMode> pipelineSurfaceModes() {
        Map<RenderPipeline, SurfaceMode> modes = new IdentityHashMap<>();
        modes.put(RenderPipelines.END_PORTAL, SurfaceMode.END_PORTAL);
        modes.put(RenderPipelines.END_GATEWAY, SurfaceMode.END_GATEWAY);
        modes.put(RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT, SurfaceMode.ARMOR_GLINT_CUTOUT);
        modes.put(RenderPipelines.ENTITY_SOLID_GLINT, SurfaceMode.ENTITY_GLINT_OPAQUE);
        modes.put(RenderPipelines.ITEM_CUTOUT_GLINT, SurfaceMode.ITEM_GLINT_CUTOUT);
        modes.put(RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL, SurfaceMode.ITEM_GLINT_CUTOUT);
        modes.put(RenderPipelines.ITEM_TRANSLUCENT_GLINT, SurfaceMode.ITEM_GLINT_TRANSLUCENT);
        modes.put(RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL, SurfaceMode.ITEM_GLINT_TRANSLUCENT);
        return Collections.unmodifiableMap(modes);
    }

    /**
     * Returns the texture a captured layer is submitted under, or empty when Remix has
     * nothing to draw for the layer. Results are cached by layer identity.
     *
     * <p>Empty covers two cases. Untextured layers (shadow, debug, lines) carry no
     * material identity at all. Layers that pass the depth test unconditionally, the
     * see-through text of a name tag, are drawn over the whole scene in vanilla; no
     * placement in a ray-traced scene reproduces that, and vanilla draws the
     * depth-tested copy of the same text beside them, so their quads would only double
     * it.
     *
     * @param layer the render layer (typically from an entity renderer)
     * @return the texture identifier to capture under, or empty to skip the layer
     */
    static Optional<Identifier> capturableTexture(RenderType layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    private static Optional<Identifier> capturableTextureUncached(RenderType layer) {
        // Equipment and shield capture transfer these depth-equal overlays to the shaded surface.
        if (layer.pipeline() == RenderPipelines.GLINT) return Optional.empty();
        if (layer.pipeline().getDepthStencilState() == null
                || layer.pipeline().getDepthStencilState().depthTest() == CompareOp.ALWAYS_PASS) {
            return Optional.empty();
        }
        return extractTexture(layer);
    }

    private static Optional<Identifier> extractTexture(RenderType layer) {
        var binding = layer.state.textures.get("Sampler0");
        return binding == null ? Optional.empty() : Optional.of(binding.location());
    }
    /**
     * How to read the layer's vertices, or empty when it draws primitives that
     * do not become triangles, such as lines.
     */
    static Optional<Topology> topology(RenderType layer) {
        return switch (layer.primitiveTopology()) {
            case QUADS -> Optional.of(Topology.QUADS);
            case TRIANGLE_STRIP -> Optional.of(Topology.TRIANGLE_STRIP);
            default -> Optional.empty();
        };
    }

    /**
     * Whether a layer that draws without a texture still carries geometry worth
     * submitting to Remix. In 1.21.1 the lead between a mob and its holder is the
     * only such layer.
     *
     * <p>Two conditions, both about what the layer can actually shade with.
     * Vertex colour is the only surface detail such a layer has, so a layer
     * without it carries nothing to submit; that excludes the water mask, which
     * exists to write depth. And of the untextured layers that do have colour,
     * the triangle strips are world geometry while the quads are screen
     * furniture such as the nameplate background, which should not become
     * scene geometry that lights and casts shadows.
     */
    static boolean isCapturableUntexturedLayer(RenderType layer) {
        // Only layers with no texture at all qualify: a textured layer that
        // capturableTexture declined must not re-enter capture through here.
        return extractTexture(layer).isEmpty()
                && topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent()
                && layer.format().contains("Color");
    }

    /**
     * Whether the layer's vertex content differs every frame, so no mesh built from
     * it can be shared across frames and none should be pooled.
     *
     * <p>Vanilla streams two kinds of geometry through an entity provider whose
     * vertices never repeat: triangle strips, which it computes from live positions
     * (the lead between a mob and its holder), and the beacon beam, whose texture
     * coordinate scrolls with world time. Everything else it draws is rigid: a
     * model part or baked quad whose pose changes while its local vertices do not.
     */
    static boolean isStreamedPerFrame(RenderType layer) {
        return streamedPerFrameCache.computeIfAbsent(layer, RenderLayerUtils::isStreamedPerFrameUncached);
    }

    private static boolean isStreamedPerFrameUncached(RenderType layer) {
        boolean beaconBeamProgram = isBeaconBeam(layer);
        return classifyStreamedPerFrame(topology(layer).orElse(Topology.QUADS), beaconBeamProgram);
    }

    private static boolean isBeaconBeam(RenderType layer) {
        return layer.pipeline().getShaders().values().stream().anyMatch(shader ->
                shader.getNamespace().equals("minecraft") && shader.getPath().equals("core/rendertype_beacon_beam"));
    }

    static boolean classifyStreamedPerFrame(Topology topology, boolean beaconBeamProgram) {
        return topology == Topology.TRIANGLE_STRIP || beaconBeamProgram;
    }

    /**
     * Converts Minecraft's render-phase state into the material behavior that
     * Remix needs. This deliberately inspects phases rather than render-layer
     * names so mod-created layers using the standard phases behave correctly.
     *
     * <p>The block-breaking crack multiplies the surface under it, a blend Remix
     * has no material for. Its texels are either fully opaque grey or fully
     * transparent, so alpha blending them over the surface is the closest surface
     * behaviour and it is classified as translucent.
     */
    static SurfaceMode surfaceMode(RenderType layer) {
        return surfaceModeCache.computeIfAbsent(layer, RenderLayerUtils::surfaceModeUncached);
    }

    private static SurfaceMode surfaceModeUncached(RenderType layer) {
        var pipeline = layer.pipeline();
        SurfaceMode pipelineMode = PIPELINE_SURFACE_MODES.get(pipeline);
        if (pipelineMode != null) return pipelineMode;
        // The trim and the armor base can share a pipeline. The trim layer alone
        // binds the palette-resolved pattern, including equipment overrides.
        if (ARMOR_TRIM_LAYER_NAMES.contains(layer.name)) return SurfaceMode.ARMOR_TRIM;
        // The core and halo share an unlit shader but have different coverage states.
        if (isBeaconBeam(layer)) return layer.hasBlending() ? SurfaceMode.EMISSIVE : SurfaceMode.EMISSIVE_CUTOUT;
        boolean emissive = pipeline == RenderPipelines.EYES
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
                || pipeline.getShaderDefines().flags().contains("EMISSIVE");
        boolean additive = pipeline.getColorTargetStates().stream()
                .anyMatch(target -> target.blendFunction().filter(BlendFunction.ADDITIVE::equals).isPresent());
        boolean text = pipeline.getShaders().values().stream()
                .anyMatch(shader -> shader.getPath().contains("text"));
        return classifySurfaceMode(emissive, additive, layer.hasBlending(),
                pipeline == RenderPipelines.ENTITY_SOLID, text);
    }
    /**
     * The text programs blend in vanilla only so glyph edges antialias; what they draw,
     * glyphs, map pixels and map icons, is coverage whose texels are either there or
     * not. Cutout keeps those quads out of the blended path, so text and maps are opaque
     * hits that cast shadows and appear in reflections instead of ghosted decals.
     */
    static SurfaceMode classifySurfaceMode(boolean emissiveProgram, boolean additiveTransparency,
                                           boolean translucentTransparency, boolean solidProgram,
                                           boolean textProgram) {
        if (emissiveProgram || additiveTransparency) {
            return SurfaceMode.EMISSIVE;
        }
        if (textProgram) {
            return SurfaceMode.CUTOUT;
        }
        if (translucentTransparency) {
            return SurfaceMode.TRANSLUCENT;
        }
        if (solidProgram) {
            return SurfaceMode.OPAQUE;
        }
        return SurfaceMode.CUTOUT;
    }
}
