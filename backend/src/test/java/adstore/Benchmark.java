package adstore;

import java.util.Random;

/**
 * Mini version of the measurement in section 2.8 of the case study:
 * bipartite graph vs a plain append-only log that must be scanned for every query.
 *
 * Run:  java -cp out adstore.Benchmark [events]      (default 200000)
 * Numbers depend on your machine and are only meant to compare the two structures.
 */
public class Benchmark {

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        int campaigns = 500, users = 50_000;
        Random rnd = new Random(1);

        int[] t = new int[n], c = new int[n], u = new int[n];       // the "append-only log"
        byte[] y = new byte[n];
        AdEventStore graph = new AdEventStore();

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            t[i] = Math.max(0, (int) ((long) i * 86_400 / n) - (rnd.nextInt(10) == 0 ? rnd.nextInt(6) : 0));
            c[i] = (int) (campaigns * Math.pow(rnd.nextDouble(), 2));
            u[i] = rnd.nextInt(users);
            int r = rnd.nextInt(100);
            y[i] = (byte) (r < 90 ? 0 : r < 98 ? 1 : 2);
            graph.addEvent(t[i], "c" + c[i], "u" + u[i], y[i]);
        }
        double ingestUs = (System.nanoTime() - t0) / 1e3 / n;

        int q = 300;
        int[] qc = new int[q], qa = new int[q];
        for (int i = 0; i < q; i++) { qc[i] = rnd.nextInt(campaigns); qa[i] = rnd.nextInt(86_400 - 3600); }

        long sink = 0;
        for (int i = 0; i < 50; i++) sink += graph.campaignEdges("c" + qc[i], qa[i], qa[i] + 3600).length;   // warm-up

        t0 = System.nanoTime();
        for (int i = 0; i < q; i++) sink += graph.campaignEdges("c" + qc[i], qa[i], qa[i] + 3600).length;
        double graphMs = (System.nanoTime() - t0) / 1e6 / q;

        t0 = System.nanoTime();
        for (int i = 0; i < q; i++) {
            int a = qa[i], b = a + 3600, k = 0;
            for (int j = 0; j < n; j++) if (c[j] == qc[i] && t[j] >= a && t[j] < b) k++;      // full scan of the log
            sink += k;
        }
        double logMs = (System.nanoTime() - t0) / 1e6 / q;

        System.out.printf("events: %,d   segments: %d   ingest: %.2f us/event%n", n, graph.stats().segments, ingestUs);
        System.out.printf("campaign 1-hour window  graph: %.4f ms   log scan: %.4f ms   speed-up: %.0fx%n", graphMs, logMs, logMs / graphMs);
        System.out.println("(checksum " + sink + ")");
    }
}
