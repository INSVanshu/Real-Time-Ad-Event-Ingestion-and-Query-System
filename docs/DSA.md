

All of it lives in one file: `backend/src/main/java/adstore/AdEventStore.java`.



Users and campaigns are the two vertex sets. Every ad event (impression, click, conversion) is one **edge** between a user and a campaign. The edge is stored twice, once in a campaign-side list and once in a user-side list, and all lists of one hour form a **segment**.

```
AdEventStore
 +-- IdMap campaigns, IdMap users        name -> small int id (own hash table, open addressing)
 +-- BipartiteGraph
 |     +-- TimeSegment[]                 one per hour, sorted by hour
 |           +-- AdjacencyList[] byCampaign    int[] time, int[] other, byte[] type
 |           +-- AdjacencyList[] byUser
 |           +-- WriteAheadLog                 one small file per hour (optional)
 +-- addEvent, campaignEdges, userEdges, uniqueReach, frequency, related, dropBefore, recover
```

Only primitive arrays are used inside the structure (no `java.util` collections). One edge side takes 4 (time) + 4 (endpoint id) + 1 (type) = 9 bytes here; the report's 13 bytes per side assume 8-byte endpoint ids. The dashboard's "26 B per event" figure uses the report's layout, it is not measured from the JVM.

## Operations and cost

`d` = edges at one vertex, `S` = segments overlapping the window, `k` = results.

| Operation | Method | Cost |
| --- | --- | --- |
| write an event | `addEvent` | O(1) amortised (log append + two list appends); late event: sorted insert near the tail |
| campaign window | `campaignEdges` | O(S log d + k) |
| user history | `userEdges` | O(S log d + k) |
| unique reach | `uniqueReach` | O(S log d + k) |
| frequency cap | `frequency` | O(S log d + k) |
| related campaigns | `related` | O(k + sum of the degrees of the k users) |
| expire | `dropBefore` | O(1) per segment, kept data is never rewritten |



* **Append keeps lists sorted.** Events arrive in time order, so appending is enough. A window is found with two binary searches (`lowerBound`).
* **Lateness window (60 s).** An hour stays open for 60 s after it ends so slightly late events still fit. After that the segment is sealed and later events for it are rejected.
* **Seal = one sequential write.** When a segment is sealed it is written to `seg-<hour>.edges` (through a temp file and a rename) and its `wal-<hour>.log` is deleted. Sealed segments never change.
* **Recovery.** `recover()` replays all `seg-*.edges` and the remaining `wal-*.log` files. A log whose segment file already exists is obsolete and removed.
* **Expiry.** `dropBefore(t)` removes whole segments from the front of the segment array.


`java backend/src/main/java/adstore/AdEventStore.java` prints the example of report section 5.1.3:
six events, two segments (09 sealed, 10 hot), reach of `c1` = 2, `related(c2)` = `c1 x1`, and `dropBefore(10:00)` removing 3 edges.

## Browser copy

`frontend/local-engine.js` is a small JavaScript port of the same structure so the dashboard also works on GitHub Pages without a server. It was checked against the Java server on identical random events.
