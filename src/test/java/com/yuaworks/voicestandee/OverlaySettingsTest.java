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
        OverlaySettings s = new OverlaySettings(420, 10, true, "left", 0.5, "pulse", 1.6, 0.26, 1.2, 2.5, 0.4, "now");
        assertEquals(s, OverlaySettings.fromJson(s.toJson()));
    }

    @Test
    void theReleaseIsClampedAndTheStopModeIsChecked() {
        OverlaySettings s = OverlaySettings.fromJson(DataObject.fromJson("{\"release\":99,\"stopMode\":\"never\"}"));
        assertEquals(1.5, s.release());
        assertEquals("smooth", s.stopMode());
        assertEquals(0.0, OverlaySettings.fromJson(DataObject.fromJson("{\"release\":-1}")).release());
        assertEquals("now", OverlaySettings.fromJson(DataObject.fromJson("{\"stopMode\":\"now\"}")).stopMode());
    }

    @Test
    void theLayoutAndTheComingAndGoingAreCheckedAndClamped() {
        OverlaySettings d = OverlaySettings.DEFAULT;
        assertEquals("row", d.layout());
        assertEquals(true, d.fit());
        assertEquals("fade", d.enter());
        assertEquals(350, d.animMs());
        assertEquals("auto", d.orderMode());

        OverlaySettings s = OverlaySettings.fromJson(DataObject.fromJson(
                "{\"layout\":\"column\",\"fit\":false,\"enter\":\"drop\",\"leave\":\"none\",\"animMs\":99999,\"orderMode\":\"manual\"}"));
        assertEquals("column", s.layout());
        assertEquals(false, s.fit());
        assertEquals("drop", s.enter());
        assertEquals("none", s.leave());
        assertEquals(1500, s.animMs());
        assertEquals("manual", s.orderMode());

        OverlaySettings bad = OverlaySettings.fromJson(DataObject.fromJson(
                "{\"layout\":\"spiral\",\"enter\":\"explode\",\"leave\":7,\"animMs\":1,\"orderMode\":\"random\"}"));
        assertEquals("row", bad.layout());
        assertEquals("fade", bad.enter());
        assertEquals("fade", bad.leave());
        assertEquals(100, bad.animMs());
        assertEquals("auto", bad.orderMode());
    }

    @Test
    void theChosenOrderKeepsOnlyWellFormedIdsOnceEachAndRoundTrips() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 1; i <= 80; i++) {
            many.append(i > 1 ? "," : "").append("{\"id\":\"").append(1000 + i).append("\",\"name\":\"n").append(i).append("\"}");
        }
        many.append("]");
        assertEquals(OverlaySettings.MAX_SEATS, OverlaySettings.fromJson(DataObject.fromJson("{\"order\":" + many + "}")).order().size());

        OverlaySettings s = OverlaySettings.fromJson(DataObject.fromJson(
                "{\"order\":[{\"id\":\"123\",\"name\":\"A\"},{\"id\":\"abc\",\"name\":\"bad id\"},{\"id\":\"123\",\"name\":\"again\"},"
                        + "{\"id\":\"456\",\"name\":\"" + "x".repeat(200) + "\"},{\"name\":\"no id\"}]}"));
        assertEquals(2, s.order().size());
        assertEquals("123", s.order().get(0).id());
        assertEquals("A", s.order().get(0).name());
        assertEquals(60, s.order().get(1).name().length());
        assertEquals(s, OverlaySettings.fromJson(s.toJson()));
        assertEquals(0, OverlaySettings.fromJson(DataObject.fromJson("{\"order\":\"nope\"}")).order().size());
    }
}
