/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.logic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Visibility at peak fog density, where green-channel contrast falls to five percent. */
public enum WeatherVisibility {
    RAIN("rain", LogicChannels.WEATHER_RAIN, 5000),
    THUNDER("thunder", LogicChannels.WEATHER_THUNDER, 5500);

    final ConfLayerDriver.LayerSpec layer;
    public final String settingId;
    static final String DISTANCE = "rtx.volumetrics.transmittanceMeasurementDistanceMeters";
    static final String COLOR = "rtx.volumetrics.transmittanceColor";
    private static final double ENCODED_OPTICAL_DEPTH = -Math.log(.05) / 2.2;

    WeatherVisibility(String id, String channel, int priority) {
        layer = new ConfLayerDriver.LayerSpec(channel, "confs/weather/" + id + ".conf",
                priority, .01f, false, ConfLayerDriver.LayerSource.MOD);
        settingId = "fog.overworld.visibility." + id;
    }

    static Map<String, String> calibrated(Properties authored, int meters) {
        Map<String, String> options = new LinkedHashMap<>();
        authored.stringPropertyNames().forEach(key -> options.put(key, authored.getProperty(key)));
        String[] rgb = authored.getProperty(COLOR, "").split(",");
        double green = rgb.length == 3 ? Double.parseDouble(rgb[1].strip()) : 1;
        if (green > 0 && green < 1) {
            // Keep the authored spectrum. Only its Beer–Lambert distance changes.
            options.put(DISTANCE, Double.toString(-meters * Math.log(green) / ENCODED_OPTICAL_DEPTH));
        } else {
            // An absent or singular reference cannot define a finite range.
            // Use a neutral 5% reference at the requested distance.
            String reference = Double.toString(Math.exp(-ENCODED_OPTICAL_DEPTH));
            options.put(COLOR, reference + ", " + reference + ", " + reference);
            options.put(DISTANCE, Integer.toString(meters));
        }
        return options;
    }
}
