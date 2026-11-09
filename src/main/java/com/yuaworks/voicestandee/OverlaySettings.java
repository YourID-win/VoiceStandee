package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Set;

/**
 * How one streamer's overlay looks. Kept on the server, so that changing it in the settings page changes OBS at once
 * without touching the address in OBS.
 *
 * <p>Everything that comes from outside is clamped here: a missing, mistyped or absurd value falls back to a sane one.
 *
 * @param h         height of a standee, in pixels of the OBS screen
 * @param gap       space between people, in pixels
 * @param align     left, center or right
 * @param idle      brightness of people who are quiet (1 = not dimmed)
 * @param anim      hop, pulse or none
 * @param hopHeight how high a hop goes, in percent of the standee's height
 * @param hopSpeed  seconds for one hop: the smaller, the quicker and finer
 * @param squash    how much the figure stretches and squashes while hopping, in percent
 * @param tilt      how far it leans while hopping, in degrees
 */
record OverlaySettings(int h, int gap, boolean names, String align, double idle, String anim,
                       double hopHeight, double hopSpeed, double squash, double tilt) {

    static final OverlaySettings DEFAULT = new OverlaySettings(360, 24, false, "center", 0.62, "hop", 2.0, 0.32, 1.0, 0.0);
    private static final Set<String> ALIGNS = Set.of("left", "center", "right");
    private static final Set<String> ANIMS = Set.of("hop", "pulse", "none");

    static OverlaySettings fromJson(DataObject o) {
        OverlaySettings d = DEFAULT;
        return new OverlaySettings(
                (int) Math.round(number(o, "h", d.h, 60, 1500)),
                (int) Math.round(number(o, "gap", d.gap, 0, 400)),
                bool(o, "names", d.names),
                choice(o, "align", d.align, ALIGNS),
                round2(number(o, "idle", d.idle, 0.1, 1)),
                choice(o, "anim", d.anim, ANIMS),
                round2(number(o, "hopHeight", d.hopHeight, 0, 10)),
                round2(number(o, "hopSpeed", d.hopSpeed, 0.12, 1.5)),
                round2(number(o, "squash", d.squash, 0, 6)),
                round2(number(o, "tilt", d.tilt, 0, 8)));
    }

    DataObject toJson() {
        return DataObject.empty().put("h", h).put("gap", gap).put("names", names).put("align", align).put("idle", idle)
                .put("anim", anim).put("hopHeight", hopHeight).put("hopSpeed", hopSpeed).put("squash", squash).put("tilt", tilt);
    }

    private static double number(DataObject o, String key, double fallback, double min, double max) {
        try {
            if (!o.hasKey(key) || o.isNull(key)) {
                return fallback;
            }
            double v = o.getDouble(key);
            return Double.isFinite(v) ? Math.min(max, Math.max(min, v)) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(DataObject o, String key, boolean fallback) {
        try {
            return o.hasKey(key) && !o.isNull(key) ? o.getBoolean(key) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String choice(DataObject o, String key, String fallback, Set<String> allowed) {
        try {
            String v = o.hasKey(key) && !o.isNull(key) ? o.getString(key) : fallback;
            return allowed.contains(v) ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
