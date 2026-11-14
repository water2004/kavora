/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.settings;

import org.edtp.kavora.logic.ConfLayerDriver;
import org.edtp.kavora.logic.AtmosphereDimension;
import org.edtp.kavora.sdk.RemixAPI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Editable optical properties, resolved by the runtime's existing option layers. */
final class FogProfileOptions {
    private record Parameter(String id, String key, double min, double max, boolean color) {
        Parameter(String id, String key, double min, double max) { this(id, key, min, max, false); }
        String runtimeKey() { return "rtx.volumetrics." + key; }
        String componentId(int channel) { return color ? id + "." + RGB.get(channel) : id; }
    }

    private static final List<String> RGB = List.of("red", "green", "blue");
    private static final Parameter MIST_DENSITY = new Parameter("density", "groundMistExtinctionPerMeter", 0, 0.25);
    private static final List<Parameter> OPTICS = List.of(
            new Parameter("range", "transmittanceMeasurementDistanceMeters", 1, 2000),
            new Parameter("anisotropy", "anisotropy", -0.95, 0.95),
            new Parameter("transmittance", "transmittanceColor", 0, 1, true),
            new Parameter("scatteringAlbedo", "singleScatteringAlbedo", 0, 1, true));
    private static final List<Parameter> HEIGHT_AND_AMBIENT = List.of(
            new Parameter("ambientRadiance", "fogAmbientRadiance", 0, 1, true),
            new Parameter("baseHeight", "heightFogBaseHeightMeters", -64, 320),
            new Parameter("heightFalloff", "heightFogFalloffMeters", 0, 128),
            new Parameter("lowerHeightFalloff", "heightFogLowerFalloffMeters", 0, 128));

    private record Value(Parameter parameter, float[] defaults, float[] current) {
        Value(Parameter parameter, float[] defaults) { this(parameter, defaults, defaults.clone()); }

        String text() {
            if (!parameter.color()) return Float.toString(current[0]);
            return current[0] + ", " + current[1] + ", " + current[2];
        }
    }

    private final ConfLayerDriver driver;
    private final Map<AtmosphereDimension, List<Value>> profiles = new EnumMap<>(AtmosphereDimension.class);
    private final List<Value> underwater;
    private boolean changed;

    static boolean contains(String id) {
        return id.startsWith("fog.overworld.") || id.startsWith("fog.nether.") || id.startsWith("fog.end.")
                || id.startsWith("water.fog.") || id.equals("mist.density");
    }

    FogProfileOptions(List<KavoraSetting<?>> settings, ConfLayerDriver driver, RemixAPI api) {
        this.driver = driver;
        var dimensionParameters = new ArrayList<>(OPTICS);
        dimensionParameters.addAll(HEIGHT_AND_AMBIENT);
        Map<Parameter, float[]> runtimeDefaults = new LinkedHashMap<>();
        for (var parameter : dimensionParameters) {
            runtimeDefaults.put(parameter, parse(parameter, api.getConfigVariableResolved(parameter.runtimeKey())));
        }
        for (var scope : AtmosphereDimension.values()) {
            var authored = driver.fogProfileDefaults(scope);
            var values = new ArrayList<Value>();
            for (var parameter : dimensionParameters) {
                Value value = authoredValue(parameter, authored, runtimeDefaults);
                values.add(value);
                addControls(settings, SettingGroup.fog(scope), "fog." + scope.id(), "fog", value);
            }
            if (scope == AtmosphereDimension.OVERWORLD) {
                var mist = new Value(MIST_DENSITY, parse(MIST_DENSITY, api.getConfigVariableResolved(MIST_DENSITY.runtimeKey())));
                values.add(mist);
                addControls(settings, SettingGroup.GROUND_MIST, "mist", "mist", mist);
            }
            profiles.put(scope, List.copyOf(values));
        }
        var waterDefaults = driver.underwaterFogDefaults();
        underwater = OPTICS.stream().map(parameter -> authoredValue(parameter, waterDefaults, runtimeDefaults)).toList();
        for (var value : underwater) {
            addControls(settings, SettingGroup.WATER_FOG, "water.fog", "fog", value);
        }
    }

    private static Value authoredValue(Parameter parameter, Properties authored, Map<Parameter, float[]> runtimeDefaults) {
        String text = authored.getProperty(parameter.runtimeKey());
        if (text != null) {
            try {
                return new Value(parameter, parse(parameter, text));
            } catch (IllegalArgumentException ignored) {
                // Use the shared default when the authored value cannot be displayed.
            }
        }
        return new Value(parameter, runtimeDefaults.get(parameter));
    }

    private void addControls(List<KavoraSetting<?>> settings, SettingGroup group, String idPrefix, String labelPrefix, Value value) {
        var parameter = value.parameter();
        for (int channel = 0; channel < value.current().length; channel++) {
            int index = channel;
            String component = parameter.componentId(index);
            String label = "kavora.effect." + labelPrefix + "." + component;
            double baseline = value.defaults()[index];
            settings.add(KavoraSetting.ofFloat(idPrefix + "." + component, group, label, label + ".tip",
                    Math.min(parameter.min(), baseline), Math.max(parameter.max(), baseline), baseline,
                    () -> (double) value.current()[index], next -> {
                        float applied = next.floatValue();
                        if (Float.compare(value.current()[index], applied) != 0) {
                            value.current()[index] = applied;
                            changed = true;
                        }
                    }));
        }
    }

    void publish() {
        if (!changed) return;
        profiles.forEach((scope, values) -> driver.replaceFogOverrides(scope, overrides(values)));
        driver.replaceUnderwaterFogOverrides(overrides(underwater));
        changed = false;
    }

    private static Map<String, String> overrides(List<Value> values) {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (var value : values) {
            if (!Arrays.equals(value.defaults(), value.current())) {
                overrides.put(value.parameter().runtimeKey(), value.text());
            }
        }
        return overrides;
    }

    private static float[] parse(Parameter parameter, String text) {
        String[] fields = text.replace("(", "").replace(")", "").strip().split("\\s*,\\s*");
        if (fields.length != (parameter.color() ? RGB.size() : 1)) {
            throw new IllegalArgumentException("Invalid value for " + parameter.runtimeKey());
        }
        float[] values = new float[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = Float.parseFloat(fields[i]);
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Non-finite " + parameter.runtimeKey());
        }
        return values;
    }
}
