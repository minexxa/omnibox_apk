/*
 * cheerio-shim.js —— QuickJS 下的 cheerio 兼容层。
 * DOM 解析/选择器由 Rust(jsdom.rs，基于 scraper/html5ever) 提供，通过 __dom* 原语调用；
 * 这里实现 cheerio 的链式 API（只读为主：不支持 attr 写入、append/html(str) 等修改类 API，用到会明确报错）。
 * 额外支持 jQuery 风格选择器：:contains() :eq() :first :last :gt() :lt() :even :odd。
 */
'use strict';

function chk(r) {
  if (typeof r === 'string' && r.charCodeAt(0) === 69 && r.slice(0, 4) === 'ERR:') throw new Error(r.slice(4));
  return r;
}

// ---------- 节点 ----------
const infoCache = new Map();    // doc -> Map(idx -> info)
const kidsCache = new Map();    // doc -> Map(idx -> [idx])
const nodeCache = new Map();    // doc -> Map(idx -> Node)

function docMap(m, d) {
  let x = m.get(d);
  if (!x) { x = new Map(); m.set(d, x); }
  return x;
}
function info(d, i) {
  const m = docMap(infoCache, d);
  let v = m.get(i);
  if (!v) { v = JSON.parse(chk(__domInfo(d, i))); m.set(i, v); }
  return v;
}
function kids(d, i) {
  const m = docMap(kidsCache, d);
  let v = m.get(i);
  if (!v) { v = JSON.parse(chk(__domChildren(d, i))); m.set(i, v); }
  return v;
}
function invalidate(d) { infoCache.delete(d); kidsCache.delete(d); }

class Node {
  constructor(d, i) { this._d = d; this._i = i; }
  get _info() { return info(this._d, this._i); }
  get type() { return this._info.t; }
  get name() { const t = this._info.t; return (t === 'tag' || t === 'script' || t === 'style' || t === 'directive') ? this._info.n : undefined; }
  get tagName() { return this.name; }
  get nodeName() { const t = this._info.t; return t === 'text' ? '#text' : t === 'comment' ? '#comment' : t === 'root' ? '#document' : (this._info.n || '').toUpperCase(); }
  get attribs() { const a = this._info.a; return a && typeof a === 'object' ? a : undefined; }
  get data() { const t = this._info.t; return (t === 'text' || t === 'comment') ? this._info.d : undefined; }
  get nodeValue() { return this.data === undefined ? null : this.data; }
  get nodeType() { const t = this._info.t; return t === 'text' ? 3 : t === 'comment' ? 8 : t === 'root' ? 9 : t === 'directive' ? 10 : 1; }
  get children() {
    const t = this._info.t;
    if (t === 'text' || t === 'comment' || t === 'directive') return undefined;
    return kids(this._d, this._i).map((k) => nodeOf(this._d, k));
  }
  get childNodes() { return this.children; }
  get parent() { const p = this._info.p; return p >= 0 ? nodeOf(this._d, p) : null; }
  get parentNode() { return this.parent; }
  get next() { const s = this._info.s; return s >= 0 ? nodeOf(this._d, s) : null; }
  get prev() { const s = this._info.k; return s >= 0 ? nodeOf(this._d, s) : null; }
  get nextSibling() { return this.next; }
  get previousSibling() { return this.prev; }
  get firstChild() { const c = this.children; return c && c.length ? c[0] : null; }
  get lastChild() { const c = this.children; return c && c.length ? c[c.length - 1] : null; }
}
function nodeOf(d, i) {
  const m = docMap(nodeCache, d);
  let n = m.get(i);
  if (!n) { n = new Node(d, i); m.set(i, n); }
  return n;
}
function isEl(n) { const t = n._info.t; return t === 'tag' || t === 'script' || t === 'style'; }
function cmpNode(a, b) { return a._d !== b._d ? a._d - b._d : a._info.r - b._info.r; }
function uniqSort(nodes) {
  const seen = new Set(); const out = [];
  for (const n of nodes) { if (!seen.has(n)) { seen.add(n); out.push(n); } }
  return out.sort(cmpNode);
}
function uniq(nodes) {
  const seen = new Set(); const out = [];
  for (const n of nodes) { if (!seen.has(n)) { seen.add(n); out.push(n); } }
  return out;
}

// ---------- 选择 ----------
function selectFrom(nodes, sel) {
  const byDoc = new Map();
  for (const n of nodes) {
    if (!(n instanceof Node)) continue;
    if (!byDoc.has(n._d)) byDoc.set(n._d, []);
    byDoc.get(n._d).push(n._i);
  }
  let out = [];
  for (const [d, ids] of byDoc) {
    const r = JSON.parse(chk(__domSelect(d, JSON.stringify(ids), String(sel))));
    for (const i of r) out.push(nodeOf(d, i));
  }
  return byDoc.size > 1 ? uniqSort(out) : out;
}
function matchesSel(n, sel) { return chk(__domMatches(n._d, n._i, String(sel))) === 'true'; }
function isDescendant(anc, n) {
  let p = n.parent;
  while (p) { if (p === anc) return true; p = p.parent; }
  return false;
}

// ---------- Cheerio 对象 ----------
function Cheerio(nodes, prev) {
  const arr = nodes || [];
  for (let i = 0; i < arr.length; i++) this[i] = arr[i];
  Object.defineProperty(this, 'length', { value: arr.length, enumerable: false, writable: true });
  Object.defineProperty(this, 'prevObject', { value: prev, enumerable: false, writable: true });
}
const P = Cheerio.prototype;
const mk = (nodes, prev) => new Cheerio(nodes, prev);
const arr = (c) => Array.prototype.slice.call(c);
const unsupported = (n) => function () { throw new Error('cheerio 兼容层(QuickJS) 暂不支持 .' + n + '()，请把该脚本切换到 Node.js 运行时'); };

function toNodeList(x, self) {
  if (x == null) return [];
  if (x instanceof Cheerio) return arr(x);
  if (x instanceof Node) return [x];
  if (Array.isArray(x)) return x.filter((v) => v instanceof Node);
  return [];
}
function filterBy(self, arg, negate) {
  let keep;
  if (typeof arg === 'string') keep = (n) => isEl(n) && matchesSel(n, arg);
  else if (typeof arg === 'function') keep = (n, i) => !!arg.call(n, i, n);
  else { const set = new Set(toNodeList(arg)); keep = (n) => set.has(n); }
  return arr(self).filter((n, i) => (negate ? !keep(n, i) : keep(n, i)));
}

P.find = function (sel) {
  if (typeof sel === 'string') return mk(selectFrom(arr(this), sel), this);
  const cand = toNodeList(sel);
  return mk(cand.filter((c) => arr(this).some((n) => isDescendant(n, c))), this);
};
P.children = function (sel) {
  let out = [];
  for (const n of this) { if (n instanceof Node && n._info.t !== 'text') for (const k of kids(n._d, n._i)) { const c = nodeOf(n._d, k); if (isEl(c)) out.push(c); } }
  out = uniq(out);
  if (sel) out = out.filter((c) => matchesSel(c, sel));
  return mk(out, this);
};
P.contents = function () {
  const out = [];
  for (const n of this) for (const k of kids(n._d, n._i)) out.push(nodeOf(n._d, k));
  return mk(uniq(out), this);
};
P.parent = function (sel) {
  let out = [];
  for (const n of this) { const p = n.parent; if (p && p._info.t !== 'root') out.push(p); }
  out = uniqSort(out);
  if (sel) out = out.filter((c) => matchesSel(c, sel));
  return mk(out, this);
};
P.parents = function (sel) {
  let out = [];
  for (const n of this) { let p = n.parent; while (p && p._info.t !== 'root') { out.push(p); p = p.parent; } }
  out = this.length > 1 ? uniqSort(out).reverse() : out;
  if (sel) out = out.filter((c) => matchesSel(c, sel));
  return mk(out, this);
};
P.closest = function (sel) {
  const out = [];
  for (const n of this) {
    let p = n;
    while (p && p._info.t !== 'root') {
      if (isEl(p) && (typeof sel === 'string' ? matchesSel(p, sel) : toNodeList(sel).includes(p))) { out.push(p); break; }
      p = p.parent;
    }
  }
  return mk(uniqSort(out), this);
};
function sibs(n) {
  const p = n.parent; if (!p) return [];
  return p.children.filter(isEl);
}
P.siblings = function (sel) {
  let out = [];
  for (const n of this) out = out.concat(sibs(n).filter((s) => s !== n));
  out = uniqSort(out);
  if (sel) out = out.filter((c) => matchesSel(c, sel));
  return mk(out, this);
};
function walkSib(n, dir, all) {
  const out = [];
  let c = dir > 0 ? n.next : n.prev;
  while (c) { if (isEl(c)) { out.push(c); if (!all) break; } c = dir > 0 ? c.next : c.prev; }
  return out;
}
P.next = function (sel) { let o = []; for (const n of this) o = o.concat(walkSib(n, 1, false)); if (sel) o = o.filter((c) => matchesSel(c, sel)); return mk(uniqSort(o), this); };
P.prev = function (sel) { let o = []; for (const n of this) o = o.concat(walkSib(n, -1, false)); if (sel) o = o.filter((c) => matchesSel(c, sel)); return mk(uniqSort(o), this); };
P.nextAll = function (sel) { let o = []; for (const n of this) o = o.concat(walkSib(n, 1, true)); if (sel) o = o.filter((c) => matchesSel(c, sel)); return mk(uniqSort(o), this); };
P.prevAll = function (sel) { let o = []; for (const n of this) o = o.concat(walkSib(n, -1, true)); if (sel) o = o.filter((c) => matchesSel(c, sel)); return mk(uniqSort(o).reverse(), this); };
P.first = function () { return this.eq(0); };
P.last = function () { return this.eq(-1); };
P.eq = function (i) { i = +i; const k = i < 0 ? this.length + i : i; return mk(k >= 0 && k < this.length ? [this[k]] : [], this); };
P.slice = function (a, b) { return mk(arr(this).slice(a, b), this); };
P.get = function (i) { return i === undefined ? arr(this) : this[i < 0 ? this.length + i : i]; };
P.toArray = function () { return arr(this); };
P.index = function (sel) {
  if (!this.length) return -1;
  const n = this[0];
  if (sel === undefined) { const p = n.parent; return p ? p.children.filter(isEl).indexOf(n) : 0; }
  const list = typeof sel === 'string' ? selectFrom([n.parent].filter(Boolean), sel) : toNodeList(sel);
  return list.indexOf(n);
};
P.filter = function (arg) { return mk(filterBy(this, arg, false), this); };
P.not = function (arg) { return mk(filterBy(this, arg, true), this); };
P.is = function (arg) { return filterBy(this, arg, false).length > 0; };
P.has = function (arg) {
  const out = arr(this).filter((n) => {
    if (typeof arg === 'string') return selectFrom([n], arg).length > 0;
    return toNodeList(arg).some((c) => isDescendant(n, c));
  });
  return mk(out, this);
};
P.map = function (fn) {
  let out = [];
  arr(this).forEach((n, i) => {
    const r = fn.call(n, i, n);
    if (r != null) out = out.concat(r instanceof Cheerio ? arr(r) : Array.isArray(r) ? r : [r]);
  });
  return mk(out, this);
};
P.each = function (fn) {
  const list = arr(this);
  for (let i = 0; i < list.length; i++) { if (fn.call(list[i], i, list[i]) === false) break; }
  return this;
};
P.add = function (x, ctx) {
  let more;
  if (typeof x === 'string') {
    const base = this.length ? this[0] : null;
    more = base ? selectFrom([nodeOf(base._d, __domRoot(base._d))], x) : [];
  } else more = toNodeList(x);
  return mk(uniqSort(arr(this).concat(more)), this);
};
P.end = function () { return this.prevObject || mk([], undefined); };
P.addBack = function () { return mk(uniqSort(arr(this).concat(this.prevObject ? arr(this.prevObject) : [])), this); };

// ---- 内容 ----
P.text = function (v) {
  if (v !== undefined) return unsupported('text(value)').call(this);
  let s = '';
  for (const n of this) s += chk(__domText(n._d, n._i));
  return s;
};
P.html = function (v) {
  if (v !== undefined) return unsupported('html(value)').call(this);
  if (!this.length) return null;
  const n = this[0];
  if (!isEl(n) && n._info.t !== 'root') return null;
  return chk(__domHtml(n._d, n._i, false));
};
P.outerHtml = function () { return arr(this).map((n) => chk(__domHtml(n._d, n._i, true))).join(''); };
P.toString = function () { return this.outerHtml(); };
const RBOOL = /^(?:autofocus|autoplay|async|checked|controls|defer|disabled|hidden|loop|multiple|open|readonly|required|scoped|selected)$/i;
P.attr = function (name, val) {
  if (val !== undefined || (name && typeof name === 'object')) return unsupported('attr(name, value)').call(this);
  if (!this.length) return name === undefined ? undefined : undefined;
  const a = this[0].attribs;
  if (name === undefined) return a;
  if (!a) return undefined;
  if (!Object.prototype.hasOwnProperty.call(a, name)) return undefined;
  // 与 cheerio 一致：布尔属性存在时返回属性名
  if (RBOOL.test(name)) return name;
  return a[name];
};
P.removeAttr = unsupported('removeAttr');
P.prop = function (name) {
  if (!this.length) return undefined;
  const n = this[0];
  if (name === 'tagName' || name === 'nodeName') return (n.name || '').toUpperCase();
  if (name === 'innerHTML') return this.html();
  if (name === 'outerHTML') return this.outerHtml();
  if (name === 'textContent' || name === 'innerText') return this.text();
  if (name === 'checked' || name === 'selected' || name === 'disabled') return this.attr(name) !== undefined;
  return this.attr(name);
};
P.val = function (v) {
  if (v !== undefined) return unsupported('val(value)').call(this);
  if (!this.length) return undefined;
  const n = this[0];
  if (n.name === 'textarea') return this.text();
  return this.attr('value');
};
P.data = function (key) {
  if (!this.length) return undefined;
  const a = this[0].attribs || {};
  const conv = (s) => {
    if (s === 'true') return true; if (s === 'false') return false; if (s === 'null') return null;
    if (s !== '' && !isNaN(s) && String(+s) === s) return +s;
    if (/^[[{]/.test(s)) { try { return JSON.parse(s); } catch (e) { /* 原样返回 */ } }
    return s;
  };
  if (key === undefined) {
    const o = {};
    Object.keys(a).forEach((k) => { if (k.indexOf('data-') === 0) o[k.slice(5).replace(/-([a-z])/g, (m, c) => c.toUpperCase())] = conv(a[k]); });
    return o;
  }
  const k = 'data-' + String(key).replace(/([A-Z])/g, '-$1').toLowerCase();
  return Object.prototype.hasOwnProperty.call(a, k) ? conv(a[k]) : undefined;
};
P.hasClass = function (c) {
  return arr(this).some((n) => ((n.attribs && n.attribs.class) || '').split(/\s+/).indexOf(String(c)) >= 0);
};
['addClass', 'removeClass', 'toggleClass', 'css', 'append', 'prepend', 'appendTo', 'prependTo', 'after', 'before', 'insertAfter', 'insertBefore',
  'replaceWith', 'wrap', 'wrapAll', 'unwrap', 'empty', 'serialize', 'serializeArray'].forEach((m) => { P[m] = unsupported(m); });

// ---- 修改（仅 remove / clone）----
P.remove = function (sel) {
  let list = arr(this);
  if (sel) list = list.filter((n) => matchesSel(n, sel));
  const docs = new Set();
  for (const n of list) { chk(__domRemove(n._d, n._i)); docs.add(n._d); }
  docs.forEach(invalidate);
  return this;
};
P.clone = function () {
  const out = [];
  for (const n of this) {
    const r = JSON.parse(chk(__domClone(n._d, n._i)));
    if (r[1] >= 0) out.push(nodeOf(r[0], r[1]));
  }
  return mk(out, this);
};
P[Symbol.iterator] = function () { return arr(this)[Symbol.iterator](); };

// ---------- load / $ ----------
const HTML_RE = /^[^#<]*(<[\w\W]+>)[^>]*$/;

function load(content, options, isDocument) {
  let html = content == null ? '' : (typeof content === 'string' ? content : (content && content._bytes ? content.toString('utf8') : String(content)));
  const frag = isDocument === false;
  const d = Number(chk(__domLoad(html, frag)));
  const rootNode = nodeOf(d, __domRoot(d));

  function $(sel, ctx) {
    if (sel == null || sel === '') return mk([], undefined);
    if (typeof sel === 'function') { sel.call(rootNode, $); return mk([rootNode], undefined); }
    if (sel instanceof Cheerio) return mk(arr(sel), undefined);
    if (sel instanceof Node) return mk([sel], undefined);
    if (Array.isArray(sel)) return mk(sel.filter((v) => v instanceof Node), undefined);
    if (typeof sel === 'string') {
      if (HTML_RE.test(sel) && sel.trim().charAt(0) === '<') {
        const fd = Number(chk(__domLoad(sel, true)));
        const fr = __domRoot(fd);
        return mk(kids(fd, fr).map((k) => nodeOf(fd, k)), undefined);
      }
      let from;
      if (ctx === undefined || ctx === null) from = [rootNode];
      else if (typeof ctx === 'string') from = selectFrom([rootNode], ctx);
      else from = toNodeList(ctx);
      return mk(selectFrom(from, sel), ctx === undefined ? undefined : $(ctx));
    }
    return mk([], undefined);
  }
  $.root = () => mk([rootNode], undefined);
  $.html = (x) => {
    if (x === undefined) return chk(__domHtml(d, rootNode._i, true));
    const list = toNodeList(x instanceof Cheerio ? x : x);
    return list.map((n) => chk(__domHtml(n._d, n._i, true))).join('');
  };
  $.xml = $.html;
  $.text = (x) => (x === undefined ? mk([rootNode]).text() : mk(toNodeList(x)).text());
  $.contains = (a, b) => isDescendant(a, b);
  $.parseHTML = (s) => $(String(s));
  $.fn = P;
  $._root = rootNode;
  return $;
}

const cheerio = { load, default: { load }, version: '1.0.0-quickjs-shim', Cheerio };
cheerio.default.load = load;
module.exports = cheerio;
