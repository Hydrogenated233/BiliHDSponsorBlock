package top.local.bilihdskip;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SegmentCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(new File("."));
    private static final String SERVER = "https://one.example/api";
    private static final String OTHER = "https://two.example/api";
    private static final String BASE = "segment-cache-v1.bin";
    private static final class Time implements SegmentCache.Clock {
        long now = 10000000L;
        @Override public long nowMillis() { return now; }
    }

    private static SegmentCache.Response response(String text) {
        return new SegmentCache.Response(text, 200, true);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // This literal is JSON.stringify({responseText:'[""]',status:200,ok:true}).
    // Quotes in responseText count twice after JSON.stringify; no org.json size helper.
    private static SegmentCache.Response withSize(int size, char fill) {
        int overhead = "{\"responseText\":\"[\\\"\\\"]\",\"status\":200,\"ok\":true}".length();
        assertTrue(size >= overhead);
        return response("[\"" + repeat(fill, size - overhead) + "\"]");
    }

    @Test public void restartReloadAndUpdatePersistOriginalExpiry() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        assertTrue(cache.put(SERVER, "abcd", response("[]")));
        clock.now += SegmentCache.TTL_MILLIS - 1;
        SegmentCache restarted = new SegmentCache(dir, clock);
        assertEquals("[]", restarted.get(SERVER, "abcd").responseText);
        clock.now++;
        assertNull(restarted.get(SERVER, "abcd"));
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        cache.reload();
        assertNull(cache.get(SERVER, "abcd"));
        assertTrue(cache.put(SERVER, "abcd", response("[1]")));
        clock.now += SegmentCache.TTL_MILLIS - 1;
        assertEquals("[1]", new SegmentCache(dir, clock).get(SERVER, "abcd").responseText);
    }

    @Test public void readDoesNotRenewExpiryAndBackwardClockMatchesWallClock() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "x", response("[]"));
        long inserted = clock.now;
        clock.now -= 50000;
        assertNotNull(new SegmentCache(dir, clock).get(SERVER, "x"));
        clock.now = inserted + SegmentCache.TTL_MILLIS - 1;
        assertNotNull(cache.get(SERVER, "x"));
        clock.now++;
        assertNull(cache.get(SERVER, "x"));
        clock.now = inserted;
        assertNull(new SegmentCache(dir, clock).get(SERVER, "x"));
    }

    @Test public void exactTotalBoundaryIncludesWrapperAndUtf8IsNotLogicalQuota() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        SegmentCache.Response full = withSize(SegmentCache.MAX_SIZE_UNITS, '\u4e2d');
        assertTrue(cache.put(SERVER, "full", full));
        assertEquals(SegmentCache.MAX_SIZE_UNITS, cache.stats().sizeUnits);
        File disk = new File(dir, BASE);
        assertTrue("UTF-8 storage legitimately exceeds logical 500Ki units", disk.length() > SegmentCache.MAX_SIZE_UNITS);
        assertTrue(disk.length() <= SegmentCache.MAX_STORAGE_BYTES);
        assertEquals(full.responseText, new SegmentCache(dir, clock).get(SERVER, "full").responseText);
        clock.now++;
        assertTrue(cache.put(SERVER, "small", response("[]")));
        assertNull(cache.get(SERVER, "full"));
        assertNotNull(cache.get(SERVER, "small"));
        assertFalse(cache.put(SERVER, "too-large", withSize(SegmentCache.MAX_SIZE_UNITS + 1, 'x')));
        assertEquals(0, cache.stats().entryCount);
        assertEquals(0, new SegmentCache(dir, clock).stats().sizeUnits);
    }

    @Test public void utf8BoundariesRoundTripEscapesAndSupplementaryCharacters() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        String text = "\u4e2d\u6587\ud83d\ude80</\u0080\u2028\u2029\"\\\b\f\n\r\t\u0000\ud800\udc00\ud800x\udc00";
        SegmentCache.Response value = new SegmentCache.Response(text, 404, false, "\u4e2d\"\n");
        cache.put(SERVER, "unicode", value);
        // Independent JS fixture: Node JSON.stringify produces 108 UTF-16 code units.
        assertEquals(108, cache.stats().sizeUnits);
        SegmentCache.Response roundTrip = new SegmentCache(dir, clock).get(SERVER, "unicode");
        assertEquals(text, roundTrip.responseText);
        assertEquals(404, roundTrip.status);
        assertFalse(roundTrip.ok);
        assertEquals(value.retryAfter, roundTrip.retryAfter);
    }

    @Test public void aggregateQuotaEvictsOldestWriteNotRecentlyReadAndUpdateRenewsOrder() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        int half = SegmentCache.MAX_SIZE_UNITS / 2;
        cache.put(SERVER, "a", withSize(half, 'a'));
        clock.now++;
        cache.put(SERVER, "b", withSize(half, 'b'));
        assertEquals(SegmentCache.MAX_SIZE_UNITS, cache.stats().sizeUnits);
        assertNotNull(cache.get(SERVER, "a"));
        clock.now++;
        cache.put(SERVER, "c", withSize(half, 'c'));
        assertNull(cache.get(SERVER, "a"));
        assertNotNull(cache.get(SERVER, "b"));
        clock.now++;
        cache.put(SERVER, "b", withSize(half, 'B'));
        clock.now++;
        cache.put(SERVER, "d", withSize(half, 'd'));
        assertNull(cache.get(SERVER, "c"));
        assertNotNull(new SegmentCache(dir, clock).get(SERVER, "b"));
        assertEquals(2, cache.stats().entryCount);
    }

    @Test public void countBoundaryAndTiesSurviveRestart() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        for (int i = 0; i < SegmentCache.MAX_ENTRIES; i++) cache.put(SERVER, "key-" + i, response("[]"));
        assertEquals(SegmentCache.MAX_ENTRIES, cache.stats().entryCount);
        assertNotNull(cache.get(SERVER, "key-0"));
        cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "overflow", response("[]"));
        assertNull(cache.get(SERVER, "key-0"));
        assertNotNull(cache.get(SERVER, "key-1"));
        assertEquals(SegmentCache.MAX_ENTRIES, new SegmentCache(dir, clock).stats().entryCount);
    }

    @Test public void serverNamespacesShareGlobalQuotaAndSwitchingBackIsSafe() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "same", response("[1]"));
        assertNull(cache.get(OTHER, "same"));
        cache.put(OTHER, "same", response("[2]"));
        cache = new SegmentCache(dir, clock);
        assertEquals("[1]", cache.get(SERVER, "same").responseText);
        assertEquals("[2]", cache.get(OTHER, "same").responseText);
        assertNull(cache.get(SERVER + "/", "same"));
        clock.now++;
        cache.put(OTHER, "full", withSize(SegmentCache.MAX_SIZE_UNITS, 'x'));
        assertNull(cache.get(SERVER, "same"));
        assertEquals(1, cache.stats().entryCount);
    }

    @Test public void framedHashesRejectTraversalAndDoNotPersistServerOrKey() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        String key = "../outside/../../secret-video?token=not-a-real-token";
        cache.put(SERVER, key, response("[]"));
        assertNotNull(new SegmentCache(dir, clock).get(SERVER, key));
        assertEquals(1, dir.list().length);
        String raw = new String(Files.readAllBytes(new File(dir, BASE).toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(SERVER));
        assertFalse(raw.contains("secret-video"));
        cache.put("https://one.example/a", "/b:c", response("[1]"));
        cache.put("https://one.example/a/b", ":c", response("[2]"));
        assertEquals("[1]", cache.get("https://one.example/a", "/b:c").responseText);
        assertEquals("[2]", cache.get("https://one.example/a/b", ":c").responseText);
        for (String invalid : new String[]{"https://user:pass@one.example", "https://one.example?token=x",
                "https://one.example#secret", "http://one.example", "../../outside"}) {
            try { cache.put(invalid, "x", response("[]")); fail(invalid); }
            catch (IllegalArgumentException expected) { /* reject credentials before hashing */ }
        }
    }

    @Test public void clearAndInvalidationPersistAcrossRestartAndPendingTemporaryFile() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "a", response("[]"));
        cache.put(OTHER, "a", new SegmentCache.Response("", 404, false));
        cache.remove(SERVER, "a");
        assertNull(new SegmentCache(dir, clock).get(SERVER, "a"));
        assertNotNull(cache.get(OTHER, "a"));
        byte[] old = Files.readAllBytes(new File(dir, BASE).toPath());
        cache.clear();
        // A stale backup and unfinished temporary snapshot cannot recreate cleared data.
        Files.write(new File(dir, BASE + ".bak").toPath(), old);
        Files.write(new File(dir, BASE + ".tmp").toPath(), old);
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        assertFalse(new File(dir, BASE + ".bak").exists());
        assertFalse(new File(dir, BASE + ".tmp").exists());
    }

    @Test public void failedPutOrClearPreservesMemoryAndLastCommittedSnapshot() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "a", response("[]"));
        File blocker = new File(dir, BASE + ".tmp");
        assertTrue(blocker.mkdir());
        try { cache.put(SERVER, "b", response("[1]")); fail(); }
        catch (IOException expected) { assertNull(cache.get(SERVER, "b")); }
        try { cache.clear(); fail(); }
        catch (IOException expected) { assertNotNull(cache.get(SERVER, "a")); }
        assertTrue(blocker.delete());
        assertNotNull(new SegmentCache(dir, clock).get(SERVER, "a"));
    }

    @Test public void crashRecoveryUsesValidMainOtherwiseBackupNeverOrphanTemporary() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "a", response("[1]"));
        File main = new File(dir, BASE), backup = new File(dir, BASE + ".bak");
        Files.copy(main.toPath(), backup.toPath());
        Files.write(main.toPath(), new byte[]{0, 1, 2});
        assertEquals("[1]", new SegmentCache(dir, clock).get(SERVER, "a").responseText);
        Files.move(main.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        assertNotNull(new SegmentCache(dir, clock).get(SERVER, "a"));
        Files.move(main.toPath(), new File(dir, BASE + ".tmp").toPath());
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
    }

    @Test public void corruptTruncatedAndOversizedSnapshotsBecomeEmptyBoundedStorage() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "a", response("[]"));
        File main = new File(dir, BASE);
        byte[] valid = Files.readAllBytes(main.toPath());
        byte[] corrupt = valid.clone(); corrupt[20] ^= 1;
        Files.write(main.toPath(), corrupt);
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        for (int length : new int[]{0, 1, 43, valid.length - 1}) {
            Files.write(main.toPath(), Arrays.copyOf(valid, length));
            assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        }
        try (RandomAccessFile out = new RandomAccessFile(main, "rw")) {
            out.setLength((long) SegmentCache.MAX_STORAGE_BYTES + 1);
        }
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        assertTrue(main.length() <= SegmentCache.MAX_STORAGE_BYTES);
    }

    private static void rehashAndWrite(File file, byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(data, data.length - 32));
        System.arraycopy(hash, 0, data, data.length - 32, 32);
        Files.write(file.toPath(), data);
    }

    private static void setInt(byte[] data, int offset, int value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (24 - 8 * i));
    }

    @Test public void checksummedMalformedMetadataAndUtf8AreStillRejected() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "a", response("[]"));
        File main = new File(dir, BASE);
        byte[] valid = Files.readAllBytes(main.toPath());
        // Header: magic/version/count; first record: hash32/expiry8/units4/bytes4.
        for (int[] mutation : new int[][]{{4, 999}, {8, 1001}, {8, -1}, {52, 0},
                {52, SegmentCache.MAX_SIZE_UNITS + 1}, {56, Integer.MAX_VALUE}, {56, -1}}) {
            byte[] bad = valid.clone(); setInt(bad, mutation[0], mutation[1]); rehashAndWrite(main, bad);
            assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        }
        byte[] invalidUtf8 = valid.clone(); invalidUtf8[60] = (byte) 0xff;
        rehashAndWrite(main, invalidUtf8);
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        byte[] malformedJson = valid.clone(); malformedJson[60] = '[';
        rehashAndWrite(main, malformedJson);
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
    }

    private static final class Stored {
        final byte[] key, payload;
        final long expires;
        final int units;
        Stored(byte[] key, long expires, int units, byte[] payload) {
            this.key = key; this.expires = expires; this.units = units; this.payload = payload;
        }
    }

    private static void writeSnapshot(File file, Stored... records) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x53474331); out.writeInt(1); out.writeInt(records.length);
            for (Stored record : records) {
                out.write(record.key); out.writeLong(record.expires); out.writeInt(record.units);
                out.writeInt(record.payload.length); out.write(record.payload);
            }
        }
        byte[] raw = bytes.toByteArray();
        byte[] withHash = Arrays.copyOf(raw, raw.length + 32);
        rehashAndWrite(file, withHash);
    }

    @Test public void duplicateKeysNestedWrappersAndUntrustedAggregateSizeRejectWholeSnapshot() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "a", response("[]"));
        File main = new File(dir, BASE);
        byte[] original = Files.readAllBytes(main.toPath());
        byte[] hash = Arrays.copyOfRange(original, 12, 44);
        byte[] payload = Arrays.copyOfRange(original, 60, original.length - 32);
        Stored valid = new Stored(hash, clock.now + SegmentCache.TTL_MILLIS,
                new String(payload, StandardCharsets.UTF_8).length(), payload);
        writeSnapshot(main, valid, valid);
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        String[] malformed = {
                "{\"responseText\":\"[]\",\"status\":\"200\",\"ok\":true}",
                "{\"responseText\":\"[]\",\"status\":200.5,\"ok\":true}",
                "{\"responseText\":\"[]\",\"status\":200,\"ok\":\"true\"}",
                "{\"responseText\":\"[]\",\"status\":200,\"ok\":true,\"identity\":\"unexpected\"}",
                "{\"responseText\":" + repeat('[', 20000) + repeat(']', 20000)
                        + ",\"status\":200,\"ok\":true}"
        };
        for (String bad : malformed) {
            writeSnapshot(main, new Stored(hash, valid.expires, bad.length(), bad.getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
        }
        String big = "{\"responseText\":\"" + repeat('x', SegmentCache.MAX_SIZE_UNITS / 2)
                + "\",\"status\":200,\"ok\":true}";
        byte[] otherHash = hash.clone(); otherHash[0] ^= 1;
        Stored a = new Stored(hash, valid.expires, big.length(), big.getBytes(StandardCharsets.UTF_8));
        Stored b = new Stored(otherHash, valid.expires, big.length(), big.getBytes(StandardCharsets.UTF_8));
        writeSnapshot(main, a, b);
        assertEquals(0, new SegmentCache(dir, clock).stats().entryCount);
    }

    @Test public void expirationIsCollectedBeforeEvictionAndClockRollbackUsesMaxAgeOrder() throws Exception {
        File dir = temporary.newFolder(); Time clock = new Time();
        SegmentCache cache = new SegmentCache(dir, clock);
        cache.put(SERVER, "old", withSize(SegmentCache.MAX_SIZE_UNITS, 'x'));
        clock.now += SegmentCache.TTL_MILLIS;
        cache.put(SERVER, "new", withSize(SegmentCache.MAX_SIZE_UNITS, 'y'));
        assertNull(cache.get(SERVER, "old"));
        assertNotNull(cache.get(SERVER, "new"));
        // A clock rollback makes this update older by maxAge, despite later insertion.
        clock.now--;
        assertFalse(cache.put(SERVER, "rolled-back", withSize(SegmentCache.MAX_SIZE_UNITS + 1, 'z')));
        assertNotNull(cache.get(SERVER, "new"));
        assertEquals(1, new SegmentCache(dir, clock).stats().entryCount);
    }

    @Test public void onlyCacheableFetchResultsAreAccepted() {
        for (int status : new int[]{0, 201, 400, 429, 500}) {
            try { new SegmentCache.Response("[]", status, false); fail(); }
            catch (IllegalArgumentException expected) { /* not a cacheable upstream status */ }
        }
        // segmentRequest.ts gates only on status; preserve ok even for a transport
        // validator's status-200 failure rather than inventing a stronger condition.
        assertFalse(new SegmentCache.Response("", 200, false).ok);
    }
}
