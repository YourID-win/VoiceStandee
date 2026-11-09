package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;

/**
 * What an overlay shows at one moment: the people in the streamer's voice channel and who is speaking.
 *
 * @param live whether the bot is in that channel (if not, nobody can be shown as speaking)
 */
record Snapshot(boolean live, List<Person> people) {

    static final Snapshot EMPTY = new Snapshot(false, List.of());

    /**
     * @param img    path of the registered standee picture on this server, or null
     * @param avatar the Discord avatar, shown instead when there is no standee
     */
    record Person(String id, String name, String img, String avatar, boolean speaking) {
    }

    /**
     * The message the overlay and the settings page receive: the people, how to draw them, and the streamer's own picture
     * (so the settings page has something to preview with when nobody is in a channel).
     */
    String toJson(OverlaySettings settings, String selfImg) {
        DataArray array = DataArray.empty();
        for (Person p : people) {
            DataObject o = DataObject.empty().put("id", p.id()).put("name", p.name()).put("speaking", p.speaking());
            if (p.img() != null) {
                o.put("img", p.img());
            }
            if (p.avatar() != null) {
                o.put("avatar", p.avatar());
            }
            array.add(o);
        }
        DataObject self = DataObject.empty();
        if (selfImg != null) {
            self.put("img", selfImg);
        }
        return DataObject.empty().put("live", live).put("people", array).put("settings", settings.toJson()).put("self", self).toString();
    }
}
