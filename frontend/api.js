
const Api = (() => {
  const local = new LocalEngine();
  let base = '';
  let mode = 'local';                       

  const query = obj => Object.entries(obj).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
 
  async function http(method, path, params = {}, body) {
    const res = await fetch(`${base}${path}?${query(params)}`, { method, body });
    const json = await res.json();
    if (!res.ok) throw new Error(json.error || res.statusText);
    return json;
  }

  const server = () => mode === 'server';

  return {
    mode: () => mode,

   
    async connect(url = '') {
      base = url.trim().replace(/\/$/, '');
      try {
        const res = await fetch(`${base}/api/stats`, { signal: AbortSignal.timeout(1500) });
        if (res.ok) return (mode = 'server');
      } catch (e) {  }
      return (mode = 'local');
    },

   
    async addEvents(events) {
      if (server()) return http('POST', '/api/events', {}, events.map(e => `${e.t},${e.c},${e.u},${e.type}`).join('\n'));
      let accepted = 0, rejected = 0;
      for (const e of events) (local.addEvent(e.t, e.c, e.u, e.type) ? accepted++ : rejected++);
      return { accepted, rejected };
    },

    campaign: async (c, from, to) => server() ? http('GET', '/api/campaign', { c, from, to }) : local.campaignEdges(c, from, to),
    user: async (u, from, to) => server() ? http('GET', '/api/user', { u, from, to }) : local.userEdges(u, from, to),
    reach: async (c, from, to) => server() ? http('GET', '/api/reach', { c, from, to }) : local.uniqueReach(c, from, to),
    frequency: async (c, u, from, to) => server() ? http('GET', '/api/frequency', { c, u, from, to }) : local.frequency(c, u, from, to),
    related: async (c, from, to) => server() ? http('GET', '/api/related', { c, from, to }) : local.related(c, from, to),
    sample: async (from, to, max) => server() ? http('GET', '/api/sample', { from, to, max }) : local.sample(from, to, max),
    stats: async () => server() ? http('GET', '/api/stats') : local.stats(),
    drop: async before => server() ? http('POST', '/api/drop', { before }) : local.dropBefore(before),
    reset: async () => { if (server()) await http('POST', '/api/reset'); else local.reset(); },
  };
})();
