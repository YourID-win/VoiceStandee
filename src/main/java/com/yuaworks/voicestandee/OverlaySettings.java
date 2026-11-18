package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * @param release   seconds the picture keeps "speaking" after the voice stops (0 = stops at once); also hides tiny gaps
 * @param stopMode  smooth = finish the hop that is under way, now = stop moving at once
 * @param layout    row (one line), column (stacked) or grid (two lines)
 * @param fit       shrink everybody so that they all fit on the OBS screen
 * @param enter     how a person appears: fade, slide, pop, drop or none
 * @param leave     how a person goes away: the same choices
 * @param animMs    milliseconds an appearing or leaving takes
 * @param orderMode auto (the order of the channel) or manual (the order below)
 * @param order     the people in the order the streamer chose, with the last name seen (people who are not in it come after)
 */
record OverlaySettings(int h, int gap, boolean names, String align, double idle, String anim,
                       double hopHeight, double hopSpeed, double squash, double tilt, double release, String stopMode,
                       String layout, boolean fit, String enter, String leave, int animMs, String orderMode, List<Seat> order) {

    /** One person in the order the streamer chose: the Discord id and the name last seen (shown in the settings list). */
    record Seat(String id, String name) {
    }

    static final int MAX_SEATS = 60;

    OverlaySettings {
        order = List.copyOf(order);
    }

    /** The look without the layout and the order (what the settings had before they were added). */
    OverlaySettings(int h, int gap, boolean names, String align, double idle, String anim, double hopHeight, double hopSpeed,
                    double squash, double tilt, double release, String stopMode) {
        this(h, gap, names, align, idle, anim, hopHeight, hopSpeed, squash, tilt, release, stopMode,
                "row", true, "fade", "fade", 350, "auto", List.of());
    }

    static final OverlaySettings DEFAULT =
            new OverlaySettings(360, 24, false, "center", 0.62, "hop", 2.0, 0.32, 1.0, 0.0, 0.15, "smooth");
    private static final Set<String> LAYOUTS = Set.of("row", "column", "grid");
    private static final Set<String> APPEARS = Set.of("fade", "slide", "pop", "drop", "none");
    private static final Set<String> ORDER_MODES = Set.of("auto", "manual");
    private static final Set<String> ALIGNS = Set.of("left", "center", "right");
    private static final Set<String> ANIMS = Set.of("hop", "pulse", "none");
    private static final Set<String> STOP_MODES = Set.of("smooth", "now");

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
                round2(number(o, "tilt", d.tilt, 0, 8)),
                round2(number(o, "release", d.release, 0, 1.5)),
                choice(o, "stopMode", d.stopMode, STOP_MODES),
                choice(o, "layout", d.layout, LAYOUTS),
                bool(o, "fit", d.fit),
                choice(o, "enter", d.enter, APPEARS),
                choice(o, "leave", d.leave, APPEARS),
                (int) Math.round(number(o, "animMs", d.animMs, 100, 1500)),
                choice(o, "orderMode", d.orderMode, ORDER_MODES),
                seats(o));
    }

    /** The chosen order: only well-formed ids, no repeats, at most {@link #MAX_SEATS}; the names are cut short. */
    private static List<Seat> seats(DataObject o) {
        List<Seat> out = new ArrayList<>();
        try {
            if (!o.hasKey("order") || o.isNull("order")) {
                return out;
            }
            var array = o.getArray("order");
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < array.length() && out.size() < MAX_SEATS; i++) {
                DataObject s = array.getObject(i);
                String id = s.getString("id", "");
                if (!id.matches("[0-9]{1,20}") || !seen.add(id)) {
                    continue;
                }
                String name = s.getString("name", "");
                out.add(new Seat(id, name.length() > 60 ? name.substring(0, 60) : name));
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    DataObject toJson() {
        return DataObject.empty().put("h", h).put("gap", gap).put("names", names).put("align", align).put("idle", idle)
                .put("anim", anim).put("hopHeight", hopHeight).put("hopSpeed", hopSpeed).put("squash", squash).put("tilt", tilt)
                .put("release", release).put("stopMode", stopMode).put("layout", layout).put("fit", fit)
                .put("enter", enter).put("leave", leave).put("animMs", animMs).put("orderMode", orderMode)
                .put("order", net.dv8tion.jda.api.utils.data.DataArray.fromCollection(
                        order.stream().map(s -> DataObject.empty().put("id", s.id()).put("name", s.name())).toList()));
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
