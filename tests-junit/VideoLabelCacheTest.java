package top.local.bilihdskip;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Public cache contract; the fake clock is the only substituted boundary. */
public class VideoLabelCacheTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String SERVER = "https://one.example";
    private static final String OTHER = "https://two.example";
    private static final String BV = "BV1xx411c7mD";
    private static final String BASE = "video-label-cache-v1.bin";
    private static final class Time implements VideoLabelCache.Clock {
        long now = 1700000000000L;
        @Override public long nowMillis() { return now; }
    }

    @Test public void emptyObjectIsAHitUntilAbsoluteExpiryAndReadsNeverRenewIt() throws Exception {
        Time time = new Time();
        java.io.File dir = temporary.newFolder();
        VideoLabelCache cache = new VideoLabelCache(dir, time);
        assertNull(cache.get(SERVER, "abcd"));
        assertTrue(cache.put(SERVER, "abcd", "{}"));
        time.now += VideoLabelCache.TTL_MILLIS - 1;
        assertEquals("{}", cache.get(SERVER, "abcd"));
        assertEquals("{}", new VideoLabelCache(dir, time).get(SERVER, "abcd"));
        time.now++;
        assertNull(cache.get(SERVER, "abcd"));
        assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
    }

    private static String block(String category) throws Exception {
        return new JSONObject().put(BV, category).toString();
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n]; Arrays.fill(chars, c); return new String(chars);
    }

    private static String withSize(int units) throws Exception {
        return block(repeat('x', units - 19));
    }

    @Test public void canonicalServerPrefixAndUnknownFirstCategorySurviveRestart() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time);
        String raw = " { \"" + BV + "\" : \"future_category\" } \n";
        cache.put("HTTPS://ONE.EXAMPLE:443/", "ABCD", raw);
        assertEquals("future_category", new JSONObject(cache.get(SERVER, "abcd")).getString(BV));
        assertNull(cache.get(OTHER, "abcd"));
        assertNull(cache.get(SERVER + "/api", "abcd"));
        assertNull(cache.get(SERVER + ":444", "abcd"));
        cache.put(OTHER, "abcd", "{}");
        VideoLabelCache restored = new VideoLabelCache(dir, time);
        assertEquals(2, restored.stats().entryCount);
        assertEquals("{\"" + BV + "\":\"future_category\"}", restored.get(SERVER, "abcd"));
        assertEquals("{}", restored.get(OTHER, "abcd"));
    }

    @Test public void compactJsEscapingCountsUtf16InsteadOfUtf8OrOrgJsonEscapes() throws Exception {
        VideoLabelCache cache = new VideoLabelCache(temporary.newFolder(), new Time());
        cache.put(SERVER, "abcd", block("</\u0085\u2028\uD83D\uDE00"));
        assertEquals("{\"" + BV + "\":\"</\u0085\u2028\uD83D\uDE00\"}", cache.get(SERVER, "abcd"));
        assertEquals(25, cache.stats().sizeUnits);
        cache.put(SERVER, "abcd", "{\"" + BV + "\":\"\\ud800\"}");
        assertEquals(25, cache.stats().sizeUnits);
        assertEquals("{\"" + BV + "\":\"\\ud800\"}", cache.get(SERVER, "abcd"));
        cache.put(SERVER, "abcd", "{\"" + BV + "\":\"\\\"\\\\\\n\\u0001\"}");
        assertEquals(31, cache.stats().sizeUnits);
    }

    @Test public void putRenewsExpiryButReadsAndRestoreDoNot() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time);
        cache.put(SERVER, "abcd", block("sponsor"));
        time.now += VideoLabelCache.TTL_MILLIS - 1;
        cache.put(SERVER, "abcd", block("unknown"));
        time.now++;
        assertEquals("unknown", new JSONObject(new VideoLabelCache(dir, time).get(SERVER, "abcd")).getString(BV));
        time.now += VideoLabelCache.TTL_MILLIS - 1;
        assertNull(cache.get(SERVER, "abcd"));
    }

    @Test public void aggregateQuotaEvictsOldestExpiryAndExactBoundaryFits() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time);
        assertTrue(cache.put(SERVER, "aaaa", withSize(VideoLabelCache.MAX_SIZE_UNITS - 2)));
        time.now++;
        assertTrue(cache.put(OTHER, "bbbb", "{}"));
        assertEquals(512000, cache.stats().sizeUnits);
        cache.get(SERVER, "aaaa");
        time.now++;
        cache.put(SERVER, "cccc", "{}");
        assertNull(cache.get(SERVER, "aaaa"));
        assertEquals("{}", cache.get(OTHER, "bbbb"));
        assertEquals(4, new VideoLabelCache(dir, time).stats().sizeUnits);
        assertFalse(cache.put(SERVER, "dddd", withSize(VideoLabelCache.MAX_SIZE_UNITS + 1)));
        assertEquals(0, cache.stats().entryCount);
    }

    @Test public void clockRollbackUsesExpiryOrderAndExpiredValuesAreCollectedFirst() throws Exception {
        Time time = new Time(); VideoLabelCache cache = new VideoLabelCache(temporary.newFolder(), time);
        cache.put(SERVER, "aaaa", withSize(VideoLabelCache.MAX_SIZE_UNITS));
        time.now--;
        assertFalse(cache.put(SERVER, "bbbb", withSize(VideoLabelCache.MAX_SIZE_UNITS + 1)));
        assertNotNull(cache.get(SERVER, "aaaa"));
        time.now += VideoLabelCache.TTL_MILLIS + 1;
        assertTrue(cache.put(SERVER, "cccc", withSize(VideoLabelCache.MAX_SIZE_UNITS)));
        assertNull(cache.get(SERVER, "aaaa"));
        assertEquals(1, cache.stats().entryCount);
    }

    @Test public void invalidShapesIdentifiersPrefixesAndServersDoNotReplaceGoodValue() throws Exception {
        VideoLabelCache cache = new VideoLabelCache(temporary.newFolder(), new Time());
        cache.put(SERVER, "abcd", "{}");
        String key = "\"" + BV + "\"";
        for (String invalid : new String[]{"[]", "null", "{x:'sponsor'}", "{\"av123\":\"sponsor\"}",
                "{\"BV123\":\"sponsor\"}", "{" + key + ":null}", "{" + key + ":1}",
                "{" + key + ":true}", "{" + key + ":{}}", "{" + key + ":[]}",
                "{" + key + ":\"\"}", "{" + key + ":\"a\",}", "{} trailing",
                "{" + key + ":\"a\"," + key + ":\"b\"}", "{" + key + ":\"\\x12\"}",
                "{" + key + ":" + repeat('[', 20000) + repeat(']', 20000) + "}"}) {
            try { cache.put(SERVER, "abcd", invalid); fail(invalid.substring(0, Math.min(60, invalid.length()))); }
            catch (IllegalArgumentException expected) { assertEquals("{}", cache.get(SERVER, "abcd")); }
        }
        for (String prefix : new String[]{"", "abc", "abcde", "zzzz", "../x", " abcd"}) {
            try { cache.put(SERVER, prefix, "{}"); fail(prefix); }
            catch (IllegalArgumentException expected) { /* invalid cache key */ }
        }
        for (String server : new String[]{"http://one.example", "https://user:pass@one.example",
                "https://one.example?token=x", "https://one.example#fragment", "../../outside",
                "https://one.example:99999"}) {
            try { cache.put(server, "abcd", "{}"); fail(server); }
            catch (IllegalArgumentException expected) { /* invalid namespace */ }
        }
        assertEquals(1, cache.stats().entryCount);
    }

    @Test public void removeClearAndAbandonedTemporaryFilesCannotResurrectData() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time);
        cache.put(SERVER, "abcd", "{}"); cache.put(OTHER, "abcd", block("sponsor"));
        cache.remove(SERVER, "abcd");
        assertNull(new VideoLabelCache(dir, time).get(SERVER, "abcd"));
        assertNotNull(cache.get(OTHER, "abcd"));
        byte[] old = read(new File(dir, BASE));
        cache.clear();
        write(new File(dir, BASE + ".bak"), old); write(new File(dir, BASE + ".tmp"), old);
        VideoLabelCache restored = new VideoLabelCache(dir, time);
        assertEquals(0, restored.stats().entryCount);
        assertEquals(0, restored.stats().sizeUnits);
        assertFalse(new File(dir, BASE + ".tmp").exists());
        assertFalse(new File(dir, BASE + ".bak").exists());
    }

    @Test public void failedCommitKeepsMemoryAndCommittedDiskSnapshot() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time);
        cache.put(SERVER, "aaaa", "{}");
        File blocker = new File(dir, BASE + ".tmp"); assertTrue(blocker.mkdir());
        try { cache.put(SERVER, "bbbb", "{}"); fail(); }
        catch (IOException expected) { assertNull(cache.get(SERVER, "bbbb")); }
        try { cache.clear(); fail(); }
        catch (IOException expected) { assertEquals("{}", cache.get(SERVER, "aaaa")); }
        assertTrue(blocker.delete());
        assertEquals("{}", new VideoLabelCache(dir, time).get(SERVER, "aaaa"));
    }

    @Test public void validMainWinsOtherwiseBackupRecoversAndOrphanTemporaryIsIgnored() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time);
        cache.put(SERVER, "aaaa", "{}");
        File main = new File(dir, BASE), backup = new File(dir, BASE + ".bak");
        byte[] old = read(main);
        write(backup, old); write(main, new byte[]{1, 2, 3});
        assertEquals("{}", new VideoLabelCache(dir, time).get(SERVER, "aaaa"));
        assertTrue(main.renameTo(backup));
        assertEquals("{}", new VideoLabelCache(dir, time).get(SERVER, "aaaa"));
        assertTrue(main.renameTo(new File(dir, BASE + ".tmp")));
        assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
    }

    @Test public void corruptTruncatedAndOversizedSnapshotsAreDiscardedAndRebounded() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time); cache.put(SERVER, "aaaa", "{}");
        File main = new File(dir, BASE); byte[] valid = read(main);
        byte[] corrupt = valid.clone(); corrupt[20] ^= 1; write(main, corrupt);
        assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
        for (int size : new int[]{0, 1, 43, valid.length - 1}) {
            write(main, Arrays.copyOf(valid, size));
            assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
        }
        try (RandomAccessFile out = new RandomAccessFile(main, "rw")) {
            out.setLength(VideoLabelCache.MAX_STORAGE_BYTES + 1L);
        }
        assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
        assertTrue(main.length() <= VideoLabelCache.MAX_STORAGE_BYTES);
    }

    @Test public void recomputedDigestCannotHideInvalidMetadataUtf8OrJson() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time); cache.put(SERVER, "aaaa", "{}");
        File main = new File(dir, BASE); byte[] valid = read(main);
        for (int[] change : new int[][]{{4, 999}, {8, 5001}, {8, -1}, {52, 0},
                {52, 512001}, {56, -1}, {56, Integer.MAX_VALUE}}) {
            byte[] bad = valid.clone(); setInt(bad, change[0], change[1]); rehash(main, bad);
            assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
        }
        for (byte first : new byte[]{(byte) 0xff, '['}) {
            byte[] bad = valid.clone(); bad[60] = first; rehash(main, bad);
            assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
        }
    }

    @Test public void entryCountLimitRestoresAt5000AndEvictsOneOnNextPut() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time); cache.put(SERVER, "aaaa", "{}");
        File main = new File(dir, BASE);
        byte[] seed = Arrays.copyOfRange(read(main), 12, 44);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x564c4331); out.writeInt(1); out.writeInt(5000);
            record(out, seed, time.now + VideoLabelCache.TTL_MILLIS, "{}");
            for (int i = 1; i < 5000; i++) {
                byte[] hash = new byte[32]; setInt(hash, 0, i);
                record(out, hash, time.now + VideoLabelCache.TTL_MILLIS + 1, "{}");
            }
        }
        snapshot(main, bytes.toByteArray());
        cache = new VideoLabelCache(dir, time);
        assertEquals(5000, cache.stats().entryCount);
        assertEquals(10000, cache.stats().sizeUnits);
        cache.put(SERVER, "bbbb", "{}");
        // Same expiry as the seed: stable oldest order evicts the seed first.
        assertNull(cache.get(SERVER, "aaaa"));
        assertEquals("{}", cache.get(SERVER, "bbbb"));
        assertEquals(5000, new VideoLabelCache(dir, time).stats().entryCount);
        assertTrue(main.length() <= VideoLabelCache.MAX_STORAGE_BYTES);
    }

    @Test public void duplicateHashesOrAggregateQuotaRejectChecksummedSnapshot() throws Exception {
        File dir = temporary.newFolder(); Time time = new Time();
        VideoLabelCache cache = new VideoLabelCache(dir, time); cache.put(SERVER, "aaaa", "{}");
        File main = new File(dir, BASE); byte[] seed = Arrays.copyOfRange(read(main), 12, 44);
        for (boolean duplicate : new boolean[]{true, false}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(0x564c4331); out.writeInt(1); out.writeInt(2);
                String value = duplicate ? "{}" : withSize(256001);
                record(out, seed, time.now + VideoLabelCache.TTL_MILLIS, value);
                byte[] other = seed.clone(); if (!duplicate) other[0] ^= 1;
                record(out, other, time.now + VideoLabelCache.TTL_MILLIS, value);
            }
            snapshot(main, bytes.toByteArray());
            assertEquals(0, new VideoLabelCache(dir, time).stats().entryCount);
        }
    }

    private static void record(DataOutputStream out, byte[] hash, long expiry, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(hash); out.writeLong(expiry); out.writeInt(json.length());
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static void snapshot(File file, byte[] payload) throws Exception {
        byte[] data = Arrays.copyOf(payload, payload.length + 32); rehash(file, data);
    }
    private static void setInt(byte[] data, int offset, int value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (24 - i * 8));
    }
    private static void rehash(File file, byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(data, data.length - 32));
        System.arraycopy(digest, 0, data, data.length - 32, 32); write(file, data);
    }
    private static void write(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
    }
    private static byte[] read(File file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }
}
