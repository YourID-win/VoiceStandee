package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDA checks names and lengths when the definitions are built, so merely building and serializing them catches mistakes. */
class CommandDefinitionTest {

    @Test
    void theDefinitionsAreValidAndFitDiscordsLimits() {
        var definitions = StandeeCommands.definitions();
        assertEquals(2, definitions.size());
        Set<String> names = new HashSet<>();
        for (SlashCommandData command : definitions) {
            DataObject data = command.toData();
            assertTrue(names.add(data.getString("name")));
            DataArray options = data.getArray("options");
            assertTrue(options.length() <= 25);
            Set<String> subcommands = new HashSet<>();
            for (int i = 0; i < options.length(); i++) {
                assertTrue(subcommands.add(options.getObject(i).getString("name")), "duplicate subcommand");
            }
        }
        assertEquals(Set.of("standee", "standee-admin"), names);
    }

    @Test
    void theUserCommandHasTheExpectedSubcommands() {
        DataObject data = StandeeCommands.definition().toData();
        Set<String> subcommands = new HashSet<>();
        DataArray options = data.getArray("options");
        for (int i = 0; i < options.length(); i++) {
            subcommands.add(options.getObject(i).getString("name"));
        }
        assertEquals(Set.of("set", "show", "remove", "obs", "auto", "hide", "unhide", "report"), subcommands);
    }

    @Test
    void theOwnersCommandIsSwitchedOffForEveryoneByDefault() {
        DataObject admin = Moderation.adminDefinition().toData();
        // "0" = no permission suffices: only what a server admin explicitly allows
        assertEquals("0", admin.get("default_member_permissions").toString());
        // while the commands everybody uses carry no such restriction
        assertTrue(StandeeCommands.definition().toData().isNull("default_member_permissions"));
    }
}
