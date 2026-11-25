// jsdom.rs —— 给 QuickJS 的 cheerio 兼容层提供 DOM 原语（基于 scraper / html5ever）。
// 文档存放在全局表里，JS 侧只拿 (文档id, 节点序号)。每次脚本调用结束后，本次创建的文档会被释放。
// 除标准 CSS 选择器外，额外支持 jQuery 风格扩展：:contains() :eq() :first :last :gt() :lt() :even :odd。

use scraper::node::Node;
use scraper::{ElementRef, Html, Selector};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const MAX_HTML: usize = 8 * 1024 * 1024;
const MAX_DOCS_PER_CALL: usize = 500;

struct Doc {
    html: Html,
    ids: Vec<ego_tree::NodeId>,
    rev: HashMap<ego_tree::NodeId, usize>,
    rank: Vec<u32>,
    /// 片段解析时，fragment 的上下文根元素（对外视作 root）
    frag_root: Option<usize>,
}

static DOCS: OnceLock<Mutex<HashMap<u64, Doc>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);

fn docs() -> &'static Mutex<HashMap<u64, Doc>> {
    DOCS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 单次脚本调用内创建的文档，调用结束统一释放
pub type Owned = Arc<Mutex<Vec<u64>>>;

pub fn new_owned() -> Owned {
    Arc::new(Mutex::new(Vec::new()))
}

pub fn free_owned(o: &Owned) {
    if let (Ok(list), Ok(mut m)) = (o.lock(), docs().lock()) {
        for id in list.iter() {
            m.remove(id);
        }
    }
}

fn build(html: Html, frag: bool) -> Doc {
    let ids: Vec<ego_tree::NodeId> = html.tree.nodes().map(|n| n.id()).collect();
    let rev: HashMap<ego_tree::NodeId, usize> = ids.iter().enumerate().map(|(i, id)| (*id, i)).collect();
    // 文档序（DFS 先序）排名
    let mut rank = vec![0u32; ids.len()];
    let mut r = 0u32;
    for n in html.tree.root().descendants() {
        if let Some(i) = rev.get(&n.id()) {
            rank[*i] = r;
            r += 1;
        }
    }
    let frag_root = if frag {
        html.tree.root().children().find(|c| c.value().is_element()).and_then(|c| rev.get(&c.id()).copied())
    } else {
        None
    };
    Doc { html, ids, rev, rank, frag_root }
}

pub fn load(owned: &Owned, html: &str, frag: bool) -> String {
    if html.len() > MAX_HTML {
        return "ERR:HTML 过大".into();
    }
    {
        let l = owned.lock().unwrap();
        if l.len() >= MAX_DOCS_PER_CALL {
            return "ERR:文档数量超限".into();
        }
    }
    let parsed = if frag { Html::parse_fragment(html) } else { Html::parse_document(html) };
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    docs().lock().unwrap().insert(id, build(parsed, frag));
    owned.lock().unwrap().push(id);
    id.to_string()
}

fn with_doc<R>(d: u64, f: impl FnOnce(&mut Doc) -> Result<R, String>) -> Result<R, String> {
    let mut m = docs().lock().map_err(|_| "DOM 锁异常".to_string())?;
    let doc = m.get_mut(&d).ok_or_else(|| "文档已释放".to_string())?;
    f(doc)
}

fn err(e: String) -> String {
    format!("ERR:{e}")
}

// ---------------- 基础信息 ----------------

fn node_ref<'a>(doc: &'a Doc, i: usize) -> Result<ego_tree::NodeRef<'a, Node>, String> {
    let id = doc.ids.get(i).ok_or("节点不存在")?;
    doc.html.tree.get(*id).ok_or_else(|| "节点不存在".to_string())
}

fn idx(doc: &Doc, n: &ego_tree::NodeRef<Node>) -> i64 {
    doc.rev.get(&n.id()).map(|v| *v as i64).unwrap_or(-1)
}

pub fn info(d: u64, i: usize) -> String {
    match with_doc(d, |doc| {
        let n = node_ref(doc, i)?;
        let is_frag_root = doc.frag_root == Some(i);
        let (t, name, attrs, data) = match n.value() {
            _ if is_frag_root => ("root", String::new(), Value::Null, String::new()),
            Node::Document | Node::Fragment => ("root", String::new(), Value::Null, String::new()),
            Node::Doctype(dt) => ("directive", format!("!{}", dt.name()), Value::Null, String::new()),
            Node::Comment(c) => ("comment", String::new(), Value::Null, c.to_string()),
            Node::Text(t) => ("text", String::new(), Value::Null, t.to_string()),
            Node::Element(e) => {
                let mut m = serde_json::Map::new();
                for (k, v) in e.attrs() {
                    m.insert(k.to_string(), Value::String(v.to_string()));
                }
                let nm = e.name().to_string();
                let t = if nm == "script" { "script" } else if nm == "style" { "style" } else { "tag" };
                (t, nm, Value::Object(m), String::new())
            }
            Node::ProcessingInstruction(_) => ("directive", String::new(), Value::Null, String::new()),
        };
        let parent = if doc.frag_root == Some(i) { -1 } else { n.parent().map(|p| idx(doc, &p)).unwrap_or(-1) };
        let next = n.next_sibling().map(|s| idx(doc, &s)).unwrap_or(-1);
        let prev = n.prev_sibling().map(|s| idx(doc, &s)).unwrap_or(-1);
        Ok(json!({"t": t, "n": name, "a": attrs, "d": data, "p": parent, "s": next, "k": prev, "r": doc.rank[i]}).to_string())
    }) {
        Ok(s) => s,
        Err(e) => err(e),
    }
}

pub fn children(d: u64, i: usize) -> String {
    match with_doc(d, |doc| {
        let n = node_ref(doc, i)?;
        let v: Vec<i64> = n.children().map(|c| idx(doc, &c)).collect();
        Ok(serde_json::to_string(&v).unwrap())
    }) {
        Ok(s) => s,
        Err(e) => err(e),
    }
}

/// 文档的"对外根"：整页解析为 0；片段解析为 frag_root
pub fn root_of(d: u64) -> i64 {
    with_doc(d, |doc| Ok(doc.frag_root.map(|v| v as i64).unwrap_or(0))).unwrap_or(0)
}

// ---------------- 文本 / HTML ----------------

fn text_of(n: &ego_tree::NodeRef<Node>, out: &mut String) {
    for c in n.descendants() {
        if let Node::Text(t) = c.value() {
            out.push_str(t);
        }
    }
}

pub fn text(d: u64, i: usize) -> String {
    match with_doc(d, |doc| {
        let n = node_ref(doc, i)?;
        let mut s = String::new();
        match n.value() {
            Node::Text(t) => s.push_str(t),
            Node::Comment(_) | Node::Doctype(_) | Node::ProcessingInstruction(_) => {}
            _ => text_of(&n, &mut s),
        }
        Ok(s)
    }) {
        Ok(s) => s,
        Err(e) => err(e),
    }
}

fn outer_html(doc: &Doc, n: &ego_tree::NodeRef<Node>) -> String {
    match n.value() {
        Node::Element(_) => ElementRef::wrap(*n).map(|e| e.html()).unwrap_or_default(),
        Node::Text(t) => escape_text(t),
        Node::Comment(c) => format!("<!--{}-->", &**c),
        Node::Doctype(dt) => format!("<!DOCTYPE {}>", dt.name()),
        Node::Document | Node::Fragment => doc.html.html(),
        _ => String::new(),
    }
}

fn escape_text(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('\u{a0}', "&nbsp;")
}

pub fn html(d: u64, i: usize, outer: bool) -> String {
    match with_doc(d, |doc| {
        let n = node_ref(doc, i)?;
        let is_root = doc.frag_root == Some(i) || matches!(n.value(), Node::Document | Node::Fragment);
        if outer {
            if is_root {
                return Ok(n.children().map(|c| outer_html(doc, &c)).collect::<String>());
            }
            return Ok(outer_html(doc, &n));
        }
        // inner
        if let Node::Element(_) = n.value() {
            if !is_root {
                return Ok(ElementRef::wrap(n).map(|e| e.inner_html()).unwrap_or_default());
            }
        }
        Ok(n.children().map(|c| outer_html(doc, &c)).collect::<String>())
    }) {
        Ok(s) => s,
        Err(e) => err(e),
    }
}

// ---------------- 选择器 ----------------

#[derive(Clone, Copy, PartialEq)]
enum Comb {
    Desc,
    Child,
    Adj,
    Gen,
}

fn is_custom_name(n: &str) -> bool {
    matches!(n, "contains" | "eq" | "first" | "last" | "gt" | "lt" | "even" | "odd")
}

/// 以 depth 0 的逗号拆分
fn split_top(s: &str, seps: &[char]) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let (mut par, mut br) = (0i32, 0i32);
    let mut quote: Option<char> = None;
    for c in s.chars() {
        if let Some(q) = quote {
            cur.push(c);
            if c == q {
                quote = None;
            }
            continue;
        }
        match c {
            '"' | '\'' => {
                quote = Some(c);
                cur.push(c)
            }
            '(' => {
                par += 1;
                cur.push(c)
            }
            ')' => {
                par -= 1;
                cur.push(c)
            }
            '[' => {
                br += 1;
                cur.push(c)
            }
            ']' => {
                br -= 1;
                cur.push(c)
            }
            c if par == 0 && br == 0 && seps.contains(&c) => out.push(std::mem::take(&mut cur)),
            _ => cur.push(c),
        }
    }
    out.push(cur);
    out
}

/// 找第一个 depth 0 的自定义伪类：返回 (起点, 名称, 参数, 结束位置)
fn find_custom(s: &str) -> Option<(usize, String, String, usize)> {
    let b: Vec<(usize, char)> = s.char_indices().collect();
    let (mut par, mut br) = (0i32, 0i32);
    let mut quote: Option<char> = None;
    let mut k = 0;
    while k < b.len() {
        let (pos, c) = b[k];
        if let Some(q) = quote {
            if c == q {
                quote = None;
            }
            k += 1;
            continue;
        }
        match c {
            '"' | '\'' => quote = Some(c),
            '(' => par += 1,
            ')' => par -= 1,
            '[' => br += 1,
            ']' => br -= 1,
            ':' if par == 0 && br == 0 => {
                // 读名称
                let mut j = k + 1;
                let mut name = String::new();
                while j < b.len() && (b[j].1.is_ascii_alphanumeric() || b[j].1 == '-' || b[j].1 == '_') {
                    name.push(b[j].1);
                    j += 1;
                }
                if is_custom_name(&name) {
                    let mut arg = String::new();
                    let mut end = if j < b.len() { b[j].0 } else { s.len() };
                    if j < b.len() && b[j].1 == '(' {
                        // 找匹配右括号
                        let mut depth = 0;
                        let mut q2: Option<char> = None;
                        let mut m = j;
                        while m < b.len() {
                            let ch = b[m].1;
                            if let Some(q) = q2 {
                                if ch == q {
                                    q2 = None;
                                }
                            } else if ch == '"' || ch == '\'' {
                                q2 = Some(ch);
                            } else if ch == '(' {
                                depth += 1;
                            } else if ch == ')' {
                                depth -= 1;
                                if depth == 0 {
                                    break;
                                }
                            }
                            m += 1;
                        }
                        if m >= b.len() {
                            return None;
                        }
                        arg = s[b[j].0 + 1..b[m].0].to_string();
                        end = if m + 1 < b.len() { b[m + 1].0 } else { s.len() };
                    }
                    return Some((pos, name, arg, end));
                }
            }
            _ => {}
        }
        k += 1;
    }
    None
}

fn unquote(s: &str) -> String {
    let t = s.trim();
    if t.len() >= 2 && ((t.starts_with('"') && t.ends_with('"')) || (t.starts_with('\'') && t.ends_with('\''))) {
        t[1..t.len() - 1].to_string()
    } else {
        t.to_string()
    }
}

fn parse_sel(sel: &str) -> Result<Selector, String> {
    Selector::parse(sel).map_err(|e| format!("无效选择器 `{sel}`: {e}"))
}

fn is_element(doc: &Doc, i: usize) -> bool {
    node_ref(doc, i).map(|n| n.value().is_element()).unwrap_or(false)
}

fn std_select(doc: &Doc, ctx: &[usize], sel: &str) -> Result<Vec<usize>, String> {
    let s = parse_sel(sel)?;
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for c in ctx {
        let n = node_ref(doc, *c)?;
        for d in n.descendants().skip(1) {
            if let Some(el) = ElementRef::wrap(d) {
                if s.matches(&el) {
                    let i = idx(doc, &d) as usize;
                    if seen.insert(i) {
                        out.push(i);
                    }
                }
            }
        }
    }
    Ok(out)
}

fn std_matches(doc: &Doc, i: usize, sel: &str) -> Result<bool, String> {
    let n = node_ref(doc, i)?;
    match ElementRef::wrap(n) {
        Some(el) => Ok(parse_sel(sel)?.matches(&el)),
        None => Ok(false),
    }
}

fn sort_uniq(doc: &Doc, v: &mut Vec<usize>) {
    v.sort_by_key(|i| doc.rank[*i]);
    v.dedup();
}

fn node_text(doc: &Doc, i: usize) -> String {
    let mut s = String::new();
    if let Ok(n) = node_ref(doc, i) {
        text_of(&n, &mut s);
    }
    s
}

fn apply_filter(doc: &Doc, set: Vec<usize>, name: &str, arg: &str) -> Result<Vec<usize>, String> {
    let len = set.len() as i64;
    let num = || -> Result<i64, String> { arg.trim().parse::<i64>().map_err(|_| format!(":{name}() 参数无效")) };
    Ok(match name {
        "contains" => {
            let needle = unquote(arg);
            set.into_iter().filter(|i| node_text(doc, *i).contains(&needle)).collect()
        }
        "eq" => {
            let n = num()?;
            let k = if n < 0 { len + n } else { n };
            if k >= 0 && k < len { vec![set[k as usize]] } else { vec![] }
        }
        "first" => set.into_iter().take(1).collect(),
        "last" => set.into_iter().last().into_iter().collect(),
        "gt" => {
            let n = num()?;
            set.into_iter().enumerate().filter(|(k, _)| (*k as i64) > n).map(|(_, v)| v).collect()
        }
        "lt" => {
            let n = num()?;
            set.into_iter().enumerate().filter(|(k, _)| (*k as i64) < n).map(|(_, v)| v).collect()
        }
        "even" => set.into_iter().enumerate().filter(|(k, _)| k % 2 == 0).map(|(_, v)| v).collect(),
        "odd" => set.into_iter().enumerate().filter(|(k, _)| k % 2 == 1).map(|(_, v)| v).collect(),
        _ => set,
    })
}

fn parent_of(doc: &Doc, i: usize) -> Option<usize> {
    node_ref(doc, i).ok().and_then(|n| n.parent()).map(|p| idx(doc, &p) as usize)
}

fn element_sibling(doc: &Doc, i: usize, next: bool) -> Option<usize> {
    let mut cur = node_ref(doc, i).ok()?;
    loop {
        cur = if next { cur.next_sibling()? } else { cur.prev_sibling()? };
        if cur.value().is_element() {
            return Some(idx(doc, &cur) as usize);
        }
    }
}

fn leading_comb(rest: &str) -> Option<(Comb, &str)> {
    let first = rest.chars().next()?;
    if first.is_whitespace() || matches!(first, '>' | '+' | '~') {
        let t = rest.trim_start();
        let (c, tail) = match t.chars().next() {
            Some('>') => (Comb::Child, &t[1..]),
            Some('+') => (Comb::Adj, &t[1..]),
            Some('~') => (Comb::Gen, &t[1..]),
            _ => (Comb::Desc, t),
        };
        Some((c, tail.trim_start()))
    } else {
        None
    }
}

fn has_top_comb(s: &str) -> bool {
    split_top(s.trim(), &[' ', '>', '+', '~']).len() > 1
}

fn apply_rest(doc: &Doc, set: Vec<usize>, rest: &str) -> Result<Vec<usize>, String> {
    if rest.trim().is_empty() {
        return Ok(set);
    }
    if let Some((comb, tail)) = leading_comb(rest) {
        if tail.is_empty() {
            return Ok(set);
        }
        let mut out = Vec::new();
        for s in &set {
            match comb {
                Comb::Desc => out.extend(eval_group(doc, &[*s], tail)?),
                Comb::Child => {
                    let r = eval_group(doc, &[*s], tail)?;
                    if has_top_comb(tail) {
                        out.extend(r);
                    } else {
                        out.extend(r.into_iter().filter(|x| parent_of(doc, *x) == Some(*s)));
                    }
                }
                Comb::Adj | Comb::Gen => {
                    if let Some(p) = parent_of(doc, *s) {
                        let r = eval_group(doc, &[p], tail)?;
                        let mut sibs = Vec::new();
                        let mut cur = *s;
                        while let Some(nx) = element_sibling(doc, cur, true) {
                            sibs.push(nx);
                            cur = nx;
                            if comb == Comb::Adj {
                                break;
                            }
                        }
                        out.extend(r.into_iter().filter(|x| sibs.contains(x)));
                    }
                }
            }
        }
        sort_uniq(doc, &mut out);
        return Ok(out);
    }
    // 紧跟的限定：自定义伪类，或普通复合选择器片段
    if let Some((0, name, arg, end)) = find_custom(rest) {
        let s2 = apply_filter(doc, set, &name, &arg)?;
        return apply_rest(doc, s2, &rest[end..]);
    }
    // 切出到下一个顶层组合符 / 自定义伪类之前的复合片段
    let cut_custom = find_custom(rest).map(|(p, ..)| p).unwrap_or(rest.len());
    let cut_comb = {
        let mut pos = rest.len();
        let (mut par, mut br) = (0i32, 0i32);
        let mut quote: Option<char> = None;
        for (p, c) in rest.char_indices() {
            if let Some(q) = quote {
                if c == q {
                    quote = None;
                }
                continue;
            }
            match c {
                '"' | '\'' => quote = Some(c),
                '(' => par += 1,
                ')' => par -= 1,
                '[' => br += 1,
                ']' => br -= 1,
                c if par == 0 && br == 0 && (c.is_whitespace() || matches!(c, '>' | '+' | '~')) => {
                    pos = p;
                    break;
                }
                _ => {}
            }
        }
        pos
    };
    let cut = cut_custom.min(cut_comb);
    let compound = &rest[..cut];
    let remainder = &rest[cut..];
    let sel = format!("*{compound}");
    let mut kept = Vec::new();
    for i in set {
        if std_matches(doc, i, &sel)? {
            kept.push(i);
        }
    }
    apply_rest(doc, kept, remainder)
}

fn eval_group(doc: &Doc, ctx: &[usize], group: &str) -> Result<Vec<usize>, String> {
    let group = group.trim();
    if group.is_empty() {
        return Ok(vec![]);
    }
    match find_custom(group) {
        None => std_select(doc, ctx, group),
        Some((p, name, arg, end)) => {
            let mut prefix = group[..p].to_string();
            let tp = prefix.trim_end();
            if tp.is_empty() || tp.ends_with(['>', '+', '~']) || prefix.ends_with(char::is_whitespace) {
                prefix.push('*');
            }
            let s = eval_group(doc, ctx, &prefix)?;
            let s = apply_filter(doc, s, &name, &arg)?;
            apply_rest(doc, s, &group[end..])
        }
    }
}

pub fn select(d: u64, ctx_json: &str, sel: &str) -> String {
    match with_doc(d, |doc| {
        let ctx: Vec<usize> = serde_json::from_str::<Vec<i64>>(ctx_json)
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter(|v| *v >= 0)
            .map(|v| v as usize)
            .collect();
        let mut out = Vec::new();
        for g in split_top(sel, &[',']) {
            out.extend(eval_group(doc, &ctx, &g)?);
        }
        sort_uniq(doc, &mut out);
        Ok(serde_json::to_string(&out).unwrap())
    }) {
        Ok(s) => s,
        Err(e) => err(e),
    }
}

pub fn matches(d: u64, i: usize, sel: &str) -> String {
    match with_doc(d, |doc| {
        // 自定义伪类：在父节点范围内求值再判断是否包含该节点
        let mut found = false;
        for g in split_top(sel, &[',']) {
            if find_custom(&g).is_some() {
                let scope = parent_of(doc, i).unwrap_or(0);
                if eval_group(doc, &[scope], &g)?.contains(&i) {
                    found = true;
                }
            } else if std_matches(doc, i, &g)? {
                found = true;
            }
            if found {
                break;
            }
        }
        let _ = is_element(doc, i);
        Ok(found)
    }) {
        Ok(b) => b.to_string(),
        Err(e) => err(e),
    }
}

// ---------------- 修改 ----------------

pub fn remove(d: u64, i: usize) -> String {
    match with_doc(d, |doc| {
        let id = *doc.ids.get(i).ok_or("节点不存在")?;
        if let Some(mut n) = doc.html.tree.get_mut(id) {
            n.detach();
        }
        Ok("ok".to_string())
    }) {
        Ok(s) => s,
        Err(e) => err(e),
    }
}

/// 克隆：把节点的 outerHTML 解析成新的片段文档，返回 [新文档id, 节点序号]
pub fn clone_node(owned: &Owned, d: u64, i: usize) -> String {
    let outer = html(d, i, true);
    if outer.starts_with("ERR:") {
        return outer;
    }
    let nd = load(owned, &outer, true);
    if nd.starts_with("ERR:") {
        return nd;
    }
    let ndid: u64 = nd.parse().unwrap_or(0);
    match with_doc(ndid, |doc| {
        let root = doc.frag_root.ok_or("克隆失败")?;
        let first = node_ref(doc, root)?.children().next().map(|c| idx(doc, &c)).unwrap_or(-1);
        Ok(json!([ndid, first]).to_string())
    }) {
        Ok(s) => s,
        Err(e) => err(e),
    }
}
