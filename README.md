# Ad Event Graph Store

**Real-Time Ad Event Ingestion and Query System** built on one data structure: a **time-segmented bipartite graph**.

Every ad event (impression, click, conversion) is an edge between a **user** and a **campaign**. Edges are appended to two sorted adjacency lists (one per side) inside the segment of their hour. Finished hours are sealed and never change, so writes are sequential, time-window queries are a binary search, and relationship queries (unique reach, frequency cap, related campaigns) just follow edges.

This repository is the working project for the case study *"Real-Time Ad Event Ingestion and Query System"*.

```
 ad event ──► write-ahead log ──► edge in campaign list + user list ──► seal hour (immutable file)
                                              │
 dashboard ◄── Java HTTP API ◄── queries: window · history · reach · frequency · related · expire
 (browser)     (Server.java)               (AdEventStore.java, the DSA)
```

## Repository layout

| Path | What it is | Part |
| --- | --- | --- |
| `backend/src/main/java/adstore/AdEventStore.java` | **The whole DSA in one file**: bipartite graph, segments, adjacency lists, own hash map, write-ahead log, recovery | 1 - DSA core |
| `frontend/local-engine.js` | Browser port of the same structure (powers the GitHub Pages demo) | 1 - DSA core |
| `backend/src/main/java/adstore/Server.java` | HTTP API (JDK only, no libraries) | 2 - Backend API |
| `frontend/api.js` | Client that talks to the server, or falls back to the browser engine | 2 - Backend API |
| `frontend/index.html`, `style.css`, `app.js` | Dashboard: event simulator, live graph drawing, segments view, query panel | 3 - Frontend |
| `backend/src/test/java/adstore/` | Tests (graph vs brute force) and a small benchmark | 4 - QA / DevOps |
| `.github/workflows/`, `Dockerfile` | CI, GitHub Pages deploy, container | 4 - QA / DevOps |
| `docs/` | `DSA.md` (structure and costs), `API.md` (endpoints) | 1, 2 |

## Team

| Part | Member | GitHub |
| --- | --- | --- |
| 1 - DSA core | _name_ | @member1 |
| 2 - Backend API | _name_ | @member2 |
| 3 - Frontend simulation | _name_ | @member3 |
| 4 - QA, CI/CD, docs | _name_ | @member4 |

See [CONTRIBUTING.md](CONTRIBUTING.md) for the branch and pull request workflow.

## Quick start

You need a **JDK 17 or newer** (`java -version` and `javac -version`).

### Option A - full stack (Java backend + dashboard)

```bash
# compile
mkdir out
javac -d out backend/src/main/java/adstore/*.java

# run: port, folder with the dashboard, folder for logs and sealed segments
java -cp out adstore.Server 8080 frontend data
```

Open <http://localhost:8080>, press **Start live stream**. The badge shows *Backend: Java server*.

Windows (cmd): `javac -d out backend\src\main\java\adstore\*.java` and `java -cp out adstore.Server 8080 frontend data`.

### Option B - dashboard only (no Java)

Open `frontend/index.html` in a browser, or run `python3 -m http.server -d frontend 8000`.
The badge shows *Backend: in-browser engine*: the same data structure running in JavaScript.

### Option C - Docker

```bash
docker build -t ad-event-graph-store .
docker run -p 8080:8080 -v adstore-data:/app/data ad-event-graph-store
```

## Try the DSA alone

```bash
java backend/src/main/java/adstore/AdEventStore.java      # prints the worked example of the report
```

## Tests and benchmark

```bash
javac -d out backend/src/main/java/adstore/*.java backend/src/test/java/adstore/*.java
java -cp out adstore.AdEventStoreTest      # 25 checks, every query compared with brute force
java -cp out adstore.Benchmark             # graph vs scanning a plain log
```

## Deploy on GitHub

1. Create an empty repository and push this project to `main`.
2. **Settings -> Pages -> Build and deployment -> Source: GitHub Actions.**
3. The `Deploy dashboard to GitHub Pages` workflow publishes `frontend/` at `https://<user>.github.io/<repo>/`.
   On Pages there is no Java server, so the dashboard runs on the in-browser engine.
4. The `CI` workflow compiles the Java code and runs the tests on every push and pull request.
5. To use the Java backend with the hosted dashboard: run the server locally, open the Pages site and type `http://localhost:8080` into *Java backend URL* (the server allows cross-origin calls). To host the backend itself, deploy the `Dockerfile` on any container host (Render, Railway, Fly.io); it serves both API and dashboard.

## API

See [docs/API.md](docs/API.md). Example:

```bash
curl -X POST "http://localhost:8080/api/event?t=32700&c=c1&u=u1&type=imp"
curl "http://localhost:8080/api/campaign?c=c1&from=32400&to=36000"
```

## Limitations

* This is a teaching prototype: one process, one machine, segments are not merged or rolled up into days.
* Edges are immutable and stored twice (campaign side and user side); an event for an already sealed hour is rejected.
* The numbers in the dashboard come from simulated random traffic. The benchmark compares the two structures on your machine and is not a production measurement.
