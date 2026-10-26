

Base URL: `http://localhost:8080` (or the port you start the server with).
Times are **seconds** since the start of the simulated day (`09:05` = `32700`). Windows are half-open: `[from, to)`.
Every response is JSON. Errors return HTTP 400 with `{"error": "..."}`. CORS is open, so the GitHub Pages dashboard can call a local server.


| Method | Endpoint | Parameters | Response |
| --- | --- | --- | --- |
| POST | `/api/event` | `t`, `c` (campaign), `u` (user), `type` = `imp` \| `click` \| `conv` | `{"accepted":true}` |
| POST | `/api/events` | body: one event per line `time,campaign,user,type` | `{"accepted":n,"rejected":m}` |
| POST | `/api/drop` | `before` (time) | `{"dropped":n}` edges removed by dropping whole segments |
| POST | `/api/reset` | - | `{"ok":true}` |

An event is rejected when its hour is already sealed (later than the 60 s lateness window), when the time is negative, the type is unknown, or a name contains a comma or line break.



| Method | Endpoint | Parameters | Response |
| --- | --- | --- | --- |
| GET | `/api/campaign` | `c`, `from`, `to`, optional `limit` (default 200) | `{"count":k,"edges":[{"t":32700,"c":"c1","u":"u1","type":"imp"}]}` |
| GET | `/api/user` | `u`, `from`, `to`, optional `limit` | same shape as above |
| GET | `/api/reach` | `c`, `from`, `to` | `{"reach":n}` distinct users |
| GET | `/api/frequency` | `c`, `u`, `from`, `to` | `{"impressions":n}` |
| GET | `/api/related` | `c`, `from`, `to` | `{"related":[{"campaign":"c1","sharedUsers":3}]}` |
| GET | `/api/sample` | `from`, `to`, optional `max` (default 120) | latest edges, fair share per campaign (used to draw the graph) |
| GET | `/api/stats` | - | counters plus `segmentList: [{hour, edges, sealed}]` |


```bash
curl -X POST "http://localhost:8080/api/event?t=32700&c=c1&u=u1&type=imp"
curl "http://localhost:8080/api/campaign?c=c1&from=32400&to=36000"
curl "http://localhost:8080/api/related?c=c2&from=32400&to=39600"
```



```
java -cp out adstore.Server [port] [frontendDir] [dataDir]
```

* `port` - default `$PORT` or `8080`
* `frontendDir` - also serves the dashboard at `/`
* `dataDir` - turns on the write-ahead log, sealed segment files and crash recovery
