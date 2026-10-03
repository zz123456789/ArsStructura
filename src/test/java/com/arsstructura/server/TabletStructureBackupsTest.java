package com.arsstructura.server;

import com.arsstructura.config.ModConfig;
import com.arsstructura.data.SafeNbt;
import com.arsstructura.data.TestEnvironment;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TabletStructureBackupsTest {
    @BeforeAll static void bootstrap() { TestEnvironment.config(); }

    @Test void duplicatePayloadsShareStorageAndDefensiveCopiesProtectBackups() {
        TabletStructureBackups store = new TabletStructureBackups();
        byte[] original = payload(1), expected = original.clone();
        String key = store.archive(original);
        assertEquals(key, store.archive(original));
        assertEquals(1, store.size());
        assertEquals(expected.length, store.usedBytes());
        original[0] ^= 1;
        byte[] returned = store.copy(key);
        returned[0] ^= 1;
        assertArrayEquals(expected, store.copy(key));
    }

    @Test void persistenceRejectsMismatchedHashesAndInvalidKeys() {
        TabletStructureBackups store = new TabletStructureBackups();
        byte[] payload = payload(2);
        String key = store.archive(payload);
        CompoundTag saved = store.save(new CompoundTag());
        CompoundTag entries = saved.getCompound("Blobs");
        entries.putByteArray("bad-key", payload);
        entries.putByteArray("0".repeat(64), payload);
        TabletStructureBackups loaded = TabletStructureBackups.load(saved);
        assertEquals(1, loaded.size());
        assertArrayEquals(payload, loaded.copy(key));
        assertNull(loaded.copy("bad-key"));
        assertNull(loaded.copy("0".repeat(64)));
    }

    @Test void countQuotaRejectsNewContentWithoutEvictingOldContent() {
        int previous = ModConfig.MAX_BACKUP_STRUCTURES.get();
        try {
            ModConfig.MAX_BACKUP_STRUCTURES.set(16);
            TabletStructureBackups store = new TabletStructureBackups();
            String first = store.archive(payload(0));
            for (int i = 1; i < 16; i++) store.archive(payload(i));
            assertThrows(IllegalArgumentException.class, () -> store.archive(payload(17)));
            assertEquals(16, store.size());
            assertArrayEquals(payload(0), store.copy(first));
            assertEquals(first, store.archive(payload(0)), "Existing content must remain usable at quota");
        } finally { ModConfig.MAX_BACKUP_STRUCTURES.set(previous); }
    }

    @Test void byteQuotaAndPerBlobLimitAreEnforced() {
        long previous = ModConfig.MAX_BACKUP_BYTES.get();
        try {
            ModConfig.MAX_BACKUP_BYTES.set(8L * 1024 * 1024);
            TabletStructureBackups store = new TabletStructureBackups();
            byte[] maximum = new byte[SafeNbt.HARD_COMPRESSED_LIMIT];
            for (int i = 0; i < 8; i++) { maximum[0] = (byte) i; store.archive(maximum); }
            maximum[0] = 9;
            assertThrows(IllegalArgumentException.class, () -> store.archive(maximum));
            assertEquals(8L * 1024 * 1024, store.usedBytes());
            assertEquals(8, store.size());
            assertThrows(IllegalArgumentException.class, () -> store.archive(new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> store.archive(new byte[SafeNbt.HARD_COMPRESSED_LIMIT + 1]));
        } finally { ModConfig.MAX_BACKUP_BYTES.set(previous); }
    }

    private static byte[] payload(int value) {
        CompoundTag data = new CompoundTag();
        data.putInt("Value", value);
        return SafeNbt.compress(data);
    }
}
