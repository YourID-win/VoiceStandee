package com.yuaworks.voicestandee;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigTest {

    @Test
    void theExtraTokensAreSplitByCommasSemicolonsAndSpacesWithoutRepeatsOrTheMainToken() {
        assertEquals(List.of("b", "c", "d"), Config.parseTokens(" b, c;d  b ,main", "main"));
        assertEquals(List.of(), Config.parseTokens("", "main"));
        assertEquals(List.of(), Config.parseTokens(null, "main"));
        assertEquals(List.of(), Config.parseTokens(" , ;", "main"));
    }
}
