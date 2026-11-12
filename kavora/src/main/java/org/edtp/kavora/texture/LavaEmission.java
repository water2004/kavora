/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.texture;

import org.edtp.kavora.logic.AtmosphereDimension;
import org.edtp.kavora.sdk.TextureFormat;
import org.edtp.kavora.sdk.TexturePatch;
import org.edtp.kavora.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.edtp.kavora.mixin.SpriteAtlasAccessor;

/** Colors the two lava regions of the shared terrain emission atlas. Render thread only. */
public final class LavaEmission {
    private static final Identifier STILL = Identifier.withDefaultNamespace("block/lava_still");
    private static final Identifier FLOW = Identifier.withDefaultNamespace("block/lava_flow");
    private static final Profile MATERIAL = new Profile(0f, 0f, 1f, 1f);
    private static final Profile NETHER = new Profile(-0.65f, 10f, 0.82f, 1f);

    /** Hue is a relative angle in degrees. Intensity scales emitted luminance. */
    public record Profile(float warmth, float hue, float saturation, float intensity) {}

    private final Map<AtmosphereDimension, Profile> profiles = new EnumMap<>(AtmosphereDimension.class);
    private final Map<Identifier, Region> regions = new HashMap<>(2);
    private Profile active = MATERIAL;
    private Grade grade = new Grade(MATERIAL);

    public static Profile defaults(AtmosphereDimension dimension) {
        return dimension == AtmosphereDimension.NETHER ? NETHER : MATERIAL;
    }

    void setProfile(AtmosphereDimension dimension, Profile profile) {
        profiles.put(dimension, profile);
    }

    void select(AtmosphereDimension dimension) {
        Profile profile = dimension == null ? MATERIAL : profiles.getOrDefault(dimension, defaults(dimension));
        if (active.equals(profile)) return;
        active = profile;
        grade = new Grade(profile);
        invalidate();
    }

    void install(TextureAtlas atlas, byte[] pixels, int atlasWidth) {
        Map<Identifier, TextureAtlasSprite> sprites = ((SpriteAtlasAccessor) atlas).getSprites();
        var target = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN);
        install(sprites.get(STILL), pixels, atlasWidth, target);
        install(sprites.get(FLOW), pixels, atlasWidth, target);
    }

    private void install(TextureAtlasSprite sprite, byte[] atlas, int atlasWidth, ByteBuffer target) {
        if (sprite == null) return;
        int x = sprite.getX(), y = sprite.getY();
        int width = sprite.contents().width() + 2 * (SpriteAtlasContent.x(sprite) - x);
        int height = sprite.contents().height() + 2 * (SpriteAtlasContent.y(sprite) - y);
        Region region = new Region(x, y, width, height);
        regions.put(sprite.contents().name(), region);
        int pitch = atlasWidth * 4;
        int start = y * pitch + x * 4;
        for (int row = 0; row < height; row++) {
            System.arraycopy(atlas, start + row * pitch, region.source, row * region.rowBytes, region.rowBytes);
        }
        if (!grade.identity) region.write(grade, target, start, pitch);
    }

    boolean hasDirtyRegions() {
        for (Region region : regions.values()) {
            if (region.dirty) return true;
        }
        return false;
    }

    void applyAnimation(Identifier id, TexturePatch patch) {
        Region region = regions.get(id);
        if (region == null) return;
        patch.pixelData.get(0, region.source);
        if (!grade.identity) region.write(grade, patch.pixelData, 0, patch.rowPitch);
        region.dirty = false;
    }

    void appendDirtyPatches(List<TexturePatch> patches, List<ByteBuffer> ownedBuffers) {
        for (Region region : regions.values()) {
            if (!region.dirty) continue;
            TexturePatch patch = new TexturePatch();
            patch.dstX = region.x;
            patch.dstY = region.y;
            patch.width = region.width;
            patch.height = region.height;
            patch.rowPitch = region.rowBytes;
            patch.format = TextureFormat.RGBA8_UNORM;
            patch.pixelData = DirectBufferPool.acquire(region.source.length);
            ownedBuffers.add(patch.pixelData);
            region.write(grade, patch.pixelData, 0, patch.rowPitch);
            patch.pixelData.limit(region.source.length);
            patches.add(patch);
            region.dirty = false;
        }
    }

    void invalidate() {
        for (Region region : regions.values()) region.dirty = true;
    }

    void clearAtlas() { regions.clear(); }

    private static final class Region {
        final int x, y, width, height, rowBytes;
        final byte[] source;
        boolean dirty;

        Region(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            rowBytes = width * 4;
            source = new byte[rowBytes * height];
        }

        void write(Grade grade, ByteBuffer target, int offset, int pitch) {
            for (int row = 0; row < height; row++) {
                int src = row * rowBytes;
                int dst = offset + row * pitch;
                if (grade.identity) {
                    target.put(dst, source, src, rowBytes);
                    continue;
                }
                for (int end = src + rowBytes; src < end; src += 4, dst += 4) {
                    int rgb = grade.rgb(source[src] & 255, source[src + 1] & 255, source[src + 2] & 255);
                    target.putInt(dst, rgb | (source[src + 3] & 255) << 24);
                }
            }
        }
    }

    static final class Grade {
        private static final int ENCODE_STEPS = 16384;
        private static final float RED_WEIGHT = 0.2126f;
        private static final float GREEN_WEIGHT = 0.7152f;
        private static final float BLUE_WEIGHT = 0.0722f;
        private static final float CR_SCALE = 2f * (1f - RED_WEIGHT);
        private static final float CB_SCALE = 2f * (1f - BLUE_WEIGHT);
        private static final double GAMMA = 2.2;
        private static final int[] ENCODED = buildEncodingTable();

        final boolean identity;
        private final float[] fromRed, fromGreen, fromBlue;

        Grade(Profile profile) {
            identity = profile.warmth == 0f && profile.hue == 0f
                    && profile.saturation == 1f && profile.intensity == 1f;
            double angle = Math.toRadians(profile.hue);
            float cos = (float) Math.cos(angle) * profile.saturation;
            float sin = (float) Math.sin(angle) * profile.saturation;
            fromRed = identity ? null : contribution(1f, 0f, 0f, profile, cos, sin);
            fromGreen = identity ? null : contribution(0f, 1f, 0f, profile, cos, sin);
            fromBlue = identity ? null : contribution(0f, 0f, 1f, profile, cos, sin);
        }

        int rgb(int r, int g, int b) {
            if (identity) return r | g << 8 | b << 16;
            int ri = r * 3, gi = g * 3, bi = b * 3;
            float red = fromRed[ri] + fromGreen[gi] + fromBlue[bi];
            float green = fromRed[ri + 1] + fromGreen[gi + 1] + fromBlue[bi + 1];
            float blue = fromRed[ri + 2] + fromGreen[gi + 2] + fromBlue[bi + 2];
            float min = Math.min(red, Math.min(green, blue));
            float max = Math.max(red, Math.max(green, blue));
            if (min < 0f || max > 1f) {
                // Reduce chroma toward gray to fit the emission atlas while retaining luminance.
                float y = Math.clamp(luminance(red, green, blue), 0f, 1f);
                float scale = 1f;
                if (min < 0f) scale = y / (y - min);
                if (max > 1f) scale = Math.min(scale, (1f - y) / (max - y));
                red = y + (red - y) * scale;
                green = y + (green - y) * scale;
                blue = y + (blue - y) * scale;
            }
            return encode(red) | encode(green) << 8 | encode(blue) << 16;
        }

        private static float[] contribution(float red, float green, float blue, Profile profile, float cos, float sin) {
            // Work on linear RGB chroma so hue and saturation are independent of emitted luminance.
            float y = luminance(red, green, blue);
            float cb = (blue - y) / CB_SCALE - 0.23f * profile.warmth * y;
            float cr = (red - y) / CR_SCALE + 0.50f * profile.warmth * y;
            float outRed = y + CR_SCALE * (cb * sin + cr * cos);
            float outBlue = y + CB_SCALE * (cb * cos - cr * sin);
            float outGreen = (y - RED_WEIGHT * outRed - BLUE_WEIGHT * outBlue) / GREEN_WEIGHT;
            float[] values = new float[256 * 3];
            for (int i = 0; i < 256; i++) {
                float energy = (float) Math.pow(i / 255.0, GAMMA) * profile.intensity;
                values[i * 3] = outRed * energy;
                values[i * 3 + 1] = outGreen * energy;
                values[i * 3 + 2] = outBlue * energy;
            }
            return values;
        }

        private static float luminance(float red, float green, float blue) {
            return RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue;
        }

        private static int[] buildEncodingTable() {
            int[] table = new int[ENCODE_STEPS + 1];
            for (int i = 0; i < table.length; i++) {
                table[i] = (int) Math.round(255.0 * Math.pow((double) i / ENCODE_STEPS, 1.0 / GAMMA));
            }
            return table;
        }

        private static int encode(float linear) {
            return ENCODED[Math.clamp(Math.round(linear * ENCODE_STEPS), 0, ENCODE_STEPS)];
        }
    }
}
