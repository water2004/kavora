/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.settings;

import org.edtp.kavora.logic.AtmosphereDimension;
import org.edtp.kavora.texture.BlockTextureManager;
import org.edtp.kavora.texture.LavaEmission;

import java.util.List;

/** Registers each dimension's editable lava emission profile with the visual settings transaction. */
final class LavaLightOptions {
    private LavaLightOptions() {}

    static void add(List<KavoraSetting<?>> settings, BlockTextureManager textures) {
        for (var dimension : AtmosphereDimension.values()) {
            var editor = new Editor(textures, dimension);
            var defaults = LavaEmission.defaults(dimension);
            var group = SettingGroup.lava(dimension);
            String id = "lava." + dimension.id() + ".";
            String label = "kavora.effect.lava.";
            settings.add(KavoraSetting.ofFloat(id + "warmth", group, label + "warmth", label + "warmth.tip",
                    -1, 1, defaults.warmth(), () -> (double) editor.profile.warmth(), value -> {
                        var p = editor.profile;
                        editor.set(new LavaEmission.Profile(value.floatValue(), p.hue(), p.saturation(), p.intensity()));
                    }));
            settings.add(KavoraSetting.ofFloat(id + "hue", group, label + "hue", label + "hue.tip",
                    -180, 180, defaults.hue(), () -> (double) editor.profile.hue(), value -> {
                        var p = editor.profile;
                        editor.set(new LavaEmission.Profile(p.warmth(), value.floatValue(), p.saturation(), p.intensity()));
                    }));
            settings.add(KavoraSetting.ofFloat(id + "saturation", group, label + "saturation", label + "saturation.tip",
                    0, 2, defaults.saturation(), () -> (double) editor.profile.saturation(), value -> {
                        var p = editor.profile;
                        editor.set(new LavaEmission.Profile(p.warmth(), p.hue(), value.floatValue(), p.intensity()));
                    }));
            settings.add(KavoraSetting.ofFloat(id + "intensity", group, label + "intensity", label + "intensity.tip",
                    0, 2, defaults.intensity(), () -> (double) editor.profile.intensity(), value -> {
                        var p = editor.profile;
                        editor.set(new LavaEmission.Profile(p.warmth(), p.hue(), p.saturation(), value.floatValue()));
                    }));
        }
    }

    private static final class Editor {
        private final BlockTextureManager textures;
        private final AtmosphereDimension dimension;
        private LavaEmission.Profile profile;

        Editor(BlockTextureManager textures, AtmosphereDimension dimension) {
            this.textures = textures;
            this.dimension = dimension;
            profile = LavaEmission.defaults(dimension);
        }

        void set(LavaEmission.Profile profile) {
            this.profile = profile;
            textures.setLavaEmission(dimension, profile);
        }
    }
}
