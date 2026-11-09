package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverlaySettingsTest {

    @Test
    void anEmptyObjectGivesTheDefaults() {
        assertEquals(OverlaySettings.DEFAULT, OverlaySettings.fromJson(DataObject.empty()));
    }

    @Test
    void valuesAreClampedToTheirRanges() {
        OverlaySettings s = OverlaySettings.fromJson(DataObject.fromJson(
                "{\"h\":99999,\"gap\":-5,\"idle\":0,\"hopHeight\":50,\"hopSpeed\":0.001,\"squash\":99,\"tilt\":-3}"));
        assertEquals(1500, s.h());
        assertEquals(0, s.gap());
        assertEquals(0.1, s.idle());
        assertEquals(10.0, s.hopHeight());
        assertEquals(0.12, s.hopSpeed());
        assertEquals(6.0, s.squash());
        assertEquals(0.0, s.tilt());
    }

    @Test
    void wrongTypesAndUnknownChoicesFallBackToTheDefaults() {
        OverlaySettings s = OverlaySettings.fromJson(DataObject.fromJson(
                "{\"h\":\"tall\",\"names\":\"yes\",\"align\":\"diagonal\",\"anim\":\"explode\",\"hopSpeed\":null,\"extra\":1}"));
        assertEquals(OverlaySettings.DEFAULT, s);
    }

    @Test
    void goodValuesAreKeptAndRoundTrip() {
        OverlaySettings s = new OverlaySettings(420, 10, true, "left", 0.5, "pulse", 1.6, 0.26, 1.2, 2.5);
        assertEquals(s, OverlaySettings.fromJson(s.toJson()));
    }
}
