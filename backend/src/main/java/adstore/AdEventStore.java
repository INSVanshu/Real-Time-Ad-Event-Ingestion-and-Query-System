package adstore;

import java.io.File;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * AdEventStore - the complete DSA component of the project, in ONE file.
 *
 * Data structure: a time-segmented BIPARTITE GRAPH (users <-> campaigns).
 * Every ad event is one edge (campaign, user, time, type) stored in two sorted
 * adjacency lists (campaign side and user side) inside the segment of its hour.
 *
 * Aggregation layout (each "has-a" the next, exactly like the class diagram):
 *
 *   AdEventStore
 *     +-- IdMap campaigns, IdMap users        (name -> dense int id, own hash table)
 *     +-- BipartiteGraph
 *     |     +-- TimeSegment[]                 (one per hour, sorted by hour)
 *     |           +-- AdjacencyList[] byCampaign   (campaign side)
 *     |           +-- AdjacencyList[] byUser       (user side)
 *     |           +-- WriteAheadLog               (optional, one file per hour)
 *     +-- query methods, seal / expire / recover
 *
 * Only primitive arrays are used inside the structure (no java.util collections).
 * Run the worked example from the report:  java AdEventStore.java
 */
public class AdEventStore {

    // ------------------------------------------------------------------ constants
    public static final byte IMP = 0, CLICK = 1, CONV = 2;
    public static final String[] TYPE_NAMES = {"imp", "click", "conv"};
    public static final int SEGMENT_SECONDS = 3600;   // one segment per hour
    public static final int BYTES_PER_EDGE = 26;      // report layout: 13 bytes per side x 2 sides (this code uses 9 per side)

    public static byte parseType(String s) {
        for (byte i = 0; i < TYPE_NAMES.length; i++) if (TYPE_NAMES[i].equalsIgnoreCase(s)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ result objects
    public static final class Edge {
        public final int time;
        public final String campaign, user;
        public final byte type;
        Edge(int time, String campaign, String user, byte type) {
            this.time = time; this.campaign = campaign; this.user = user; this.type = type;
        }
    }

    public static final class Related {
        public final String campaign;
        public final int sharedUsers;
        Related(String campaign, int sharedUsers) { this.campaign = campaign; this.sharedUsers = sharedUsers; }
    }

    public static final class SegmentInfo {
        public final int hour, edges;
        public final boolean sealed;
        SegmentInfo(int hour, int edges, boolean sealed) { this.hour = hour; this.edges = edges; this.sealed = sealed; }
    }

    public static final class Stats {
        public long accepted, rejected, edges, edgeBytes;
        public int segments, sealed, campaigns, users;
        public SegmentInfo[] segmentList;
    }

    // ------------------------------------------------------------------ IdMap (hash table, open addressing)
    private static final class IdMap {
        private String[] slotKey = new String[64];
        private int[] slotId = new int[64];
        private String[] names = new String[32];
        int size;

        int get(String key) { int i = slot(key); return slotKey[i] == null ? -1 : slotId[i]; }

        int getOrAdd(String key) {
            int i = slot(key);
            if (slotKey[i] != null) return slotId[i];
            if ((size + 1) * 2 > slotKey.length) { rehash(); i = slot(key); }
            slotKey[i] = key;
            slotId[i] = size;
            if (size == names.length) names = Arrays.copyOf(names, size * 2);
            names[size] = key;
            return size++;
        }

        String nameOf(int id) { return names[id]; }

        private int slot(String key) {
            int mask = slotKey.length - 1;
            int h = key.hashCode();
            int i = (h ^ (h >>> 16)) & mask;
            while (slotKey[i] != null && !slotKey[i].equals(key)) i = (i + 1) & mask;   // linear probing
            return i;
        }

        private void rehash() {
            String[] oldKeys = slotKey;
            int[] oldIds = slotId;
            slotKey = new String[oldKeys.length * 2];
            slotId = new int[oldKeys.length * 2];
            for (int j = 0; j < oldKeys.length; j++) {
                if (oldKeys[j] == null) continue;
                int i = slot(oldKeys[j]);
                slotKey[i] = oldKeys[j];
                slotId[i] = oldIds[j];
            }
        }
    }

    // ------------------------------------------------------------------ AdjacencyList (sorted by time)
    private static final class AdjacencyList {
        int[] time = new int[4];
        int[] other = new int[4];      // id of the vertex on the other side
        byte[] type = new byte[4];
        int size;

        /** Normal case: append at the end, O(1). Late event: sorted insert near the tail. */
        void add(int t, int o, byte ty) {
            if (size == time.length) {
                time = Arrays.copyOf(time, size * 2);
                other = Arrays.copyOf(other, size * 2);
                type = Arrays.copyOf(type, size * 2);
            }
            int pos = (size > 0 && t < time[size - 1]) ? upperBound(t) : size;
            if (pos < size) {
                System.arraycopy(time, pos, time, pos + 1, size - pos);
                System.arraycopy(other, pos, other, pos + 1, size - pos);
                System.arraycopy(type, pos, type, pos + 1, size - pos);
            }
            time[pos] = t; other[pos] = o; type[pos] = ty;
            size++;
        }

        /** first index whose time >= t (binary search) */
        int lowerBound(int t) {
            int lo = 0, hi = size;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (time[mid] < t) lo = mid + 1; else hi = mid; }
            return lo;
        }

        /** first index whose time > t (binary search) */
        int upperBound(int t) {
            int lo = 0, hi = size;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (time[mid] <= t) lo = mid + 1; else hi = mid; }
            return lo;
        }
    }

    // ------------------------------------------------------------------ WriteAheadLog (one small file per hour)
    private static final class WriteAheadLog {
        private final PrintWriter out;
        WriteAheadLog(File file) throws IOException { out = new PrintWriter(new BufferedWriter(new FileWriter(file, true))); }
        void append(String line) { out.println(line); out.flush(); }
        void close() { out.close(); }
    }

    // ------------------------------------------------------------------ TimeSegment (one hour of edges)
    private static final class TimeSegment {
        final int hour;
        boolean sealed;
        int edges;
        AdjacencyList[] byCampaign = new AdjacencyList[16];   // index = campaign id
        AdjacencyList[] byUser = new AdjacencyList[16];       // index = user id
        WriteAheadLog wal;

        TimeSegment(int hour) { this.hour = hour; }

        void add(int c, int u, int t, byte ty) {
            byCampaign = ensure(byCampaign, c);
            byUser = ensure(byUser, u);
            if (byCampaign[c] == null) byCampaign[c] = new AdjacencyList();
            if (byUser[u] == null) byUser[u] = new AdjacencyList();
            byCampaign[c].add(t, u, ty);      // same edge, stored twice:
            byUser[u].add(t, c, ty);          // once per side
            edges++;
        }

        AdjacencyList campaign(int c) { return c < byCampaign.length ? byCampaign[c] : null; }
        AdjacencyList user(int u) { return u < byUser.length ? byUser[u] : null; }

        boolean overlaps(int from, int to) { return hour * SEGMENT_SECONDS < to && (hour + 1) * SEGMENT_SECONDS > from; }

        private static AdjacencyList[] ensure(AdjacencyList[] a, int idx) {
            return idx < a.length ? a : Arrays.copyOf(a, Math.max(idx + 1, a.length * 2));
        }
    }

    // ------------------------------------------------------------------ BipartiteGraph (segments sorted by hour)
    private static final class BipartiteGraph {
        TimeSegment[] segs = new TimeSegment[8];
        int count;

        /** index of the hour, or -(insertion point + 1) when absent */
        int indexOf(int hour) {
            int lo = 0, hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (segs[mid].hour < hour) lo = mid + 1;
                else if (segs[mid].hour > hour) hi = mid - 1;
                else return mid;
            }
            return -(lo + 1);
        }

        TimeSegment find(int hour) { int i = indexOf(hour); return i >= 0 ? segs[i] : null; }

        TimeSegment insert(int hour) {
            int pos = -indexOf(hour) - 1;
            if (count == segs.length) segs = Arrays.copyOf(segs, count * 2);
            System.arraycopy(segs, pos, segs, pos + 1, count - pos);
            segs[pos] = new TimeSegment(hour);
            count++;
            return segs[pos];
        }

        void removeFirst(int n) {
            System.arraycopy(segs, n, segs, 0, count - n);
            Arrays.fill(segs, count - n, count, null);
            count -= n;
        }
    }

    // ================================================================== the store itself
    private final File dir;               // null = pure in-memory, otherwise WAL + segment files live here
    private final int lateness;           // seconds an hour stays open after it ends (late events)
    private IdMap campaigns = new IdMap();
    private IdMap users = new IdMap();
    private BipartiteGraph graph = new BipartiteGraph();
    private int maxTime = -1;
    private int nextSealTime = Integer.MAX_VALUE;
    private long accepted, rejected;
    private boolean replaying;

    public AdEventStore() { this(null, 60); }

    public AdEventStore(File dir, int latenessSeconds) {
        this.dir = dir;
        this.lateness = latenessSeconds;
        if (dir != null) dir.mkdirs();
    }

    // ------------------------------------------------------------------ O1: write an event (add edge)
    public synchronized boolean addEvent(int time, String campaign, String user, byte type) {
        int hour = time / SEGMENT_SECONDS;
        boolean bad = time < 0 || type < 0 || type > 2 || !validName(campaign) || !validName(user);
        boolean tooLate = !replaying && (hour + 1) * SEGMENT_SECONDS + lateness <= maxTime;   // its hour is already sealed
        if (bad || tooLate) { rejected++; return false; }

        TimeSegment seg = graph.find(hour);
        if (seg == null) {
            seg = graph.insert(hour);
            nextSealTime = Math.min(nextSealTime, (hour + 1) * SEGMENT_SECONDS + lateness);
        }
        if (seg.sealed) { rejected++; return false; }

        if (dir != null && !replaying) {            // 1) write-ahead log (sequential append)
            try {
                if (seg.wal == null) seg.wal = new WriteAheadLog(new File(dir, "wal-" + hour + ".log"));
                seg.wal.append(line(time, campaign, user, type));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        seg.add(campaigns.getOrAdd(campaign), users.getOrAdd(user), time, type);   // 2) two list appends
        accepted++;

        if (time > maxTime) maxTime = time;
        if (!replaying && maxTime >= nextSealTime) sealDue();                      // 3) seal finished hours
        return true;
    }

    // ------------------------------------------------------------------ O2: campaign time window  [from, to)
    public synchronized Edge[] campaignEdges(String campaign, int from, int to) {
        int c = campaigns.get(campaign);
        return c < 0 ? new Edge[0] : collect(c, true, from, to);
    }

    // ------------------------------------------------------------------ O3: user history  [from, to)
    public synchronized Edge[] userEdges(String user, int from, int to) {
        int u = users.get(user);
        return u < 0 ? new Edge[0] : collect(u, false, from, to);
    }

    // ------------------------------------------------------------------ O4a: unique reach of a campaign
    public synchronized int uniqueReach(String campaign, int from, int to) {
        int c = campaigns.get(campaign);
        if (c < 0) return 0;
        boolean[] seen = new boolean[users.size];
        int reach = 0;
        for (int s = 0; s < graph.count; s++) {
            TimeSegment seg = graph.segs[s];
            AdjacencyList l = seg.overlaps(from, to) ? seg.campaign(c) : null;
            if (l == null) continue;
            int hi = l.lowerBound(to);
            for (int i = l.lowerBound(from); i < hi; i++) {
                if (!seen[l.other[i]]) { seen[l.other[i]] = true; reach++; }
            }
        }
        return reach;
    }

    // ------------------------------------------------------------------ O4b: frequency cap (impressions of a user to a campaign)
    public synchronized int frequency(String campaign, String user, int from, int to) {
        int c = campaigns.get(campaign), u = users.get(user), n = 0;
        if (c < 0 || u < 0) return 0;
        for (int s = 0; s < graph.count; s++) {
            TimeSegment seg = graph.segs[s];
            AdjacencyList l = seg.overlaps(from, to) ? seg.user(u) : null;
            if (l == null) continue;
            int hi = l.lowerBound(to);
            for (int i = l.lowerBound(from); i < hi; i++) if (l.other[i] == c && l.type[i] == IMP) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ O4c: related campaigns (two hops)
    /** Users who clicked campaign c -> their other campaigns -> number of shared users per campaign. */
    public synchronized Related[] related(String campaign, int from, int to) {
        int c = campaigns.get(campaign);
        if (c < 0) return new Related[0];
        int[] shared = new int[campaigns.size];
        int[] stamp = new int[campaigns.size];          // "already counted for this user" marker
        boolean[] visited = new boolean[users.size];
        for (int s = 0; s < graph.count; s++) {
            TimeSegment seg = graph.segs[s];
            AdjacencyList l = seg.overlaps(from, to) ? seg.campaign(c) : null;
            if (l == null) continue;
            int hi = l.lowerBound(to);
            for (int i = l.lowerBound(from); i < hi; i++) {
                int u = l.other[i];
                if (l.type[i] != CLICK || visited[u]) continue;
                visited[u] = true;                       // hop 1: user u clicked c
                for (int s2 = 0; s2 < graph.count; s2++) {   // hop 2: everything else u did
                    TimeSegment seg2 = graph.segs[s2];
                    AdjacencyList ul = seg2.overlaps(from, to) ? seg2.user(u) : null;
                    if (ul == null) continue;
                    int uhi = ul.lowerBound(to);
                    for (int j = ul.lowerBound(from); j < uhi; j++) {
                        int o = ul.other[j];
                        if (o != c && stamp[o] != u + 1) { stamp[o] = u + 1; shared[o]++; }
                    }
                }
            }
        }
        int n = 0;
        for (int k = 0; k < shared.length; k++) if (shared[k] > 0) n++;
        Related[] out = new Related[n];
        n = 0;
        for (int k = 0; k < shared.length; k++) if (shared[k] > 0) out[n++] = new Related(campaigns.nameOf(k), shared[k]);
        for (int i = 1; i < out.length; i++) {            // insertion sort, most shared users first
            Related x = out[i];
            int j = i - 1;
            while (j >= 0 && out[j].sharedUsers < x.sharedUsers) { out[j + 1] = out[j]; j--; }
            out[j + 1] = x;
        }
        return out;
    }

    // ------------------------------------------------------------------ helper for the dashboard: some recent edges
    public synchronized Edge[] sample(int from, int to, int max) {
        Edge[] out = new Edge[Math.max(max, 0)];
        int n = 0;
        int perCampaign = Math.max(1, max / Math.max(1, campaigns.size));    // fair share, so one hot campaign cannot fill the sample
        for (int s = graph.count - 1; s >= 0 && n < max; s--) {
            TimeSegment seg = graph.segs[s];
            if (!seg.overlaps(from, to)) continue;
            for (int c = 0; c < seg.byCampaign.length && n < max; c++) {
                AdjacencyList l = seg.byCampaign[c];
                if (l == null) continue;
                int lo = l.lowerBound(from), taken = 0;
                for (int i = l.lowerBound(to) - 1; i >= lo && n < max && taken < perCampaign; i--, taken++)
                    out[n++] = new Edge(l.time[i], campaigns.nameOf(c), users.nameOf(l.other[i]), l.type[i]);
            }
        }
        return Arrays.copyOf(out, n);
    }

    // ------------------------------------------------------------------ O6: expire old data (drop whole segments)
    public synchronized int dropBefore(int time) {
        int i = 0, dropped = 0;
        while (i < graph.count && (graph.segs[i].hour + 1) * SEGMENT_SECONDS <= time) {
            TimeSegment s = graph.segs[i++];
            dropped += s.edges;
            if (s.wal != null) s.wal.close();
            if (dir != null) {
                new File(dir, "seg-" + s.hour + ".edges").delete();
                new File(dir, "wal-" + s.hour + ".log").delete();
            }
        }
        graph.removeFirst(i);          // surviving segments are never rewritten
        return dropped;
    }

    // ------------------------------------------------------------------ statistics
    public synchronized Stats stats() {
        Stats st = new Stats();
        st.accepted = accepted;
        st.rejected = rejected;
        st.segments = graph.count;
        st.campaigns = campaigns.size;
        st.users = users.size;
        st.segmentList = new SegmentInfo[graph.count];
        for (int s = 0; s < graph.count; s++) {
            TimeSegment seg = graph.segs[s];
            st.segmentList[s] = new SegmentInfo(seg.hour, seg.edges, seg.sealed);
            st.edges += seg.edges;
            if (seg.sealed) st.sealed++;
        }
        st.edgeBytes = st.edges * BYTES_PER_EDGE;
        return st;
    }

    public synchronized void reset() {
        for (int s = 0; s < graph.count; s++) if (graph.segs[s].wal != null) graph.segs[s].wal.close();
        if (dir != null && dir.listFiles() != null)
            for (File f : dir.listFiles()) if (f.getName().startsWith("seg-") || f.getName().startsWith("wal-")) f.delete();
        campaigns = new IdMap();
        users = new IdMap();
        graph = new BipartiteGraph();
        maxTime = -1;
        nextSealTime = Integer.MAX_VALUE;
        accepted = rejected = 0;
    }

    // ------------------------------------------------------------------ O7: crash recovery (replay segment files + logs)
    public synchronized int recover() {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        replaying = true;
        try {
            for (File f : files) {
                String name = f.getName();
                boolean isSeg = name.startsWith("seg-") && name.endsWith(".edges");
                boolean isWal = name.startsWith("wal-") && name.endsWith(".log");
                if (isWal && new File(dir, "seg-" + name.substring(4, name.length() - 4) + ".edges").exists()) {
                    f.delete();                       // segment already sealed, its log is obsolete
                    continue;
                }
                if (!isSeg && !isWal) continue;
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String ln;
                    while ((ln = r.readLine()) != null) {
                        String[] p = ln.split(",");
                        try {
                            if (p.length == 4 && addEvent(Integer.parseInt(p[0]), p[1], p[2], parseType(p[3]))) n++;
                        } catch (NumberFormatException torn) { /* half-written last line: skip */ }
                    }
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        } finally { replaying = false; }
        if (maxTime >= nextSealTime) sealDue();
        return n;
    }

    // ================================================================== private helpers
    /** Collect edges of one vertex in [from, to): one binary search per overlapping segment. */
    private Edge[] collect(int v, boolean campaignSide, int from, int to) {
        Edge[] out = new Edge[16];
        int n = 0;
        for (int s = 0; s < graph.count; s++) {
            TimeSegment seg = graph.segs[s];
            if (!seg.overlaps(from, to)) continue;
            AdjacencyList l = campaignSide ? seg.campaign(v) : seg.user(v);
            if (l == null) continue;
            int hi = l.lowerBound(to);
            for (int i = l.lowerBound(from); i < hi; i++) {
                if (n == out.length) out = Arrays.copyOf(out, n * 2);
                out[n++] = campaignSide
                        ? new Edge(l.time[i], campaigns.nameOf(v), users.nameOf(l.other[i]), l.type[i])
                        : new Edge(l.time[i], campaigns.nameOf(l.other[i]), users.nameOf(v), l.type[i]);
            }
        }
        return Arrays.copyOf(out, n);      // segments are in hour order, so the result is in time order
    }

    /** Seal every segment whose hour (plus the lateness window) has passed. */
    private void sealDue() {
        int next = Integer.MAX_VALUE;
        for (int i = 0; i < graph.count; i++) {
            TimeSegment s = graph.segs[i];
            if (s.sealed) continue;
            int due = (s.hour + 1) * SEGMENT_SECONDS + lateness;
            if (due <= maxTime) seal(s); else next = Math.min(next, due);
        }
        nextSealTime = next;
    }

    /** Write the finished hour to disk in one sequential pass, then remove its log. */
    private void seal(TimeSegment seg) {
        if (dir != null) {
            File tmp = new File(dir, "seg-" + seg.hour + ".tmp");
            File fin = new File(dir, "seg-" + seg.hour + ".edges");
            try (PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(tmp)))) {
                for (int c = 0; c < seg.byCampaign.length; c++) {
                    AdjacencyList l = seg.byCampaign[c];
                    if (l == null) continue;
                    for (int i = 0; i < l.size; i++)
                        w.println(line(l.time[i], campaigns.nameOf(c), users.nameOf(l.other[i]), l.type[i]));
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
            tmp.renameTo(fin);                                 // atomic publish of the immutable file
            if (seg.wal != null) { seg.wal.close(); seg.wal = null; }
            new File(dir, "wal-" + seg.hour + ".log").delete();
        }
        seg.sealed = true;                                     // edges of a sealed segment never change
    }

    private static String line(int t, String c, String u, byte ty) { return t + "," + c + "," + u + "," + TYPE_NAMES[ty]; }

    private static boolean validName(String s) {
        return s != null && !s.isEmpty() && s.indexOf(',') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0;
    }

    // ================================================================== demo: the worked example of the report (section 5.1.3)
    public static void main(String[] args) {
        AdEventStore s = new AdEventStore();
        s.addEvent(at(9, 5), "c1", "u1", IMP);
        s.addEvent(at(9, 20), "c1", "u2", IMP);
        s.addEvent(at(9, 40), "c2", "u1", CLICK);
        s.addEvent(at(10, 10), "c1", "u2", CLICK);     // new hour: segment 09 is sealed
        s.addEvent(at(10, 30), "c2", "u3", IMP);
        s.addEvent(at(10, 45), "c3", "u2", IMP);

        Stats st = s.stats();
        System.out.println("segments=" + st.segments + " sealed=" + st.sealed + " edges=" + st.edges);
        show("campaignEdges(c1, 09:00-10:00)", s.campaignEdges("c1", at(9, 0), at(10, 0)));
        show("userEdges(u2, 09:00-11:00)", s.userEdges("u2", at(9, 0), at(11, 0)));
        System.out.println("uniqueReach(c1, 09:00-11:00) = " + s.uniqueReach("c1", at(9, 0), at(11, 0)));
        System.out.println("frequency(c1, u2, 09:00-11:00) = " + s.frequency("c1", "u2", at(9, 0), at(11, 0)));
        for (Related r : s.related("c2", at(9, 0), at(11, 0))) System.out.println("related(c2): " + r.campaign + " x" + r.sharedUsers);
        System.out.println("dropBefore(10:00) removed " + s.dropBefore(at(10, 0)) + " edges, left " + s.stats().edges);
    }

    private static int at(int h, int m) { return h * 3600 + m * 60; }

    private static void show(String title, Edge[] edges) {
        System.out.println(title);
        for (Edge e : edges)
            System.out.printf("   %02d:%02d  %s  %s  %s%n", e.time / 3600, e.time % 3600 / 60, e.campaign, e.user, TYPE_NAMES[e.type]);
    }
}
