package com.arsstructura.file;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrefabFileManagerTest {
    @Test void namesCannotEscapeDirectoryOrNameWindowsDevices() {
        for (String bad : List.of("../escape", "..", "C:\\escape", "a/b", "a:b", "thing.nbt", "NUL", "con", "COM1", "LPT9", "", "a".repeat(65)))
            assertThrows(IOException.class, () -> PrefabFileManager.validateName(bad), bad);
    }

    @Test void chineseAndHumanReadableNamesAreAccepted() throws IOException {
        assertEquals("中式庭院", PrefabFileManager.validateName("中式庭院"));
        assertEquals("House 2-west", PrefabFileManager.validateName(" House 2-west "));
    }
}
