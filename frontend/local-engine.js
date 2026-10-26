
class LocalEngine {
  static SEG = 3600;      
  static LATE = 60;       
  static TYPES = ['imp', 'click', 'conv'];

  constructor() { this.reset(); }

  reset() {
    this.segs = new Map();            
    this.maxTime = -1;
    this.accepted = 0;
    this.rejected = 0;
    this.campaigns = new Set();
    this.users = new Set();
  }

  
  addEvent(t, c, u, type) {
    const hour = Math.floor(t / LocalEngine.SEG);
    const bad = !(t >= 0) || !c || !u || !LocalEngine.TYPES.includes(type);
    if (bad || (hour + 1) * LocalEngine.SEG + LocalEngine.LATE <= this.maxTime) { this.rejected++; return false; }

    let seg = this.segs.get(hour);
    if (!seg) { seg = { hour, sealed: false, edges: 0, byC: new Map(), byU: new Map() }; this.segs.set(hour, seg); }
    LocalEngine.#insert(seg.byC, c, { t, o: u, y: type });   
    LocalEngine.#insert(seg.byU, u, { t, o: c, y: type });   
    seg.edges++;
    this.accepted++;
    this.campaigns.add(c);
    this.users.add(u);

    this.maxTime = Math.max(this.maxTime, t);
    for (const s of this.segs.values())                      
      if (!s.sealed && (s.hour + 1) * LocalEngine.SEG + LocalEngine.LATE <= this.maxTime) s.sealed = true;
    return true;
  }

  static #insert(map, key, edge) {
    if (!map.has(key)) map.set(key, []);
    const list = map.get(key);
    let i = list.length;
    while (i > 0 && list[i - 1].t > edge.t) i--;             
    list.splice(i, 0, edge);                                  
  }

  
  static #lower(list, t) {                                    
    let lo = 0, hi = list.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (list[mid].t < t) lo = mid + 1; else hi = mid; }
    return lo;
  }

  #segments(from, to) {                                       
    return [...this.segs.values()]
      .filter(s => s.hour * LocalEngine.SEG < to && (s.hour + 1) * LocalEngine.SEG > from)
      .sort((a, b) => a.hour - b.hour);
  }

  #window(side, key, from, to) {                             
    const out = [];
    for (const seg of this.#segments(from, to)) {
      const list = seg[side].get(key);
      if (!list) continue;
      const hi = LocalEngine.#lower(list, to);
      for (let i = LocalEngine.#lower(list, from); i < hi; i++) out.push(list[i]);
    }
    return out;
  }

 
  campaignEdges(c, from, to) {
    const e = this.#window('byC', c, from, to).map(x => ({ t: x.t, c, u: x.o, type: x.y }));
    return { count: e.length, edges: e };
  }

  userEdges(u, from, to) {
    const e = this.#window('byU', u, from, to).map(x => ({ t: x.t, c: x.o, u, type: x.y }));
    return { count: e.length, edges: e };
  }

  
  uniqueReach(c, from, to) {
    return { reach: new Set(this.#window('byC', c, from, to).map(x => x.o)).size };
  }

  frequency(c, u, from, to) {
    return { impressions: this.#window('byU', u, from, to).filter(x => x.o === c && x.y === 'imp').length };
  }

  related(c, from, to) {
    const clickers = new Set(this.#window('byC', c, from, to).filter(x => x.y === 'click').map(x => x.o));
    const shared = new Map();                                
    for (const u of clickers)
      for (const x of this.#window('byU', u, from, to)) {
        if (x.o === c) continue;
        if (!shared.has(x.o)) shared.set(x.o, new Set());
        shared.get(x.o).add(u);
      }
    const related = [...shared].map(([campaign, set]) => ({ campaign, sharedUsers: set.size }))
      .sort((a, b) => b.sharedUsers - a.sharedUsers);
    return { related };
  }

  sample(from, to, max) {
    const edges = [];
    const perCampaign = Math.max(1, Math.floor(max / Math.max(1, this.campaigns.size)));   
    for (const seg of this.#segments(from, to).reverse())
      for (const [c, list] of seg.byC) {
        const lo = LocalEngine.#lower(list, from);
        let taken = 0;
        for (let i = LocalEngine.#lower(list, to) - 1; i >= lo && edges.length < max && taken < perCampaign; i--, taken++)
          edges.push({ t: list[i].t, c, u: list[i].o, type: list[i].y });
      }
    return { count: edges.length, edges };
  }

  
  dropBefore(time) {
    let dropped = 0;
    for (const [hour, seg] of [...this.segs])
      if ((hour + 1) * LocalEngine.SEG <= time) { dropped += seg.edges; this.segs.delete(hour); }
    return { dropped };
  }

  stats() {
    const list = [...this.segs.values()].sort((a, b) => a.hour - b.hour);
    const edges = list.reduce((n, s) => n + s.edges, 0);
    return {
      accepted: this.accepted, rejected: this.rejected, edges,
      segments: list.length, sealed: list.filter(s => s.sealed).length,
      campaigns: this.campaigns.size, users: this.users.size, edgeBytes: edges * 26,
      segmentList: list.map(s => ({ hour: s.hour, edges: s.edges, sealed: s.sealed })),
    };
  }
}
