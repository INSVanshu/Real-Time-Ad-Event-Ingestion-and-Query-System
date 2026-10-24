# Contributing

The project is split into four parts, one owner each. Everyone works on their **own branch**, opens a **pull request** into `main`, and another member reviews it. `.github/CODEOWNERS` asks the owner of a file to review changes to it.

| Part | Owner | Branch | Files |
| --- | --- | --- | --- |
| 1 - DSA core | member 1 | `feature/dsa-core` | `AdEventStore.java`, `local-engine.js`, `docs/DSA.md` |
| 2 - Backend API | member 2 | `feature/api-server` | `Server.java`, `api.js`, `docs/API.md` |
| 3 - Frontend simulation | member 3 | `feature/dashboard` | `index.html`, `style.css`, `app.js` |
| 4 - QA, CI/CD, docs | member 4 | `feature/qa-devops` | `src/test/`, `.github/`, `Dockerfile`, `README.md`, `.gitignore` |

## Rules

* Never push straight to `main`. Open a pull request; CI must be green before merging.
* Use your own GitHub account and your own name/e-mail for your commits (`git config user.name`, `git config user.email`).
* Keep commits small and name them with a prefix: `feat:`, `fix:`, `test:`, `docs:`, `ci:`, `refactor:`.
* One pull request = one part of the work. Do not edit another member's files; ask them or open an issue.

## Suggested order of work

Merge in this order, because each part builds on the one before:

### 1. `feature/dsa-core` (member 1)

1. `feat: IdMap, a small open-addressing hash map for campaign and user ids`
2. `feat: AdjacencyList with binary search and sorted insert for late events`
3. `feat: TimeSegment and BipartiteGraph (segments sorted by hour)`
4. `feat: addEvent, campaignEdges, userEdges`
5. `feat: uniqueReach, frequency and related (two-hop query)`
6. `feat: write-ahead log, seal, dropBefore and recover`
7. `feat: JavaScript port of the structure for the browser demo`
8. `docs: explain the structure and its costs`

### 2. `feature/api-server` (member 2)

1. `feat: HTTP server skeleton and static file serving`
2. `feat: write endpoints /api/event and /api/events`
3. `feat: read endpoints (campaign, user, reach, frequency, related, sample, stats)`
4. `feat: drop and reset endpoints, CORS headers`
5. `feat: api.js client with fallback to the in-browser engine`
6. `docs: document every endpoint`

### 3. `feature/dashboard` (member 3)

1. `feat: page layout and light/dark theme`
2. `feat: event simulator with skewed campaign popularity and late events`
3. `feat: bipartite graph drawing on canvas`
4. `feat: hourly segment view (hot / sealed)`
5. `feat: query panel with timing`
6. `feat: expire, burst and reset buttons`

### 4. `feature/qa-devops` (member 4)

1. `test: worked example from the report`
2. `test: random events compared with brute force`
3. `test: late events and crash recovery`
4. `test: benchmark against a plain log scan`
5. `ci: compile and test on every push and pull request`
6. `ci: deploy the dashboard to GitHub Pages`
7. `build: Dockerfile and .gitignore`
8. `docs: README with quick start and deployment`

## Commands

```bash
git clone <repo-url> && cd ad-event-graph-store
git checkout -b feature/<your-branch>
# ... work, then:
git add <your files>
git commit -m "feat: ..."
git push -u origin feature/<your-branch>
# open a pull request on GitHub, ask a teammate to review
```

## Before you open a pull request

```bash
javac -d out backend/src/main/java/adstore/*.java backend/src/test/java/adstore/*.java
java -cp out adstore.AdEventStoreTest
```

## Repository settings (once, by the owner of the repo)

* **Settings -> Branches -> Add rule** for `main`: require a pull request and the `CI` check.
* **Settings -> Pages -> Source: GitHub Actions.**
* **Settings -> Collaborators:** add the other three members.
