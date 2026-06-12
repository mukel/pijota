package ai.pijota.tui;

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

    @Test
    void commandArgsExtractsRemaining() {
        assertEquals("gpt-4", TuiCommandController.commandArgs("/model gpt-4", "/model"));
        assertEquals("arg1 arg2", TuiCommandController.commandArgs("/cmd arg1 arg2", "/cmd"));
    }

    @Test
    void commandArgsEmptyWhenNoArgs() {
        assertEquals("", TuiCommandController.commandArgs("/help", "/help"));
    }

    @Test
    void commandArgsEmptyWhenInputEqualsCommand() {
        assertEquals("", TuiCommandController.commandArgs("/quit", "/quit"));
    }

    @Test
    void commandArgsKeepsCase() {
        assertEquals("Dark Mode", TuiCommandController.commandArgs("/theme Dark Mode", "/theme"));
    }

    @Test
    void commandArgsWithSlashInArguments() {
        assertEquals("sub/command", TuiCommandController.commandArgs("/load sub/command", "/load"));
    }
}
