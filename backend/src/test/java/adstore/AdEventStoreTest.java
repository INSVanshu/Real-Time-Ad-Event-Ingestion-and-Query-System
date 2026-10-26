package adstore;

import adstore.AdEventStore.Edge;
import adstore.AdEventStore.Related;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;


public class AdEventStoreTest {

    static int passed, failed;

    static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("  FAIL: " + name); }
    }

    
    static int n;
    static int[] T = new int[300_000], C = new int[300_000], U = new int[300_000];
    static byte[] Y = new byte[300_000];

    public static void main(String[] args) throws Exception {
        workedExample();
        randomVsBruteForce();
        lateEvents();
        recovery();
        System.out.println(passed + " checks passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

   
    static void workedExample() {
        System.out.println("worked example");
        AdEventStore s = new AdEventStore();
        s.addEvent(at(9, 5), "c1", "u1", AdEventStore.IMP);
        s.addEvent(at(9, 20), "c1", "u2", AdEventStore.IMP);
        s.addEvent(at(9, 40), "c2", "u1", AdEventStore.CLICK);
        s.addEvent(at(10, 10), "c1", "u2", AdEventStore.CLICK);
        s.addEvent(at(10, 30), "c2", "u3", AdEventStore.IMP);
        s.addEvent(at(10, 45), "c3", "u2", AdEventStore.IMP);

        check(s.stats().segments == 2 && s.stats().sealed == 1, "segment 09 sealed, segment 10 hot");
        check(s.campaignEdges("c1", at(9, 0), at(10, 0)).length == 2, "campaign window c1 09-10 has 2 edges");
        check(s.userEdges("u2", at(9, 0), at(11, 0)).length == 3, "user history u2 has 3 edges");
        check(s.uniqueReach("c1", at(9, 0), at(11, 0)) == 2, "unique reach of c1 is 2");
        check(s.frequency("c1", "u2", at(9, 0), at(11, 0)) == 1, "u2 saw c1 once");
        Related[] r = s.related("c2", at(9, 0), at(11, 0));
        check(r.length == 1 && r[0].campaign.equals("c1") && r[0].sharedUsers == 1, "related(c2) = c1 x1");
        check(s.dropBefore(at(10, 0)) == 3 && s.stats().edges == 3, "dropBefore(10:00) removes 3, leaves 3");
    }

   
    static void randomVsBruteForce() {
        System.out.println("random events vs brute force");
        Random rnd = new Random(42);
        AdEventStore s = new AdEventStore();
        int campaigns = 100, users = 5_000;
        for (int i = 0; i < 100_000; i++) {
            int t = (int) ((long) i * 86_400 / 100_000) - (rnd.nextInt(10) == 0 ? rnd.nextInt(6) : 0);   
            t = Math.max(t, 0);
            int c = (int) (campaigns * Math.pow(rnd.nextDouble(), 2));                                 
            int u = rnd.nextInt(users);
            byte ty = (byte) (rnd.nextInt(100) < 90 ? 0 : rnd.nextInt(100) < 80 ? 1 : 2);
            if (s.addEvent(t, "c" + c, "u" + u, ty)) { T[n] = t; C[n] = c; U[n] = u; Y[n] = ty; n++; }
        }
        check(s.stats().edges == n, "edge count equals accepted events (" + n + ")");

        boolean win = true, hist = true, reach = true, rel = true, sorted = true, freq = true;
        for (int q = 0; q < 60; q++) {
            int c = rnd.nextInt(campaigns), a = rnd.nextInt(86_400 - 3600), b = a + 3600;
            Edge[] e = s.campaignEdges("c" + c, a, b);
            win &= e.length == bruteCount(c, -1, a, b);
            for (int i = 1; i < e.length; i++) sorted &= e[i - 1].time <= e[i].time;
            reach &= s.uniqueReach("c" + c, a, b) == bruteReach(c, a, b);
        }
        for (int q = 0; q < 30; q++) {
            int u = rnd.nextInt(users);
            hist &= s.userEdges("u" + u, 0, 86_400).length == bruteCount(-1, u, 0, 86_400);
            int c = rnd.nextInt(campaigns);
            freq &= s.frequency("c" + c, "u" + u, 0, 86_400) == bruteImpressions(c, u, 0, 86_400);
        }
        for (int q = 0; q < 5; q++) {
            int c = rnd.nextInt(10), a = rnd.nextInt(20) * 3600, b = a + 4 * 3600;
            Related[] r = s.related("c" + c, a, b);
            int[] expect = bruteRelated(c, a, b, campaigns);
            int total = 0;
            for (Related x : r) { rel &= expect[Integer.parseInt(x.campaign.substring(1))] == x.sharedUsers; total += x.sharedUsers; }
            int expTotal = 0;
            for (int x : expect) expTotal += x;
            rel &= total == expTotal;
        }
        check(win, "60 campaign windows match brute force");
        check(sorted, "campaign window results are in time order");
        check(hist, "30 user histories match brute force");
        check(reach, "unique reach matches brute force");
        check(freq, "frequency counts match brute force");
        check(rel, "related campaigns match brute force");

        int cut = 12 * 3600, remain = 0;
        for (int i = 0; i < n; i++) if ((T[i] / 3600 + 1) * 3600 > cut) remain++;
        s.dropBefore(cut);
        check(s.stats().edges == remain, "after dropBefore(12:00) edge count equals events in remaining segments");
    }

   
    static void lateEvents() {
        System.out.println("late events");
        AdEventStore s = new AdEventStore(null, 60);
        s.addEvent(3590, "c1", "u1", AdEventStore.IMP);
        s.addEvent(3605, "c1", "u2", AdEventStore.IMP);
        check(s.addEvent(3598, "c1", "u3", AdEventStore.IMP), "event 7 s late is accepted");
        Edge[] e = s.campaignEdges("c1", 0, 7200);
        check(e.length == 3 && e[0].time <= e[1].time && e[1].time <= e[2].time, "late event is inserted at its sorted place");
        s.addEvent(3700, "c1", "u4", AdEventStore.IMP);               
        check(!s.addEvent(3000, "c1", "u5", AdEventStore.IMP), "event for a sealed hour is rejected");
        check(s.stats().rejected == 1, "rejected counter is 1");
        check(!s.addEvent(-5, "c1", "u1", AdEventStore.IMP) && !s.addEvent(10, "a,b", "u1", AdEventStore.IMP), "invalid events are rejected");
    }

   
    static void recovery() throws Exception {
        System.out.println("crash recovery");
        File dir = Files.createTempDirectory("adstore-test").toFile();
        AdEventStore a = new AdEventStore(dir, 60);
        Random rnd = new Random(7);
        int total = 0;
        for (int i = 0; i < 5_000; i++)
            if (a.addEvent(i * 3, "c" + rnd.nextInt(20), "u" + rnd.nextInt(300), (byte) rnd.nextInt(3))) total++;
        int before = a.uniqueReach("c3", 0, 20_000);
        int segs = a.stats().segments;
       
        AdEventStore b = new AdEventStore(dir, 60);
        b.recover();
        check(b.stats().edges == total, "all " + total + " events are back after recovery");
        check(b.stats().segments == segs, "same number of segments after recovery");
        check(b.uniqueReach("c3", 0, 20_000) == before, "queries give the same answers after recovery");
        File[] files = dir.listFiles();
        boolean walOnlyForHot = true;
        for (File f : files) if (f.getName().startsWith("wal-") && new File(dir, "seg-" + f.getName().substring(4, f.getName().length() - 4) + ".edges").exists()) walOnlyForHot = false;
        check(walOnlyForHot, "sealed segments have no leftover write-ahead log");
        b.reset();
        check(dir.listFiles().length == 0, "reset removes the files");
        dir.delete();
    }

    
    static int at(int h, int m) { return h * 3600 + m * 60; }

    static int bruteCount(int c, int u, int a, int b) {
        int k = 0;
        for (int i = 0; i < n; i++) if (T[i] >= a && T[i] < b && (c < 0 || C[i] == c) && (u < 0 || U[i] == u)) k++;
        return k;
    }

    static int bruteReach(int c, int a, int b) {
        boolean[] seen = new boolean[5_000];
        int k = 0;
        for (int i = 0; i < n; i++) if (C[i] == c && T[i] >= a && T[i] < b && !seen[U[i]]) { seen[U[i]] = true; k++; }
        return k;
    }

    static int bruteImpressions(int c, int u, int a, int b) {
        int k = 0;
        for (int i = 0; i < n; i++) if (C[i] == c && U[i] == u && Y[i] == 0 && T[i] >= a && T[i] < b) k++;
        return k;
    }

    static int[] bruteRelated(int c, int a, int b, int campaigns) {
        boolean[] clicked = new boolean[5_000];
        for (int i = 0; i < n; i++) if (C[i] == c && Y[i] == 1 && T[i] >= a && T[i] < b) clicked[U[i]] = true;
        boolean[][] pair = new boolean[campaigns][5_000];
        for (int i = 0; i < n; i++) if (clicked[U[i]] && C[i] != c && T[i] >= a && T[i] < b) pair[C[i]][U[i]] = true;
        int[] out = new int[campaigns];
        for (int k = 0; k < campaigns; k++) for (int u = 0; u < 5_000; u++) if (pair[k][u]) out[k]++;
        return out;
    }
}
