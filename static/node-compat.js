/*
 * node-compat.js —— QuickJS 下的 Node 常用模块兼容层（在 POLYFILL 之后求值）。
 * 网络统一经 Rust 原语 __request；定时器经 __sleep。
 * 覆盖：timers / fetch / TextEncoder / atob / events / axios / http / https / url / querystring / util / vm / child_process(明确报错)
 */
(function () {
  var G = globalThis;
  var isBuf = function (x) { return !!x && typeof x === 'object' && !!x._bytes; };
  var toBytes = function (x) {
    if (isBuf(x)) return x._bytes;
    if (x instanceof Uint8Array) return Array.prototype.slice.call(x);
    if (x instanceof ArrayBuffer) return Array.prototype.slice.call(new Uint8Array(x));
    return __utf8ToBytes(String(x));
  };

  // ---------------- timers ----------------
  var seq = 0, alive = {};
  function timerObj(id) {
    return { _id: id, ref: function () { return this; }, unref: function () { return this; }, hasRef: function () { return true; },
      refresh: function () { return this; }, [Symbol.toPrimitive]: function () { return id; } };
  }
  G.setTimeout = function (fn, ms) {
    var id = ++seq, args = Array.prototype.slice.call(arguments, 2);
    alive[id] = true;
    __sleep(Number(ms) || 0).then(function () {
      if (alive[id]) { delete alive[id]; if (typeof fn === 'function') fn.apply(null, args); }
    });
    return timerObj(id);
  };
  G.setInterval = function (fn, ms) {
    var id = ++seq, args = Array.prototype.slice.call(arguments, 2);
    alive[id] = true;
    (function tick() {
      __sleep(Math.max(Number(ms) || 0, 1)).then(function () {
        if (!alive[id]) return;
        if (typeof fn === 'function') fn.apply(null, args);
        tick();
      });
    })();
    return timerObj(id);
  };
  G.setImmediate = function (fn) { var a = Array.prototype.slice.call(arguments, 1); var id = ++seq; alive[id] = true; Promise.resolve().then(function () { if (alive[id]) { delete alive[id]; fn.apply(null, a); } }); return timerObj(id); };
  G.clearTimeout = G.clearInterval = G.clearImmediate = function (t) { if (t != null) delete alive[typeof t === 'object' ? t._id : t]; };
  if (typeof G.queueMicrotask === 'undefined') G.queueMicrotask = function (f) { Promise.resolve().then(f); };

  // ---------------- 全局：TextEncoder / atob / __dirname ----------------
  if (typeof G.TextEncoder === 'undefined') {
    G.TextEncoder = function () { this.encoding = 'utf-8'; };
    G.TextEncoder.prototype.encode = function (s) { return Uint8Array.from(__utf8ToBytes(s == null ? '' : String(s))); };
  }
  if (typeof G.TextDecoder === 'undefined') {
    G.TextDecoder = function (label) { this.encoding = String(label || 'utf-8').toLowerCase(); };
    G.TextDecoder.prototype.decode = function (b) { return b == null ? '' : Buffer.from(toBytes(b)).toString('utf8'); };
  }
  if (typeof G.btoa === 'undefined') G.btoa = function (s) { s = String(s); var o = []; for (var i = 0; i < s.length; i++) o.push(s.charCodeAt(i) & 255); return Buffer.from(o).toString('base64'); };
  if (typeof G.atob === 'undefined') G.atob = function (s) { return Buffer.from(String(s), 'base64').toString('latin1'); };
  if (typeof G.__dirname === 'undefined') { G.__dirname = '/'; G.__filename = '/script.js'; }

  // ---------------- Buffer 补充 ----------------
  if (G.Buffer) {
    G.Buffer.alloc = function (n, fill, enc) {
      n = Math.max(0, n | 0);
      var out = new Array(n).fill(0);
      if (fill !== undefined && fill !== 0) {
        var f = typeof fill === 'number' ? [fill & 255] : toBytes(typeof fill === 'string' ? Buffer.from(fill, enc || 'utf8') : fill);
        if (f.length) for (var i = 0; i < n; i++) out[i] = f[i % f.length];
      }
      return __mkbuf(out);
    };
    G.Buffer.allocUnsafe = G.Buffer.alloc;
    G.Buffer.concat = function (list, total) {
      var out = [];
      for (var i = 0; i < list.length; i++) { var b = toBytes(list[i]); for (var j = 0; j < b.length; j++) out.push(b[j] & 255); }
      return __mkbuf(total != null ? out.slice(0, total) : out);
    };
    G.Buffer.compare = function (a, b) { var x = toBytes(a), y = toBytes(b); for (var i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1; return x.length === y.length ? 0 : (x.length < y.length ? -1 : 1); };
  }

  // ---------------- URLSearchParams / URL（WHATWG 近似实现）----------------
  function formEncode(s) {
    var b = __utf8ToBytes(String(s)), o = '';
    for (var i = 0; i < b.length; i++) {
      var c = b[i];
      if ((c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c === 42 || c === 45 || c === 46 || c === 95) o += String.fromCharCode(c);
      else if (c === 32) o += '+';
      else o += '%' + (c < 16 ? '0' : '') + c.toString(16).toUpperCase();
    }
    return o;
  }
  function formDecode(s) { s = String(s).replace(/\+/g, ' '); try { return decodeURIComponent(s); } catch (e) { return s.replace(/%([0-9a-fA-F]{2})/g, function (m, h) { return String.fromCharCode(parseInt(h, 16)); }); } }
  class URLSearchParams {
    constructor(init) {
      this._m = [];
      if (init instanceof URLSearchParams) this._m = init._m.map(function (p) { return [p[0], p[1]]; });
      else if (typeof init === 'string') this._parse(init);
      else if (init && typeof init[Symbol.iterator] === 'function') { for (var p of init) this._m.push([String(p[0]), String(p[1])]); }
      else if (init && typeof init === 'object') Object.keys(init).forEach((k) => { this._m.push([k, String(init[k])]); });
    }
    _parse(str) {
      this._m = [];
      String(str).replace(/^\?/, '').split('&').forEach((pair) => {
        if (!pair) return;
        var i = pair.indexOf('=');
        this._m.push([formDecode(i < 0 ? pair : pair.slice(0, i)), formDecode(i < 0 ? '' : pair.slice(i + 1))]);
      });
    }
    get size() { return this._m.length; }
    append(k, v) { this._m.push([String(k), String(v)]); }
    delete(k, v) { k = String(k); this._m = this._m.filter(function (p) { return !(p[0] === k && (v === undefined || p[1] === String(v))); }); }
    get(k) { k = String(k); for (var i = 0; i < this._m.length; i++) if (this._m[i][0] === k) return this._m[i][1]; return null; }
    getAll(k) { k = String(k); return this._m.filter(function (p) { return p[0] === k; }).map(function (p) { return p[1]; }); }
    has(k, v) { k = String(k); return this._m.some(function (p) { return p[0] === k && (v === undefined || p[1] === String(v)); }); }
    set(k, v) {
      k = String(k); v = String(v);
      var found = false, out = [];
      this._m.forEach(function (p) { if (p[0] === k) { if (!found) { found = true; out.push([k, v]); } } else out.push(p); });
      if (!found) out.push([k, v]);
      this._m = out;
    }
    sort() { this._m = this._m.map(function (p, i) { return [p, i]; }).sort(function (a, b) { return a[0][0] < b[0][0] ? -1 : a[0][0] > b[0][0] ? 1 : a[1] - b[1]; }).map(function (x) { return x[0]; }); }
    toString() { return this._m.map(function (p) { return formEncode(p[0]) + '=' + formEncode(p[1]); }).join('&'); }
    forEach(f, t) { this._m.slice().forEach(function (p) { f.call(t, p[1], p[0], this); }, this); }
    keys() { return this._m.map(function (p) { return p[0]; })[Symbol.iterator](); }
    values() { return this._m.map(function (p) { return p[1]; })[Symbol.iterator](); }
    entries() { return this._m.map(function (p) { return [p[0], p[1]]; })[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
  }
  G.URLSearchParams = URLSearchParams;

  var SPECIAL = { 'http:': '80', 'https:': '443', 'ftp:': '21', 'ws:': '80', 'wss:': '443', 'file:': '' };
  function pctEncode(str, keep) {
    var b = __utf8ToBytes(String(str)), o = '';
    for (var i = 0; i < b.length; i++) {
      var c = b[i], ch = String.fromCharCode(c);
      if (c > 32 && c < 127 && keep.indexOf(ch) < 0 || (c > 32 && c < 127 && /[A-Za-z0-9\-._~!$&'()*+,;=:@\/?%\[\]|^]/.test(ch) && keep.indexOf(ch) < 0)) o += ch;
      else if (c > 32 && c < 127 && keep.indexOf(ch) >= 0) o += '%' + c.toString(16).toUpperCase();
      else o += '%' + (c < 16 ? '0' : '') + c.toString(16).toUpperCase();
    }
    return o;
  }
  var PATH_ESC = ' "#<>?`{}', QUERY_ESC = ' "#<>\'', FRAG_ESC = ' "<>`';
  function normPath(path) {
    var segs = path.split('/'), out = [];
    for (var i = 0; i < segs.length; i++) {
      var sg = segs[i], last = i === segs.length - 1;
      var low = sg.toLowerCase();
      if (sg === '.' || low === '%2e') { if (last) out.push(''); continue; }
      if (sg === '..' || low === '.%2e' || low === '%2e.' || low === '%2e%2e') { if (out.length > 1) out.pop(); if (last) out.push(''); continue; }
      out.push(sg);
    }
    var r = out.join('/');
    return r.charAt(0) === '/' ? r : '/' + r;
  }
  function invalid(input) { var e = new TypeError('Invalid URL'); e.code = 'ERR_INVALID_URL'; e.input = input; return e; }
  function parseUrl(input, base) {
    var str = String(input).replace(/^[\u0000- ]+|[\u0000- ]+$/g, '').replace(/[\t\n\r]/g, '');
    var m = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(str);
    var rec = { protocol: '', username: '', password: '', hostname: '', port: '', pathname: '', search: '', hash: '', opaque: false };
    function setAuthority(rest, protocol) {
      var end = rest.search(/[\/?#\\]/); if (end < 0) end = rest.length;
      var auth = rest.slice(0, end), tail = rest.slice(end).replace(/\\/g, protocol in SPECIAL ? '/' : '\\');
      var at = auth.lastIndexOf('@');
      if (at >= 0) {
        var ui = auth.slice(0, at); auth = auth.slice(at + 1);
        var ci = ui.indexOf(':');
        rec.username = pctEncode(ci < 0 ? ui : ui.slice(0, ci), ''); rec.password = ci < 0 ? '' : pctEncode(ui.slice(ci + 1), '');
      }
      var pm = /^(\[[^\]]*\]|[^:]*)(?::(\d*))?$/.exec(auth);
      if (!pm) throw invalid(input);
      var host = pm[1];
      if (protocol in SPECIAL && protocol !== 'file:' && host === '') throw invalid(input);
      if (/[\s<>^|%]/.test(host.replace(/%[0-9a-fA-F]{2}/g, ''))) { if (protocol in SPECIAL) throw invalid(input); }
      try { host = protocol in SPECIAL ? decodeURIComponent(host).toLowerCase() : host; } catch (e) { throw invalid(input); }
      rec.hostname = host;
      var port = pm[2] || '';
      if (port !== '') { var pn = parseInt(port, 10); if (pn > 65535) throw invalid(input); port = String(pn); }
      rec.port = port === SPECIAL[protocol] ? '' : port;
      return tail;
    }
    function setTail(tail, special) {
      var h = tail.indexOf('#'); if (h >= 0) { rec.hash = tail.length > h + 1 ? '#' + pctEncode(tail.slice(h + 1), FRAG_ESC) : ''; tail = tail.slice(0, h); }
      var q = tail.indexOf('?'); if (q >= 0) { rec.search = tail.length > q + 1 ? '?' + pctEncode(tail.slice(q + 1), special ? QUERY_ESC + '\'' : QUERY_ESC) : ''; tail = tail.slice(0, q); }
      rec.pathname = special ? normPath(pctEncode(tail === '' ? '/' : tail, PATH_ESC)) : pctEncode(tail, PATH_ESC);
    }
    if (m) {
      var protocol = m[1].toLowerCase() + ':', rest = str.slice(m[0].length);
      rec.protocol = protocol;
      if (protocol in SPECIAL) {
        if (/^[\/\\]{2}/.test(rest) || !base || (typeof base === 'object' ? base.protocol : parseUrl(base).protocol) !== protocol) {
          rest = protocol === 'file:' ? (/^[\/\\]{2}/.test(rest) ? rest.slice(2) : rest) : rest.replace(/^[\/\\]*/, '');
          setTail(setAuthority(rest, protocol), true);
        } else {
          return resolveRel(rest, typeof base === 'object' ? base : parseUrl(base), input);
        }
      } else if (/^\/\//.test(rest)) {
        setTail(setAuthority(rest.slice(2), protocol), false);
      } else {
        rec.opaque = true;
        var hi = rest.indexOf('#'); if (hi >= 0) { rec.hash = rest.length > hi + 1 ? '#' + pctEncode(rest.slice(hi + 1), FRAG_ESC) : ''; rest = rest.slice(0, hi); }
        var qi = rest.indexOf('?'); if (qi >= 0) { rec.search = rest.length > qi + 1 ? rest.slice(qi) : ''; rest = rest.slice(0, qi); }
        rec.pathname = rest;
      }
      return rec;
    }
    if (base === undefined || base === null) throw invalid(input);
    var b = typeof base === 'object' && base.protocol !== undefined && base.pathname !== undefined && base.hostname !== undefined && !(base instanceof G.URL) ? base : null;
    if (!b) { try { b = base instanceof G.URL ? base._rec : parseUrl(String(base)); } catch (e) { throw invalid(input); } }
    return resolveRel(str, b, input);
  }
  function resolveRel(str, b, input) {
    var rec = { protocol: b.protocol, username: b.username, password: b.password, hostname: b.hostname, port: b.port, pathname: b.pathname, search: b.search, hash: '', opaque: b.opaque };
    if (b.opaque && str.charAt(0) !== '#') throw invalid(input);
    var special = b.protocol in SPECIAL;
    str = special ? str.replace(/\\/g, '/') : str;
    if (str.slice(0, 2) === '//') { return parseUrl(b.protocol + str); }
    var h = str.indexOf('#'), frag = '';
    if (h >= 0) { frag = str.length > h + 1 ? '#' + pctEncode(str.slice(h + 1), FRAG_ESC) : ''; str = str.slice(0, h); }
    rec.hash = frag;
    if (str === '') return rec;
    var q = str.indexOf('?'), query = null;
    if (q >= 0) { query = str.length > q + 1 ? '?' + pctEncode(str.slice(q + 1), special ? QUERY_ESC + '\'' : QUERY_ESC) : ''; str = str.slice(0, q); }
    if (str === '') { rec.search = query === null ? b.search : query; return rec; }
    rec.search = query === null ? '' : query;
    var path = pctEncode(str, PATH_ESC);
    if (path.charAt(0) === '/') rec.pathname = normPath(path);
    else rec.pathname = normPath(b.pathname.replace(/[^\/]*$/, '') + path);
    return rec;
  }
  class URL {
    constructor(input, base) {
      this._rec = parseUrl(input, base);
      this._setSp();
    }
    _setSp() {
      var self = this, sp = new URLSearchParams(this._rec.search);
      ['append', 'set', 'delete', 'sort'].forEach(function (mth) {
        var orig = sp[mth];
        sp[mth] = function () { var r = orig.apply(sp, arguments); var s = sp.toString(); self._rec.search = s ? '?' + s : ''; return r; };
      });
      this._sp = sp;
    }
    get protocol() { return this._rec.protocol; }
    set protocol(v) { v = String(v).replace(/:.*$/, '') + ':'; this._rec.protocol = v; if (this._rec.port === SPECIAL[v]) this._rec.port = ''; }
    get username() { return this._rec.username; } set username(v) { this._rec.username = String(v); }
    get password() { return this._rec.password; } set password(v) { this._rec.password = String(v); }
    get hostname() { return this._rec.hostname; } set hostname(v) { this._rec.hostname = String(v).toLowerCase(); }
    get port() { return this._rec.port; } set port(v) { v = String(v); this._rec.port = v === SPECIAL[this._rec.protocol] ? '' : v.replace(/\D.*$/, ''); }
    get host() { return this._rec.hostname + (this._rec.port ? ':' + this._rec.port : ''); }
    set host(v) { var m = /^([^:]*)(?::(\d+))?$/.exec(String(v)); if (m) { this._rec.hostname = m[1].toLowerCase(); this.port = m[2] || ''; } }
    get origin() { var r = this._rec; return (r.protocol in SPECIAL && r.protocol !== 'file:') ? r.protocol + '//' + this.host : 'null'; }
    get pathname() { return this._rec.pathname; } set pathname(v) { v = String(v); this._rec.pathname = normPath(pctEncode(v.charAt(0) === '/' ? v : '/' + v, PATH_ESC)); }
    get search() { return this._rec.search; }
    set search(v) { v = String(v); v = v === '' || v === '?' ? '' : '?' + pctEncode(v.replace(/^\?/, ''), QUERY_ESC + '\''); this._rec.search = v; this._setSp(); }
    get hash() { return this._rec.hash; } set hash(v) { v = String(v); this._rec.hash = v === '' || v === '#' ? '' : '#' + pctEncode(v.replace(/^#/, ''), FRAG_ESC); }
    get searchParams() { return this._sp; }
    get href() {
      var r = this._rec;
      if (r.opaque) return r.protocol + r.pathname + r.search + r.hash;
      var auth = r.username ? r.username + (r.password ? ':' + r.password : '') + '@' : '';
      var hasAuthority = r.hostname !== '' || r.protocol in SPECIAL || r.protocol === 'file:';
      return r.protocol + (hasAuthority ? '//' + auth + this.host : '') + r.pathname + r.search + r.hash;
    }
    set href(v) { this._rec = parseUrl(v); this._setSp(); }
    toString() { return this.href; }
    toJSON() { return this.href; }
    static canParse(u, b) { try { new URL(u, b); return true; } catch (e) { return false; } }
  }
  G.URL = URL;

  // ---------------- events ----------------
  class EventEmitter {
    constructor() { this._ev = {}; }
    on(n, f) { (this._ev[n] = this._ev[n] || []).push(f); return this; }
    addListener(n, f) { return this.on(n, f); }
    prependListener(n, f) { (this._ev[n] = this._ev[n] || []).unshift(f); return this; }
    once(n, f) { var self = this; var w = function () { self.off(n, w); return f.apply(self, arguments); }; w._orig = f; return this.on(n, w); }
    off(n, f) { var l = this._ev[n]; if (l) this._ev[n] = l.filter(function (x) { return x !== f && x._orig !== f; }); return this; }
    removeListener(n, f) { return this.off(n, f); }
    removeAllListeners(n) { if (n === undefined) this._ev = {}; else delete this._ev[n]; return this; }
    emit(n) {
      var l = (this._ev[n] || []).slice();
      var args = Array.prototype.slice.call(arguments, 1);
      for (var i = 0; i < l.length; i++) l[i].apply(this, args);
      return l.length > 0;
    }
    listenerCount(n) { return (this._ev[n] || []).length; }
    listeners(n) { return (this._ev[n] || []).slice(); }
    setMaxListeners() { return this; }
  }
  EventEmitter.EventEmitter = EventEmitter;
  EventEmitter.default = EventEmitter;
  __mods['events'] = EventEmitter;

  // ---------------- axios ----------------
  function mergeCfg(a, b) {
    var o = Object.assign({}, a || {}, b || {});
    o.headers = Object.assign({}, (a && a.headers && a.headers.common) || {}, (a && a.headers) || {}, (b && b.headers) || {});
    delete o.headers.common;
    if ((a && a.params) || (b && b.params)) o.params = Object.assign({}, a && a.params, b && b.params);
    return o;
  }
  function hasHeader(h, name) { name = name.toLowerCase(); return Object.keys(h).some(function (k) { return k.toLowerCase() === name; }); }
  function buildUrl(cfg) {
    var url = String(cfg.url || '');
    if (cfg.baseURL && !/^[a-z][a-z0-9+.-]*:\/\//i.test(url)) url = String(cfg.baseURL).replace(/\/+$/, '') + '/' + url.replace(/^\/+/, '');
    if (cfg.params) {
      var q = '';
      if (cfg.params instanceof G.URLSearchParams) q = cfg.params.toString();
      else q = Object.keys(cfg.params).filter(function (k) { var v = cfg.params[k]; return v !== undefined && v !== null && v !== ''; })
        .map(function (k) { var v = cfg.params[k]; return Array.isArray(v) ? v.map(function (x) { return encodeURIComponent(k) + '=' + encodeURIComponent(x); }).join('&') : encodeURIComponent(k) + '=' + encodeURIComponent(v); }).join('&');
      if (q) url += (url.indexOf('?') >= 0 ? '&' : '?') + q;
    }
    return url;
  }
  function makeResp(r, cfg) {
    var ct = '';
    var hs = r.headers || {};
    var data;
    var rt = cfg.responseType;
    if (rt === 'arraybuffer' || rt === 'stream') data = Buffer.from(__hexToBytes(r.bodyHex || ''));
    else if (rt === 'text') data = r.body;
    else { try { data = JSON.parse(r.body); } catch (e) { data = r.body; } }
    return { status: r.statusCode, statusText: String(r.statusCode), data: data, headers: hs, config: cfg, request: { res: { responseUrl: r.url }, responseURL: r.url, path: r.url } };
  }
  function axiosError(msg, cfg, code, response) {
    var e = new Error(msg);
    e.isAxiosError = true; e.config = cfg; e.code = code; if (response) { e.response = response; e.status = response.status; }
    e.toJSON = function () { return { message: msg, code: code }; };
    return e;
  }
  function makeAxios(defaults) {
    var inst;
    var reqI = [], resI = [];
    async function doRequest(cfg) {
      cfg = mergeCfg(inst.defaults, cfg);
      for (var i = reqI.length - 1; i >= 0; i--) if (reqI[i].fulfilled) cfg = (await reqI[i].fulfilled(cfg)) || cfg;
      var method = String(cfg.method || 'GET').toUpperCase();
      var headers = Object.assign({}, cfg.headers || {});
      var opts = { method: method, headers: headers };
      var data = cfg.data;
      if (data != null) {
        if (typeof data === 'string') {
          opts.body = data;
          if (!hasHeader(headers, 'content-type') && method !== 'GET' && method !== 'HEAD') headers['Content-Type'] = 'application/x-www-form-urlencoded';
        } else if (isBuf(data) || data instanceof Uint8Array || data instanceof ArrayBuffer) {
          opts.bodyHex = __bytesToHex(toBytes(data));
        } else if (data instanceof G.URLSearchParams) {
          opts.body = data.toString();
          if (!hasHeader(headers, 'content-type')) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=utf-8';
        } else if (typeof data === 'object') {
          opts.body = JSON.stringify(data);
          if (!hasHeader(headers, 'content-type')) headers['Content-Type'] = 'application/json';
        } else opts.body = String(data);
      }
      if (cfg.timeout) opts.timeout = cfg.timeout;
      if (cfg.maxRedirects !== undefined) opts.maxRedirects = cfg.maxRedirects;
      if (cfg.responseType === 'arraybuffer' || cfg.responseType === 'stream') opts.binary = true;
      if (cfg.auth && cfg.auth.username !== undefined) headers['Authorization'] = 'Basic ' + Buffer.from(cfg.auth.username + ':' + (cfg.auth.password || '')).toString('base64');
      var url = buildUrl(cfg);
      var raw;
      try { raw = JSON.parse(await __request(url, JSON.stringify(opts))); }
      catch (e) { throw axiosError(e.message, cfg, 'ERR_NETWORK'); }
      if (raw && raw.__err) {
        var m = String(raw.__err);
        throw axiosError(m, cfg, /timed out|timeout/i.test(m) ? 'ECONNABORTED' : 'ERR_NETWORK');
      }
      var resp = makeResp(raw, cfg);
      var ok = cfg.validateStatus ? cfg.validateStatus(resp.status) : (resp.status >= 200 && resp.status < 300);
      if (!ok) {
        var err = axiosError('Request failed with status code ' + resp.status, cfg, resp.status >= 500 ? 'ERR_BAD_RESPONSE' : 'ERR_BAD_REQUEST', resp);
        for (var j = 0; j < resI.length; j++) if (resI[j].rejected) { try { return await resI[j].rejected(err); } catch (e2) { err = e2; } }
        throw err;
      }
      for (var k = 0; k < resI.length; k++) if (resI[k].fulfilled) resp = (await resI[k].fulfilled(resp)) || resp;
      return resp;
    }
    inst = function (a, b) { return typeof a === 'string' ? doRequest(Object.assign({}, b, { url: a })) : doRequest(a); };
    inst.defaults = Object.assign({ headers: {} }, defaults || {});
    inst.interceptors = {
      request: { use: function (f, r) { reqI.push({ fulfilled: f, rejected: r }); return reqI.length - 1; }, eject: function (i) { reqI[i] = {}; } },
      response: { use: function (f, r) { resI.push({ fulfilled: f, rejected: r }); return resI.length - 1; }, eject: function (i) { resI[i] = {}; } }
    };
    inst.request = doRequest;
    ['get', 'delete', 'head', 'options'].forEach(function (m) { inst[m] = function (url, cfg) { return doRequest(Object.assign({}, cfg, { url: url, method: m })); }; });
    ['post', 'put', 'patch'].forEach(function (m) { inst[m] = function (url, data, cfg) { return doRequest(Object.assign({}, cfg, { url: url, method: m, data: data })); }; });
    inst.getUri = function (cfg) { return buildUrl(mergeCfg(inst.defaults, cfg)); };
    inst.create = function (d) { return makeAxios(mergeCfg(inst.defaults, d)); };
    inst.isAxiosError = function (e) { return !!(e && e.isAxiosError); };
    inst.isCancel = function () { return false; };
    inst.all = function (p) { return Promise.all(p); };
    inst.spread = function (f) { return function (a) { return f.apply(null, a); }; };
    inst.default = inst;
    return inst;
  }
  var axios = makeAxios({});
  __mods['axios'] = axios;

  // ---------------- http / https ----------------
  function makeHttp(proto) {
    function parseArgs(a, b, c) {
      var opts, cb;
      if (typeof a === 'string' || (a && typeof a.href === 'string' && a.searchParams)) {
        var u = new G.URL(String(a.href || a));
        opts = { protocol: u.protocol, hostname: u.hostname, port: u.port, path: u.pathname + u.search };
        if (b && typeof b === 'object') opts = Object.assign(opts, b);
        cb = typeof b === 'function' ? b : c;
      } else { opts = a || {}; cb = typeof b === 'function' ? b : undefined; }
      return { opts: opts, cb: cb };
    }
    class IncomingMessage extends EventEmitter {
      constructor(raw, url) {
        super();
        this.statusCode = raw.statusCode; this.statusMessage = ''; this.headers = raw.headers || {}; this.rawHeaders = [];
        this.url = url; this.complete = true; this._raw = raw; this._enc = null;
      }
      setEncoding(e) { this._enc = e || 'utf8'; return this; }
      resume() { return this; } pause() { return this; } destroy() { return this; }
    }
    class ClientRequest extends EventEmitter {
      constructor(opts, cb) {
        super();
        this._opts = opts; this._chunks = []; this._headers = {}; this._done = false; this._destroyed = false; this.aborted = false;
        Object.keys(opts.headers || {}).forEach((k) => { this._headers[k] = opts.headers[k]; });
        if (cb) this.once('response', cb);
        this._timeout = opts.timeout || 0;
      }
      setHeader(k, v) { this._headers[k] = v; return this; }
      getHeader(k) { var lk = String(k).toLowerCase(); var f = Object.keys(this._headers).filter(function (x) { return x.toLowerCase() === lk; })[0]; return f === undefined ? undefined : this._headers[f]; }
      removeHeader(k) { delete this._headers[k]; }
      setTimeout(ms, cb) { this._timeout = ms; if (cb) this.once('timeout', cb); return this; }
      setNoDelay() { return this; } setSocketKeepAlive() { return this; } flushHeaders() {}
      write(d, enc, cb) { if (d != null) this._chunks.push(typeof d === 'string' ? Buffer.from(d, typeof enc === 'string' ? enc : 'utf8') : d); if (typeof enc === 'function') enc(); else if (cb) cb(); return true; }
      end(d, enc, cb) {
        if (typeof d === 'function') { cb = d; d = null; } else if (typeof enc === 'function') { cb = enc; }
        if (d != null) this.write(d, enc);
        if (this._done) return this;
        this._done = true;
        if (cb) this.once('finish', cb);
        var self = this;
        Promise.resolve().then(function () { return self._send(); });
        return this;
      }
      destroy(err) {
        this._destroyed = true;
        if (err) { var self = this; Promise.resolve().then(function () { self.emit('error', err); }); }
        return this;
      }
      abort() { this.aborted = true; this._destroyed = true; this.emit('abort'); }
      async _send() {
        var o = this._opts;
        if (this._destroyed) return;
        var host = o.hostname || (o.host ? String(o.host).replace(/:\d+$/, '') : 'localhost');
        var port = o.port ? ':' + o.port : '';
        if ((proto === 'https' && String(o.port) === '443') || (proto === 'http' && String(o.port) === '80')) port = '';
        var path = o.path || '/';
        var url = (o.protocol ? String(o.protocol).replace(/:?$/, ':') : proto + ':') + '//' + host + port + path;
        var bytes = [];
        this._chunks.forEach(function (c) { var b = toBytes(c); for (var i = 0; i < b.length; i++) bytes.push(b[i]); });
        var reqOpts = { method: String(o.method || 'GET').toUpperCase(), headers: Object.assign({}, this._headers), binary: true, maxRedirects: 0 };
        if (this._timeout) reqOpts.timeout = this._timeout;
        if (bytes.length) reqOpts.bodyHex = __bytesToHex(bytes);
        // Content-Length 交给底层自动计算
        Object.keys(reqOpts.headers).forEach(function (k) { if (k.toLowerCase() === 'content-length' || k.toLowerCase() === 'host') delete reqOpts.headers[k]; });
        var raw;
        try { raw = JSON.parse(await __request(url, JSON.stringify(reqOpts))); } catch (e) { raw = { __err: e.message }; }
        if (this._destroyed) return;
        this.emit('finish');
        if (raw.__err) {
          var m = String(raw.__err);
          if (/timed out|timeout/i.test(m) && this.listenerCount('timeout') > 0) { this.emit('timeout'); return; }
          var er = new Error(m); er.code = /timed out|timeout/i.test(m) ? 'ETIMEDOUT' : 'ECONNRESET';
          this.emit('error', er);
          return;
        }
        var res = new IncomingMessage(raw, url);
        var bodyBytes = __hexToBytes(raw.bodyHex || '');
        this.emit('response', res);
        var self = this;
        Promise.resolve().then(function () {
          if (self._destroyed) return;
          if (bodyBytes.length) res.emit('data', res._enc ? Buffer.from(bodyBytes).toString(res._enc === true ? 'utf8' : res._enc) : Buffer.from(bodyBytes));
          res.emit('end');
          res.emit('close');
        });
      }
    }
    function request(a, b, c) { var p = parseArgs(a, b, c); return new ClientRequest(p.opts, p.cb); }
    function get(a, b, c) { var r = request(a, b, c); r.end(); return r; }
    function Agent(opts) { this.options = opts || {}; }
    Agent.prototype.destroy = function () {};
    return { request: request, get: get, Agent: Agent, globalAgent: new Agent(), ClientRequest: ClientRequest, IncomingMessage: IncomingMessage, METHODS: ['GET', 'POST', 'PUT', 'DELETE', 'HEAD', 'OPTIONS', 'PATCH'], STATUS_CODES: {} };
  }
  __mods['http'] = makeHttp('http');
  __mods['https'] = makeHttp('https');

  // ---------------- fetch ----------------
  function makeHeaders(h) {
    var m = {};
    if (h) {
      if (typeof h.forEach === 'function' && !Array.isArray(h) && h.get) h.forEach(function (v, k) { m[String(k).toLowerCase()] = String(v); });
      else if (Array.isArray(h)) h.forEach(function (p) { m[String(p[0]).toLowerCase()] = String(p[1]); });
      else Object.keys(h).forEach(function (k) { m[k.toLowerCase()] = String(h[k]); });
    }
    return {
      get: function (k) { var v = m[String(k).toLowerCase()]; return v === undefined ? null : v; },
      has: function (k) { return Object.prototype.hasOwnProperty.call(m, String(k).toLowerCase()); },
      set: function (k, v) { m[String(k).toLowerCase()] = String(v); },
      append: function (k, v) { k = String(k).toLowerCase(); m[k] = m[k] ? m[k] + ', ' + v : String(v); },
      delete: function (k) { delete m[String(k).toLowerCase()]; },
      forEach: function (f) { Object.keys(m).forEach(function (k) { f(m[k], k); }); },
      entries: function () { return Object.keys(m).map(function (k) { return [k, m[k]]; })[Symbol.iterator](); },
      keys: function () { return Object.keys(m)[Symbol.iterator](); },
      values: function () { return Object.keys(m).map(function (k) { return m[k]; })[Symbol.iterator](); },
      _m: m
    };
  }
  if (typeof G.Headers === 'undefined') G.Headers = function (h) { return makeHeaders(h); };
  if (typeof G.fetch === 'undefined') {
    G.fetch = async function (input, init) {
      init = init || {};
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      var headers = init.headers && init.headers._m ? init.headers._m : init.headers;
      var res = await axios.request({ url: url, method: init.method || 'GET', headers: headers || {}, data: init.body, responseType: 'arraybuffer', validateStatus: function () { return true; }, maxRedirects: init.redirect === 'manual' ? 0 : undefined });
      var bytes = res.data._bytes;
      var used = false;
      var r = {
        ok: res.status >= 200 && res.status < 300, status: res.status, statusText: '', url: (res.request && res.request.responseURL) || url, redirected: false,
        headers: makeHeaders(res.headers),
        get bodyUsed() { return used; },
        text: async function () { used = true; return Buffer.from(bytes).toString('utf8'); },
        json: async function () { used = true; return JSON.parse(Buffer.from(bytes).toString('utf8')); },
        arrayBuffer: async function () { used = true; return Uint8Array.from(bytes).buffer; },
        clone: function () { return r; }
      };
      return r;
    };
  }

  // ---------------- url / querystring ----------------
  function qsEscape(s) { return encodeURIComponent(String(s)); }
  function qsUnescape(s) { try { return decodeURIComponent(String(s).replace(/\+/g, ' ')); } catch (e) { return String(s); } }
  var querystring = {
    escape: qsEscape, unescape: qsUnescape,
    stringify: function (o, sep, eq) {
      sep = sep || '&'; eq = eq || '=';
      if (!o || typeof o !== 'object') return '';
      return Object.keys(o).map(function (k) {
        var v = o[k];
        if (Array.isArray(v)) return v.map(function (x) { return qsEscape(k) + eq + qsEscape(x == null ? '' : x); }).join(sep);
        return qsEscape(k) + eq + qsEscape(v == null ? '' : v);
      }).filter(Boolean).join(sep);
    },
    parse: function (s, sep, eq) {
      sep = sep || '&'; eq = eq || '=';
      var out = {};
      String(s || '').split(sep).forEach(function (kv) {
        if (!kv) return;
        var i = kv.indexOf(eq);
        var k = qsUnescape(i < 0 ? kv : kv.slice(0, i)), v = i < 0 ? '' : qsUnescape(kv.slice(i + eq.length));
        if (Object.prototype.hasOwnProperty.call(out, k)) { if (Array.isArray(out[k])) out[k].push(v); else out[k] = [out[k], v]; } else out[k] = v;
      });
      return out;
    }
  };
  querystring.encode = querystring.stringify; querystring.decode = querystring.parse;
  __mods['querystring'] = querystring;

  __mods['url'] = {
    URL: G.URL, URLSearchParams: G.URLSearchParams,
    parse: function (s, parseQuery) {
      var str = String(s);
      var m = str.match(/^(?:([a-z][a-z0-9+.-]*:))?(?:\/\/(?:([^@/]*)@)?([^/?#:]*)(?::(\d+))?)?([^?#]*)(\?[^#]*)?(#.*)?$/i) || [];
      var o = { protocol: m[1] || null, slashes: /^[a-z][a-z0-9+.-]*:\/\//i.test(str) || str.indexOf('//') === 0 || null, auth: m[2] || null, host: m[3] ? m[3] + (m[4] ? ':' + m[4] : '') : null, port: m[4] || null, hostname: m[3] || null,
        hash: m[7] || null, search: m[6] || null, query: m[6] ? m[6].slice(1) : null, pathname: m[5] || (m[3] ? '/' : null), path: null, href: str };
      o.path = (o.pathname || '') + (o.search || '') || null;
      if (parseQuery) o.query = querystring.parse(o.query || '');
      return o;
    },
    format: function (o) {
      if (typeof o === 'string') return o;
      if (o && o.href) return o.href;
      var s = (o.protocol ? o.protocol.replace(/:?$/, ':') + '//' : '') + (o.auth ? o.auth + '@' : '') + (o.host || ((o.hostname || '') + (o.port ? ':' + o.port : ''))) + (o.pathname || '');
      var q = o.search || (o.query ? '?' + (typeof o.query === 'string' ? o.query : querystring.stringify(o.query)) : '');
      return s + q + (o.hash || '');
    },
    resolve: function (from, to) { return new G.URL(to, new G.URL(from, 'http://x.invalid/')).href.replace(/^http:\/\/x\.invalid/, ''); },
    fileURLToPath: function (u) { return decodeURIComponent(String(u.href || u).replace(/^file:\/\//, '')); },
    pathToFileURL: function (p) { return new G.URL('file://' + p); }
  };

  // ---------------- util ----------------
  function inspect(v) { try { return typeof v === 'string' ? v : JSON.stringify(v, null, 2); } catch (e) { return String(v); } }
  __mods['util'] = {
    promisify: function (fn) {
      return function () {
        var args = Array.prototype.slice.call(arguments), self = this;
        return new Promise(function (res, rej) {
          args.push(function (err, val) { if (err) rej(err); else res(val); });
          fn.apply(self, args);
        });
      };
    },
    callbackify: function (fn) { return function () { var a = Array.prototype.slice.call(arguments), cb = a.pop(); fn.apply(this, a).then(function (v) { cb(null, v); }, function (e) { cb(e); }); }; },
    inspect: inspect,
    format: function (f) {
      var a = Array.prototype.slice.call(arguments, 1);
      if (typeof f !== 'string') return [f].concat(a).map(inspect).join(' ');
      var i = 0;
      var out = f.replace(/%[sdifjo%]/g, function (m) { if (m === '%%') return '%'; if (i >= a.length) return m; var v = a[i++]; return m === '%d' || m === '%i' || m === '%f' ? String(Number(v)) : m === '%j' || m === '%o' ? JSON.stringify(v) : String(typeof v === 'object' ? inspect(v) : v); });
      return out + (i < a.length ? ' ' + a.slice(i).map(inspect).join(' ') : '');
    },
    inherits: function (c, s) { Object.setPrototypeOf(c.prototype, s.prototype); },
    deprecate: function (f) { return f; },
    isDeepStrictEqual: function (a, b) { return JSON.stringify(a) === JSON.stringify(b); },
    types: { isPromise: function (x) { return !!x && typeof x.then === 'function'; } },
    TextEncoder: G.TextEncoder, TextDecoder: G.TextDecoder
  };

  // ---------------- vm（近似实现）----------------
  // 没有真正的隔离上下文：把 sandbox 属性挂到全局 -> 在全局作用域求值 -> 把新增/变化的全局拷回 sandbox。
  // 为保证沙箱内函数事后被调用时仍能解析到 sandbox 里的全局，这些全局会保留（每次脚本调用都是独立 Runtime，不会跨调用泄漏）。
  __mods['vm'] = (function () {
    var ctxs = new WeakSet();
    function run(code, sandbox) {
      sandbox = sandbox || {};
      var before = {};
      Object.getOwnPropertyNames(G).forEach(function (k) { before[k] = G[k]; });
      Object.keys(sandbox).forEach(function (k) { before[k] = before[k]; G[k] = sandbox[k]; });
      var result = (0, eval)(String(code));
      Object.getOwnPropertyNames(G).forEach(function (k) {
        if (!(k in before) || G[k] !== before[k] || k in sandbox) { try { sandbox[k] = G[k]; } catch (e) { /* 忽略 */ } }
      });
      return result;
    }
    return {
      createContext: function (s) { s = s || {}; ctxs.add(s); return s; },
      isContext: function (s) { return ctxs.has(s); },
      runInContext: function (code, sandbox) { return run(code, sandbox); },
      runInNewContext: function (code, sandbox) { return run(code, sandbox || {}); },
      runInThisContext: function (code) { return (0, eval)(String(code)); },
      Script: function (code) { this.runInContext = function (s) { return run(code, s); }; this.runInNewContext = function (s) { return run(code, s || {}); }; this.runInThisContext = function () { return (0, eval)(String(code)); }; }
    };
  })();

  // ---------------- child_process：QuickJS 沙箱内不可用，给出明确错误 ----------------
  (function () {
    function nope() { var e = new Error('QuickJS 运行时不支持 child_process（需要外部命令的脚本请使用 Node.js 运行时）'); e.code = 'ENOSYS'; return e; }
    __mods['child_process'] = {
      exec: function () { var cb = arguments[arguments.length - 1]; var e = nope(); if (typeof cb === 'function') Promise.resolve().then(function () { cb(e); }); return new EventEmitter(); },
      execFile: function () { var cb = arguments[arguments.length - 1]; var e = nope(); if (typeof cb === 'function') Promise.resolve().then(function () { cb(e); }); return new EventEmitter(); },
      execSync: function () { throw nope(); }, execFileSync: function () { throw nope(); }, spawn: function () { throw nope(); }, spawnSync: function () { throw nope(); }, fork: function () { throw nope(); }
    };
  })();

  // process 补充
  var pr = G.process;
  if (pr) {
    pr.versions = pr.versions || { node: '20.0.0', quickjs: '1' };
    pr.execPath = pr.execPath || 'node';
    pr.argv = pr.argv || ['node', 'script'];
    pr.cwd = pr.cwd || function () { return '/'; };
    pr.exit = pr.exit || function () { throw new Error('process.exit() 在 QuickJS 沙箱中不可用'); };
    pr.nextTick = pr.nextTick || function (f) { var a = Array.prototype.slice.call(arguments, 1); Promise.resolve().then(function () { f.apply(null, a); }); };
    pr.stdout = pr.stdout || { write: function (s) { __log('info', String(s)); return true; } };
    pr.stderr = pr.stderr || { write: function (s) { __log('error', String(s)); return true; } };
    pr.hrtime = pr.hrtime || function () { var t = Date.now(); return [Math.floor(t / 1000), (t % 1000) * 1e6]; };
    pr.on = pr.on || function () { return pr; };
    pr.memoryUsage = pr.memoryUsage || function () { return { rss: 0, heapUsed: 0, heapTotal: 0 }; };
  }
  if (typeof G.performance === 'undefined') G.performance = { now: function () { return Date.now(); } };
})();
