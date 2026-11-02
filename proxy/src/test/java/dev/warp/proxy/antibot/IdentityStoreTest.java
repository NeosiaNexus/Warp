/*
 * Copyright (C) 2026 Warp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.warp.proxy.antibot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.Verification;
import dev.warp.api.antibot.VerificationMethod;
import dev.warp.api.antibot.VerifiedKey;
import dev.warp.api.event.antibot.IdentityVerifiedEvent;
import dev.warp.proxy.config.AntiBotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("IdentityStore")
class IdentityStoreTest {

  private static final long DAY = Duration.ofDays(1).toMillis();
  private static final IdentityKeys KEYS = new IdentityKeys(new byte[32], 24, 48);
  private static final UUID ACCOUNT = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

  @TempDir private Path directory;
  private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
  private final List<IdentityVerifiedEvent> events = new CopyOnWriteArrayList<>();

  @Nested
  @DisplayName("on disk")
  class OnDisk {

    @Test
    @DisplayName("should find again after a restart what it trusted, and not what it forgot")
    void survivesRestart() throws Exception {
      IdentityStore store = open();
      VerifiedKey kept = KEYS.account(ACCOUNT);
      VerifiedKey forgotten = KEYS.account(UUID.randomUUID());
      store.trust(kept, VerificationMethod.COMMAND, null).get(5, TimeUnit.SECONDS);
      store.trust(forgotten, VerificationMethod.JOINED, null).get(5, TimeUnit.SECONDS);
      store.forget(forgotten).get(5, TimeUnit.SECONDS);
      store.close();

      IdentityStore reopened = open();

      Verification found = reopened.find(kept);
      assertNotNull(found);
      assertEquals(VerificationMethod.COMMAND, found.method());
      assertNull(reopened.find(forgotten));
      reopened.close();
    }

    @Test
    @DisplayName("should replay the journal after a crash, cutting off a torn last change")
    void replaysJournal() throws IOException {
      try (VerifiedFiles files = new VerifiedFiles(directory)) {
        files.appendPut(1, 1, 100, 100, (byte) 0);
        files.appendPut(2, 2, 100, 100, (byte) 0);
        files.appendRemove(1, 1);
        files.flush();
      }
      // Half an entry: the process died mid-write.
      Files.write(
          directory.resolve(VerifiedFiles.JOURNAL), new byte[13], StandardOpenOption.APPEND);

      VerifiedTable table = new VerifiedTable(10);
      try (VerifiedFiles files = new VerifiedFiles(directory)) {
        VerifiedFiles.Loaded loaded = files.load(table, 1);

        assertEquals(3, loaded.journalEntries());
      }
      assertNull(table.find(1, 1));
      assertNotNull(table.find(2, 2));
      assertEquals(3L * VerifiedFiles.ENTRY, Files.size(directory.resolve(VerifiedFiles.JOURNAL)));
    }

    @Test
    @DisplayName("should set a damaged snapshot aside and still replay the journal")
    void damagedSnapshot() throws Exception {
      IdentityStore store = open();
      store.trust(KEYS.account(ACCOUNT), VerificationMethod.JOINED, null).get(5, TimeUnit.SECONDS);
      store.close();
      Path snapshot = directory.resolve(VerifiedFiles.SNAPSHOT);
      byte[] bytes = Files.readAllBytes(snapshot);
      bytes[20] ^= 0x55;
      Files.write(snapshot, bytes);

      VerifiedTable table = new VerifiedTable(10);
      try (VerifiedFiles files = new VerifiedFiles(directory)) {
        assertTrue(files.load(table, 1).damaged());
      }
      assertTrue(Files.exists(directory.resolve(VerifiedFiles.SNAPSHOT + ".damaged")));
    }
  }

  @Nested
  @DisplayName("expiry and events")
  class Expiry {

    @Test
    @DisplayName("should forget an identity 30 days after its last join, not after its first")
    void slidingExpiry() throws Exception {
      IdentityStore store = open();
      VerifiedKey key = KEYS.account(ACCOUNT);
      store.trust(key, VerificationMethod.JOINED, null).get(5, TimeUnit.SECONDS);

      clock.addAndGet(20 * DAY);
      store.seen(key);
      store.drainForTests(() -> {});
      clock.addAndGet(20 * DAY);

      assertNotNull(store.find(key), "seen 20 days ago: still trusted");
      clock.addAndGet(11 * DAY);
      assertNull(store.find(key), "31 days after its last join");
      store.close();
    }

    @Test
    @DisplayName("should announce an identity it starts trusting, once")
    void announcesOnce() throws Exception {
      IdentityStore store = open();
      Identity identity = new Identity.Account(ACCOUNT);

      store.add(identity).get(5, TimeUnit.SECONDS);
      store.add(identity).get(5, TimeUnit.SECONDS);

      assertEquals(1, events.size());
      assertEquals(identity, events.getFirst().identity());
      assertEquals(VerificationMethod.PLUGIN, events.getFirst().verification().method());
      assertTrue(store.check(identity).isPresent());
      store.close();
    }
  }

  private IdentityStore open() throws IOException {
    AntiBotConfig.Verified d = AntiBotConfig.Verified.DEFAULTS;
    AntiBotConfig.Verified config =
        new AntiBotConfig.Verified(
            Duration.ofDays(30), 1_000, 24, 48, true, d.secretFile(), d.sharedStoreTimeout());
    return new IdentityStore(
        config, KEYS, directory, clock::get, events::add, IdentityStore.newThread());
  }
}
