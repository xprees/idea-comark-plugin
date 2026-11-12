// Classifies why Studio's database path D rewrites a text-path document y (D(y) != y), by the rules of DB-PATH.md.
//
// Input: the front matter and comark tree renderMarkdown receives on each path for the same y: `t` from the text path
// (comark parse of y) and `d` from the database path (Nuxt Content row -> mdcToComark). renderMarkdown is the same on both
// paths, so every difference between y and D(y) comes from a difference between these two inputs. The rule ids returned
// are the headings of DB-PATH.md. A difference no rule explains is reported as FM-OTHER / B-STRUCTURE, and a text difference
// with identical inputs as RENDER.

// ---- front matter ------------------------------------------------------------------------------------------------------
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v) && !(v instanceof Date)
const jsonish = (v) => JSON.stringify(v, (k, x) => (Object.is(x, -0) ? '-0' : x === undefined ? '$undefined' : x))
const same = (a, b) => jsonish(a) === jsonish(b)
// cleanDataKeys drops these keys from the front matter, also when they come from the document's meta (schema.js:4, 35-36, 60-64)
const RESERVED = ['id', 'fsPath', 'stem', 'extension', '__hash__', 'path', 'body', 'meta', 'rawbody']

function frontMatterRules(tFm, dFm, ctx, out) {
  // a front matter that is not a mapping (a scalar or a list) on the text path: the yaml package parse differs
  if (tFm != null && !isObj(tFm)) out.add('FM-YAML')
  const t = isObj(tFm) ? tFm : {}
  const d = isObj(dFm) ? dFm : {}
  const tKeys = Object.keys(t)
  const dKeys = Object.keys(d)
  for (const k of dKeys) {
    if (k in t) continue
    if (k === 'title') out.add(ctx.firstH1 ? 'FM-TITLE-H1' : 'FM-TITLE-FILE')
    else if (k === 'description') out.add('FM-DESCRIPTION')
    else if (k === 'excerpt') out.add('FM-EXCERPT')
    else if (ctx.defaults && k in ctx.defaults && same(d[k], ctx.defaults[k])) out.add('FM-DEFAULT')
    else if (tKeys.some((tk) => tk.startsWith(k + '.'))) out.add('FM-UNFLATTEN')
    else out.add('FM-OTHER')
  }
  for (const k of tKeys) {
    if (k in d) continue
    const v = t[k]
    if (RESERVED.includes(k)) out.add('FM-RESERVED')
    else if (v === null || v === undefined) out.add('FM-DROP-NULL')
    else if (Array.isArray(v) && v.length === 0) out.add('FM-DROP-EMPTY-LIST')
    else if (k === 'navigation' && (v === true || v === 'true')) out.add('FM-DROP-NAVIGATION')
    else if ((k === 'title' || k === 'description') && !v) out.add('FM-DROP-EMPTY-TITLE')
    else if (k.includes('.')) out.add('FM-UNFLATTEN')
    else if (k === 'seo') out.add('FM-SEO')
    else out.add('FM-OTHER')
  }
  for (const k of tKeys) {
    if (!(k in d) || same(t[k], d[k])) continue
    if (ctx.columns && ctx.columns[k] && ctx.columns[k] !== 'json') out.add('FM-COERCE')
    else if (k === 'seo') out.add('FM-SEO')
    else if ((k === 'title' || k === 'description') && typeof t[k] !== 'string') out.add('FM-COERCE')
    else if (hasShared(t[k]) || hasDate(t[k]) || hasNonJson(t[k])) out.add('FM-JSON')
    else out.add('FM-YAML')
  }
  // key order of the keys both sides have
  const common = tKeys.filter((k) => k in d)
  const dOrder = dKeys.filter((k) => k in t)
  if (common.join('\u0000') !== dOrder.join('\u0000')) out.add('FM-KEY-ORDER')
}
const hasDate = (v) => v instanceof Date || (Array.isArray(v) ? v.some(hasDate) : isObj(v) && Object.values(v).some(hasDate))
const hasNonJson = (v) => v === undefined || (typeof v === 'number' && (!Number.isFinite(v) || Object.is(v, -0))) ||
  (Array.isArray(v) ? v.some(hasNonJson) : isObj(v) && Object.values(v).some(hasNonJson))
function hasShared(root) {
  const seen = new Set()
  let shared = false
  const walk = (v) => {
    if (v === null || typeof v !== 'object' || v instanceof Date) return
    if (seen.has(v)) { shared = true; return }
    seen.add(v)
    for (const x of Array.isArray(v) ? v : Object.values(v)) walk(x)
  }
  walk(root)
  return shared
}

const MARKDOWN_TAGS = new Set(['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'table',
  'thead', 'tbody', 'tr', 'th', 'td', 'hr', 'br', 'a', 'img', 'em', 'strong', 'del', 'span', 'template', 'input', '#root'])
const isComponentTag = (tag) => typeof tag === 'string' && !MARKDOWN_TAGS.has(tag)
const isComponent = (n) => Array.isArray(n) && isComponentTag(n[0]) && !isHtml(n)
// a span without attributes: comark parses a bare `[text]` (no link, no attributes) as a span
const isSpan = (n) => Array.isArray(n) && n[0] === 'span' && Object.keys(n[1] || {}).filter((k) => k !== '$').length === 0 && !isHtml(n)
const textOf = (n, brackets = false) => typeof n === 'string' ? n : !Array.isArray(n) || n[0] === null ? ''
  : (brackets && isSpan(n) ? '[' + n.slice(2).map((c) => textOf(c, brackets)).join('') + ']' : n.slice(2).map((c) => textOf(c, brackets)).join(''))
const emojiCount = (str) => (str.match(/\p{Extended_Pictographic}/gu) || []).length
const note = (out, text) => { if (out.notes) out.notes.push(text) }
// ---- body --------------------------------------------------------------------------------------------------------------
const tagOf = (n) => (Array.isArray(n) ? n[0] : typeof n === 'string' ? '#text' : '#other')
const attrsOf = (n) => (Array.isArray(n) && n[1] && typeof n[1] === 'object' ? n[1] : {})
const kids = (n) => (Array.isArray(n) ? n.slice(2) : [])
const isComment = (n) => Array.isArray(n) && n[0] === null
const isHtml = (n) => Array.isArray(n) && !!(attrsOf(n).$ && attrsOf(n).$.html)
const prim = (v) => (v === null || typeof v !== 'object' ? String(v) : JSON.stringify(v))
const camel = (k) => k.replace(/-([a-z])/g, (_, c) => c.toUpperCase())

// Compares the attributes of two elements with the same tag; adds the rules that explain the difference.
function attrRules(tag, ta, da, out) {
  // keys whose value is undefined are not written (sanitizeDocumentTree sets language/style/meta to undefined, tree.js:129-138)
  const t = Object.fromEntries(Object.entries(ta).filter(([k, v]) => k !== '$' && v !== undefined))
  const d = Object.fromEntries(Object.entries(da).filter(([k, v]) => k !== '$' && v !== undefined))
  if ('rel' in t && !('rel' in d)) { out.add('B-ATTR-REL'); delete t.rel }
  for (const k of Object.keys(t)) {
    if (k in d) continue
    if (k.startsWith(':') && k.slice(1) in d) {
      out.add('B-ATTR-UNBIND')
      d[k] = d[k.slice(1)]
      delete d[k.slice(1)]
      continue
    }
    const c = Object.keys(d).find((dk) => !(dk in t) && (dk === camel(k) || dk.toLowerCase() === k.replace(/-/g, '').toLowerCase()))
    if (c) { out.add('B-ATTR-CAMEL'); d[k] = d[c]; delete d[c] }
  }
  const tKeys = Object.keys(t)
  const dKeys = Object.keys(d)
  for (const k of tKeys.filter((k) => !(k in d))) {
    if ((tag === 'th' || tag === 'td') && (k === 'align' || k === 'style')) out.add('B-TABLE-ALIGN')
    else if (/^(href|src|xlinkhref|action)$/i.test(k) || /^(:|v-bind:)?(on|@)/i.test(k) || /^(srcdoc|formaction|innerhtml|outerhtml)$/i.test(k)) out.add('B-ATTR-UNSAFE')
    else if (tag === 'pre' || tag === 'code') out.add('B-CODE')
    else out.add('B-ATTR-OTHER')
  }
  for (const k of dKeys.filter((k) => !(k in t))) {
    if (/^h[1-6]$/.test(tag) && k === 'id') out.add('B-HEADING-ID')
    else if (tag === 'pre' || tag === 'code') out.add('B-CODE')
    else out.add('B-ATTR-OTHER')
  }
  for (const k of tKeys) {
    if (!(k in d) || prim(t[k]) === prim(d[k])) continue
    if (k === 'href' && /\.md(#|$)/.test(String(t[k]))) out.add('B-LINK-MD')
    else if (tag === 'pre' && k === 'highlights') out.add('B-CODE-HIGHLIGHTS')
    else if (k === 'href' || k === 'src') out.add(d[k] === undefined || d[k] === 'undefined' ? 'B-ATTR-UNSAFE' : 'B-ATTR-OTHER')
    else if (typeof t[k] === 'string' && /&(#\d+|#x[0-9a-f]+|[a-z]+);/i.test(t[k])) out.add('B-ATTR-ENTITY')
    else if (k === 'class' && String(t[k]).split(/\s+/).sort().join(' ') === String(d[k]).split(/\s+/).sort().join(' ')) out.add('B-CLASS-ORDER')
    else if (tag === 'pre' || tag === 'code') out.add('B-CODE')
    else out.add('B-ATTR-OTHER')
  }
  const commonT = tKeys.filter((k) => k in d)
  const commonD = dKeys.filter((k) => k in t)
  if (commonT.join('\u0000') !== commonD.join('\u0000')) out.add('B-ATTR-ORDER')
}

// li children after @nuxtjs/mdc compileHast (parser/compiler.js:21-38): paragraphs are unwrapped, each later one after a br
function flattenLi(children) {
  let prev = false
  const r = []
  for (const c of children) {
    if (Array.isArray(c) && c[0] === 'p') {
      if (prev) r.push(['br', {}])
      prev = true
      r.push(...kids(c))
    } else r.push(c)
  }
  return r
}
const mergeText = (list) => {
  const r = []
  for (const n of list) {
    if (typeof n === 'string' && typeof r[r.length - 1] === 'string') r[r.length - 1] += n
    else r.push(n)
  }
  return r
}

// Renderer-equivalent normalisation applied to both trees before comparing (comark renderMarkdown output is the same):
// - the text path's shiki plugin leaves class="shiki" on <pre>, which userBlockAttrs drops (stringify/attributes.js:93-96);
// - attributes of the <code> inside <pre> are only read for a language-* class when <pre> has no language
//   (stringify/handlers/pre.js:6-11), and __ignoreMap is never written;
// - a component whose only child is a plain paragraph renders like the paragraph's children (comark's autoUnwrap on the
//   text path removes that paragraph at parse time, Nuxt Content keeps it).
function normalise(n, isText) {
  if (!Array.isArray(n) || n[0] === null) return n
  let [tag, attrs, ...children] = n
  attrs = { ...(attrs || {}) }
  if (tag === 'pre') {
    if (isText && typeof attrs.class === 'string' && /^(shiki|shj)/.test(attrs.class)) delete attrs.class
    children = children.map((c) => {
      if (!Array.isArray(c) || c[0] !== 'code') return c
      const ca = c[1] || {}
      const lang = !attrs.language && typeof ca.class === 'string' && ca.class.split(' ').find((x) => x.startsWith('language-'))
      return ['code', lang ? { class: lang } : {}, ...c.slice(2)]
    })
  }
  return autoUnwrap([tag, attrs, ...children.map((c) => normalise(c, isText))])
}
// comark 0.7.0 dist/internal/parse/auto-unwrap.js (one level; normalise recurses)
function autoUnwrap(node) {
  const [tag, props, ...children] = node
  const nonEmpty = children.filter((c) => typeof c !== 'string' || (c && c.trim()))
  if (nonEmpty.length !== 1 || typeof nonEmpty[0] === 'string' || nonEmpty[0][0] !== 'p') return node
  const pAttrs = nonEmpty[0][1]
  const merged = pAttrs && Object.keys(pAttrs).length > 0 ? { ...pAttrs, ...props } : props
  return [tag, merged, ...nonEmpty[0].slice(2)]
}
// D's text with autolink literals (remark-gfm, which Nuxt Content enables and Studio's comark parse does not: linkify false)
// turned back into text
function unlink(list) {
  return mergeText(list.map((n) => {
    if (tagOf(n) !== 'a') return n
    const href = String(attrsOf(n).href || '')
    const text = kids(n).every((c) => typeof c === 'string') ? kids(n).join('') : null
    if (text !== null && (href === text || href === 'mailto:' + text || href === 'http://' + text || href === 'https://' + text)) return text
    return n
  }))
}

function nodeRules(t, d, out) {
  if (typeof t === 'string' && typeof d === 'string') {
    if (t === d) return
    if (t.includes('{{')) out.add('B-BINDING')
    else if (t.replace(/\s+/g, ' ').trim() === d.replace(/\s+/g, ' ').trim()) out.add('B-WHITESPACE')
    else if (/\p{Extended_Pictographic}/u.test(d) && t.includes(':') && emojiCount(d) > emojiCount(t)) out.add('B-EMOJI')
    else if (t.split('\n').filter((l) => !/^\s*:{2,}\s*$/.test(l)).join('\n').trim() === d.trim()) out.add('B-CLOSING-MARKER')
    else out.add('B-TEXT')
    return
  }
  if (tagOf(t) !== tagOf(d)) {
    if (Array.isArray(d) && typeof d[0] === 'string' && textOf(t).includes('<' + d[0])) out.add('B-HTML')
    else if (isSpan(t) && typeof d === 'string') out.add('B-BRACKET-SPAN')
    else if (isComponent(t) && typeof d === 'string') out.add('B-INLINE-COMPONENT')
    else if (typeof t === 'string' && t.includes('{{')) out.add('B-BINDING')
    else if (tagOf(d) === 'binding') out.add('B-BINDING')
    else if (isHtml(t)) out.add('B-HTML')
    else if (tagOf(t) === 'template' || tagOf(d) === 'template') out.add('B-SLOT')
    else { out.add('B-STRUCTURE'); note(out, `${tagOf(t)} vs ${tagOf(d)}`) }
    return
  }
  const tag = tagOf(t)
  if (isHtml(t)) {
    // raw HTML: the T path keeps html-marked nodes (rendered back as HTML), D has plain elements parsed by rehype-raw
    // (rendered as Markdown or components)
    out.add('B-HTML')
  } else attrRules(tag, attrsOf(t), attrsOf(d), out)
  childrenRules(tag, kids(t), kids(d), out)
}

function childrenRules(tag, tk, dk, out) {
  let t = tk
  if (t.some(isComment)) {
    out.add('B-COMMENT')
    t = mergeText(t.filter((n) => !isComment(n)))
  }
  if (tag === 'li' && t.some((n) => tagOf(n) === 'p')) {
    const flat = mergeText(flattenLi(t))
    if (!same(flat, t) && flat.length === dk.length && flat.every((n, i) => tagOf(n) === tagOf(dk[i]))) {
      out.add('B-LI-PARAGRAPHS')
      t = flat
    }
  }
  if (dk.some((n) => tagOf(n) === 'a')) {
    const u = unlink(dk)
    if (u.length !== dk.length && same(u, t)) { out.add('B-AUTOLINK'); return }
  }
  if (t.length !== dk.length || t.some((n, i) => tagOf(n) !== tagOf(dk[i]))) {
    const dText = dk.map(textOf).join('')
    const tText = t.map((n) => textOf(n)).join('')
    if (dk.some((n) => Array.isArray(n) && typeof n[0] === 'string' && tText.includes('<' + n[0]))) { out.add('B-HTML'); return }
    if (t.some(isSpan) && dk.every((n) => !isSpan(n)) && t.map((n) => textOf(n, true)).join('') === dText) { out.add('B-BRACKET-SPAN'); return }
    if (t.some(isComponent) && !dk.some(isComponent) && dText.includes(':')) { out.add('B-INLINE-COMPONENT'); return }
    if (isComponentTag(tag) && t.length > dk.length && tagOf(t[0]) === 'hr') { out.add('B-COMPONENT-HR'); return }
  }
  if (t.length !== dk.length) {
    if (t.some(isHtml)) out.add('B-HTML')
    else if (t.some((n) => typeof n === 'string' && n.includes('{{'))) out.add('B-BINDING')
    else if (tag === 'template' || [...t, ...dk].some((n) => tagOf(n) === 'template')) out.add('B-SLOT')
    else { out.add('B-STRUCTURE'); note(out, `${tag}: [${t.map(tagOf)}] vs [${dk.map(tagOf)}]`) }
    return
  }
  for (let i = 0; i < t.length; i++) nodeRules(t[i], dk[i], out)
}

/**
 * @param {{frontmatter: object, nodes: any[]}} t text-path renderMarkdown input for y
 * @param {{frontmatter: object, nodes: any[]} | null} d database-path renderMarkdown input for y (null: no tree)
 * @param {{firstH1: boolean, defaults?: object, columns?: object}} ctx
 * @returns {string[]} sorted rule ids
 */
export function classify(t, d, ctx) {
  if (!d) return ['B-NO-TREE']
  const out = new Set()
  if (ctx.notes) out.notes = ctx.notes
  if (ctx.details) {
    // debugging aid: ctx.details collects, per rule, where it was first seen
    const add = out.add.bind(out)
    out.add = (rule) => { if (!ctx.details[rule]) ctx.details[rule] = new Error().stack.split('\n')[2].trim(); return add(rule) }
  }
  frontMatterRules(t.frontmatter, d.frontmatter, ctx, out)
  if (hasShared(t.frontmatter) && !hasShared(d.frontmatter)) out.add('FM-JSON')
  childrenRules('#root', t.nodes.map((n) => normalise(n, true)), d.nodes.map((n) => normalise(n, false)), out)
  // y itself is not what T writes for y: the differences between y and T(y) are the text path's own
  if (ctx.yStable === false) out.add('Y-NOT-T-STABLE')
  if (out.size === 0) out.add('RENDER')
  return [...out].sort()
}

// Whether the top-level body starts (ignoring hr) with an h1: @nuxtjs/mdc contentHeading (parser/index.js:103-119)
export function startsWithH1(nodes) {
  const els = nodes.filter((n) => Array.isArray(n) && n[0] !== null && n[0] !== 'hr')
  return els.length > 0 && els[0][0] === 'h1'
}
