package org.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiCommandControllerTest {

    @Test
    void commandNameExtractsFirstWord() {
        assertEquals("/help", TuiCommandController.commandName("/help"));
        assertEquals("/model", TuiCommandController.commandName("/model gpt-4"));
        assertEquals("/quit", TuiCommandController.commandName("/quit now"));
    }

    @Test
    void commandNameCaseInsensitive() {
        assertEquals("/help", TuiCommandController.commandName("/HELP"));
        assertEquals("/model", TuiCommandController.commandName("/Model GPT-4"));
    }

    @Test
    void commandNameSingleWord() {
        assertEquals("/undo", TuiCommandController.commandName("/undo"));
    }

    @Test
    void commandNameWithMultipleSpaces() {
        assertEquals("/theme", TuiCommandController.commandName("/theme   dark"));
    }
}
