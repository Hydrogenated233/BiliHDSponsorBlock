package top.local.bilihdskip;

import org.json.JSONTokener;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Persistent full-video LabelBlock cache, using the Chrome 0.17.1 quotas. One instance/owner per directory.
 * Blocking operations: call on the owner's IO executor, never Android's main thread.
 * Logical quota follows upstream JSON.stringify(LabelBlock).length (UTF-16).
 * No Android dependency, background writer, user ID, raw server or raw key on disk.
 */
public final class VideoLabelCache {
    public static final long TTL_MILLIS = 60L * 60 * 1000;
    public static final int MAX_ENTRIES = 5000;
    public static final int MAX_SIZE_UNITS = 500 * 1024;
    // Each UTF-16 code unit needs at most 3 UTF-8 bytes; fixed record overhead
    // is 32 (hashed key) + 8 (expiry) + 4 (logical length) + 4 (payload length).
    public static final int MAX_STORAGE_BYTES = 12 + 48 * MAX_ENTRIES
            + 3 * MAX_SIZE_UNITS + 32;
    private static final int MAGIC = 0x564c4331;
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final String BASE = "video-label-cache-v1.bin";

    public interface Clock { long nowMillis(); }

    public static final class Stats {
        public final int entryCount;
        /** Upstream calls this sizeBytes, but measures UTF-16 units, not bytes. */
        public final long sizeUnits;
        private Stats(int count, long size) { entryCount = count; sizeUnits = size; }
    }

    private static final class Entry {
        final long expiresAt;
        final String response;
        final long size;
        Entry(long expiresAt, String response) {
            this.expiresAt = expiresAt;
            this.response = response;
            this.size = response.length();
        }
    }

    private final File directory;
    private final Clock clock;
    private LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>();

    public VideoLabelCache(File directory) throws IOException {
        this(directory, new Clock() {
            @Override public long nowMillis() { return System.currentTimeMillis(); }
        });
    }

    public VideoLabelCache(File directory, Clock clock) throws IOException {
        if (directory == null || clock == null) throw new IllegalArgumentException("Missing dependency");
        this.directory = directory.getCanonicalFile();
        this.clock = clock;
        if (!this.directory.isDirectory() && !this.directory.mkdirs()) {
            throw new IOException("Cannot create cache directory");
        }
        restore();
    }

    /** Miss returns null. A hit neither renews expiry nor changes eviction order. */
    public synchronized String get(String server, String prefix) throws IOException {
        String hash = cacheKey(server, prefix);
        Entry entry = entries.get(hash);
        if (entry == null) return null;
        if (expired(entry, clock.nowMillis())) {
            LinkedHashMap<String, Entry> next = copy();
            next.remove(hash);
            commit(next);
            return null;
        }
        return entry.response;
    }

    /**
     * Insert/update and synchronously persist. Returns whether the new value survived
     * total-quota eviction. An over-budget value participates in oldest-first GC;
     * it is not rejected by a separate UTF-8 limit.
     */
    public synchronized boolean put(String server, String prefix, String labelBlockJson) throws IOException {
        String normalized = normalizeBlock(labelBlockJson);
        String hash = cacheKey(server, prefix);
        long now = clock.nowMillis();
        if (now > Long.MAX_VALUE - TTL_MILLIS || now <= -TTL_MILLIS) {
            throw new IllegalArgumentException("Clock out of range");
        }
        LinkedHashMap<String, Entry> next = copy();
        next.remove(hash);
        next.put(hash, new Entry(now + TTL_MILLIS, normalized));
        collect(next, now);
        commit(next);
        return next.containsKey(hash);
    }

    /** Invalidation works regardless of the caller's enableCache setting. */
    public synchronized void remove(String server, String prefix) throws IOException {
        LinkedHashMap<String, Entry> next = copy();
        next.remove(cacheKey(server, prefix));
        collect(next, clock.nowMillis());
        commit(next);
    }

    /** Clear every server namespace. Commits an empty snapshot, including on failure recovery. */
    public synchronized void clear() throws IOException {
        commit(new LinkedHashMap<String, Entry>());
    }

    /** Reload/recover disk state, discard expired entries and validate/recompute all sizes. */
    private void restore() throws IOException {
        File main = owned(BASE), backup = owned(BASE + ".bak");
        LinkedHashMap<String, Entry> loaded = read(main);
        if (loaded == null) {
            loaded = read(backup);
            if (loaded != null) {
                // Restore the sole valid committed snapshot before any rewrite. Do
                // not rotate a corrupt main over a valid backup during recovery.
                deleteRequired(main);
                if (!backup.renameTo(main)) throw new IOException("Cannot recover cache backup");
            }
        }
        if (loaded == null) loaded = new LinkedHashMap<String, Entry>();
        collect(loaded, clock.nowMillis());
        // Replace corrupt/oversized state and stale recovery files with validated state.
        // Existing in-memory state remains unchanged on an IO failure.
        persist(loaded);
        entries = loaded;
    }

    public synchronized Stats stats() throws IOException {
        LinkedHashMap<String, Entry> next = copy();
        collect(next, clock.nowMillis());
        if (next.size() != entries.size()) commit(next);
        return new Stats(entries.size(), total(entries));
    }

    private LinkedHashMap<String, Entry> copy() {
        return new LinkedHashMap<String, Entry>(entries);
    }

    private void commit(LinkedHashMap<String, Entry> next) throws IOException {
        persist(next);
        entries = next;
    }

    private static boolean expired(Entry entry, long now) {
        return entry.expiresAt == 0 || now >= entry.expiresAt;
    }

    private static long total(Map<String, Entry> map) {
        long size = 0;
        for (Entry entry : map.values()) size += entry.size;
        return size;
    }

    private static void collect(LinkedHashMap<String, Entry> map, long now) {
        for (Iterator<Entry> it = map.values().iterator(); it.hasNext();) {
            if (expired(it.next(), now)) it.remove();
        }
        long size = total(map);
        if (map.size() <= MAX_ENTRIES && size <= MAX_SIZE_UNITS) return;
        List<Map.Entry<String, Entry>> oldest = new ArrayList<Map.Entry<String, Entry>>(map.entrySet());
        // Stable sort preserves insertion order for identical maxAge, like upstream.
        Collections.sort(oldest, new Comparator<Map.Entry<String, Entry>>() {
            @Override public int compare(Map.Entry<String, Entry> a, Map.Entry<String, Entry> b) {
                return Long.compare(a.getValue().expiresAt, b.getValue().expiresAt);
            }
        });
        for (Map.Entry<String, Entry> victim : oldest) {
            if (map.size() <= MAX_ENTRIES && size <= MAX_SIZE_UNITS) break;
            map.remove(victim.getKey());
            size -= victim.getValue().size;
        }
    }

    private File owned(String name) throws IOException {
        File file = new File(directory, name);
        if (!directory.equals(file.getCanonicalFile().getParentFile()) || file.isDirectory()) {
            throw new IOException("Invalid cache storage path");
        }
        return file;
    }

    private static String cacheKey(String server, String prefix) {
        if (server == null || prefix == null || !prefix.matches("[0-9a-fA-F]{4}")) {
            throw new IllegalArgumentException("Expected HTTPS server and four hex prefix characters");
        }
        final String canonical;
        try {
            URI uri = new URI(server).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() < -1
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Expected HTTPS server without credentials/query/fragment");
            }
            String path = uri.getRawPath();
            if (path == null) path = "";
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            int port = uri.getPort();
            canonical = "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 || port == 443 ? "" : ":" + port) + path;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid server", e);
        }
        MessageDigest digest = sha256();
        hashString(digest, canonical);
        hashString(digest, prefix.toLowerCase(Locale.ROOT));
        return hex(digest.digest());
    }
    private static void hashString(MessageDigest digest, String value) {
        int n = value.length();
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (n >>> shift));
        for (int i = 0; i < n; i++) {
            char c = value.charAt(i);
            digest.update((byte) (c >>> 8));
            digest.update((byte) c);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = digits.charAt((bytes[i] >>> 4) & 15);
            out[2 * i + 1] = digits.charAt(bytes[i] & 15);
        }
        return new String(out);
    }

    private static byte[] unhex(String hash) {
        byte[] bytes = new byte[DIGEST_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        String digits = "0123456789abcdef";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (Character.isHighSurrogate(c) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(++i));
                    } else if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) out.append(digits.charAt((c >>> shift) & 15));
                    } else out.append(c);
            }
        }
        out.append('"');
    }

    /**
     * LabelBlock only: strict flat JSON, actual BV identifiers and nonempty first
     * category strings. No category allowlist: selection is evaluated by the caller.
     * This also avoids org.json's permissive JSON grammar and recursive depth bombs.
     */
    private static String normalizeBlock(String json) {
        if (json == null) throw new IllegalArgumentException("Missing LabelBlock");
        FlatBlock parser = new FlatBlock(json);
        StringBuilder out = new StringBuilder();
        Set<String> keys = new HashSet<String>();
        parser.expect('{');
        out.append('{');
        if (!parser.take('}')) {
            do {
                String key = parser.string();
                if (!key.matches("BV[0-9A-Za-z]{10}") || !keys.add(key)) {
                    throw new IllegalArgumentException("Invalid or duplicate BVID");
                }
                parser.expect(':');
                String value = parser.string();
                if (value.length() == 0) throw new IllegalArgumentException("Empty first category");
                if (keys.size() > 1) out.append(',');
                quote(out, key);
                out.append(':');
                quote(out, value);
            } while (parser.take(','));
            parser.expect('}');
        }
        parser.space();
        if (parser.position != json.length()) throw new IllegalArgumentException("Trailing LabelBlock data");
        return out.append('}').toString();
    }

    private static final class FlatBlock {
        final String json;
        int position;
        FlatBlock(String json) { this.json = json; }
        void space() {
            while (position < json.length()) {
                char c = json.charAt(position);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                position++;
            }
        }
        boolean take(char c) {
            space();
            if (position == json.length() || json.charAt(position) != c) return false;
            position++;
            return true;
        }
        void expect(char c) {
            if (!take(c)) throw new IllegalArgumentException("Invalid LabelBlock JSON");
        }
        String string() {
            space();
            int start = position;
            if (position == json.length() || json.charAt(position++) != '"') {
                throw new IllegalArgumentException("Expected LabelBlock string");
            }
            boolean closed = false;
            while (position < json.length()) {
                char c = json.charAt(position++);
                if (c == '"') { closed = true; break; }
                if (c < 0x20) throw new IllegalArgumentException("Unescaped JSON control character");
                if (c == '\\') {
                    if (position == json.length()) throw new IllegalArgumentException("Truncated escape");
                    c = json.charAt(position++);
                    if (c == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (position == json.length()
                                    || "0123456789abcdefABCDEF".indexOf(json.charAt(position++)) < 0) {
                                throw new IllegalArgumentException("Invalid Unicode escape");
                            }
                        }
                    } else if ("\"\\/bfnrt".indexOf(c) < 0) {
                        throw new IllegalArgumentException("Invalid JSON escape");
                    }
                }
            }
            if (!closed) throw new IllegalArgumentException("Unterminated LabelBlock string");
            try {
                Object value = new JSONTokener(json.substring(start, position)).nextValue();
                if (!(value instanceof String)) throw new IllegalArgumentException("Expected string");
                return (String) value;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid LabelBlock string", e);
            }
        }
    }

    private static String parseStored(String json) throws IOException {
        try {
            String canonical = normalizeBlock(json);
            if (!canonical.equals(json)) throw new IOException("Noncanonical LabelBlock");
            return canonical;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid cached LabelBlock", e);
        }
    }
    private LinkedHashMap<String, Entry> read(File file) throws IOException {
        if (!file.exists()) return null;
        if (file.length() < 12 + DIGEST_BYTES || file.length() > MAX_STORAGE_BYTES) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (bytes.size() + n > MAX_STORAGE_BYTES) return null;
                bytes.write(buffer, 0, n);
            }
        }
        byte[] data = bytes.toByteArray();
        int payloadLength = data.length - DIGEST_BYTES;
        if (payloadLength < 12) return null;
        MessageDigest digest = sha256();
        digest.update(data, 0, payloadLength);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(data, payloadLength, data.length))) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 0, payloadLength))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) return null;
            LinkedHashMap<String, Entry> loaded = new LinkedHashMap<String, Entry>();
            long size = 0;
            for (int i = 0; i < count; i++) {
                byte[] hash = new byte[DIGEST_BYTES];
                in.readFully(hash);
                long expiresAt = in.readLong();
                int expectedSize = in.readInt(), length = in.readInt();
                if (expiresAt <= 0 || expectedSize < 0 || expectedSize > MAX_SIZE_UNITS
                        || length < 0 || length > 3 * MAX_SIZE_UNITS || length > in.available()) return null;
                byte[] encoded = new byte[length];
                in.readFully(encoded);
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString();
                Entry entry = new Entry(expiresAt, parseStored(json));
                size += entry.size;
                if (entry.size != expectedSize || size > MAX_SIZE_UNITS || loaded.put(hex(hash), entry) != null) return null;
            }
            return in.available() == 0 ? loaded : null;
        } catch (IOException | IllegalArgumentException e) { return null; }
    }

    private void persist(LinkedHashMap<String, Entry> next) throws IOException {
        if (next.size() > MAX_ENTRIES || total(next) > MAX_SIZE_UNITS) throw new IOException("Cache quota exceeded");
        File main = owned(BASE), backup = owned(BASE + ".bak"), temporary = owned(BASE + ".tmp");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(next.size());
            for (Map.Entry<String, Entry> record : next.entrySet()) {
                Entry entry = record.getValue();
                byte[] payload = entry.response.getBytes(StandardCharsets.UTF_8);
                out.write(unhex(record.getKey()));
                out.writeLong(entry.expiresAt);
                out.writeInt((int) entry.size);
                out.writeInt(payload.length);
                out.write(payload);
            }
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length + DIGEST_BYTES > MAX_STORAGE_BYTES) throw new IOException("Cache storage quota exceeded");
        // Only fixed child names are touched; a second instance/process must not write here.
        deleteRequired(temporary);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(payload);
            out.write(sha256().digest(payload));
            out.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            throw e;
        }
        // .bak stays intact until a complete new snapshot is ready. A valid main wins
        // on recovery; otherwise a valid backup wins. An orphan .tmp is never read.
        if (main.exists()) {
            deleteRequired(backup);
            if (!main.renameTo(backup)) { temporary.delete(); throw new IOException("Cannot back up cache"); }
        }
        if (!temporary.renameTo(main)) {
            if (backup.exists()) backup.renameTo(main);
            temporary.delete();
            throw new IOException("Cannot commit cache");
        }
        backup.delete();
    }

    private static void deleteRequired(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove cache recovery file");
    }
}
