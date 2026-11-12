// Derived from @nuxt/content 3.16.2 (MIT, Copyright (c) Nuxt Project); used only to record test data.
// Emulation of @nuxt/content 3.16.2 turning a .md file into the row that Nuxt Studio reads
// from the browser-side SQLite database. Line refs: @nuxt/content-3.16.2 dist/module.mjs
//   createParser            1464-1554   (parserOptions, remark-emoji default, highlight plugin, schema split, seo, path-meta)
//   markdown transformer    1219-1262   (parseMarkdown from @nuxtjs/mdc/runtime, compress -> minimark fromHast)
//   link handler            1284-1310   (normaliseLink: relative *.md links -> slugified path without .md)
//   path-meta / generatePath 1170-1217
//   _getHighlightPlugin     1413-1461   (shiki, default theme material-theme-palenight, compress style map)
//   pageStandardSchema/meta 2203-2333   generateCollectionInsert 2528-2558 (SQL value coercion)
//   runtime/internal/collection.js refineContentFields (JSON.parse json columns, Boolean() booleans)
//   runtime/internal/schema.js getOrderedSchemaKeys (id, title, ...sorted), describeProperty/getPropertyType (SQL types)
//   formatDate / formatDateTime / toUtcDate 428-467 (DATE and DATETIME columns)
// Assumptions (configurable by env):
//   CTYPE=page|data (default page)   SCHEMA=extra,schema,keys (strings)   HIGHLIGHT=0 to disable shiki
//   opts.schema entries are either a key name (a VARCHAR column) or { name, type, default } where type is the column kind
//   a user schema field gets: 'string' (z.string, VARCHAR), 'number' (z.number, INT), 'boolean' (z.boolean, BOOLEAN),
//   'date' (z.date, DATE: zodToJsonSchema with dateStrategy 'format:date', module.mjs 3116), 'datetime' (DATETIME) or
//   'json' (z.array / z.object, a JSON TEXT column).
//   no user remark/rehype plugins, no mdc.highlight config, default pathMeta.
import { parseMarkdown, createShikiHighlighter, rehypeHighlight } from '@nuxtjs/mdc/runtime'
import { createOnigurumaEngine } from 'shiki/engine/oniguruma'
import { fromHast } from 'minimark/hast'
import { normalizeUri } from 'micromark-util-sanitize-uri'
import { visit } from 'unist-util-visit'
import { hash } from 'ohash'
import slugify from 'slugify'
import { pascalCase } from 'scule'
import { withLeadingSlash, withoutTrailingSlash, isRelative } from 'ufo'
import remarkEmoji from 'remark-emoji'

// ---- module.mjs 1170-1217
const SEMVER_REGEX = /^\d+(?:\.\d+)*(?:\.x)?$/
const generatePath = (path, { forceLeadingSlash = true, slugifyOptions = {} } = {}) => {
  path = path.split('/').map((part) => slugify(refineUrlPart(part), slugifyOptions)).join('/')
  return forceLeadingSlash ? withLeadingSlash(withoutTrailingSlash(path)) : path
}
const generateTitle = (path) => path.split(/[\s-]/g).map(pascalCase).join(' ')
function refineUrlPart(name) {
  name = name.split(/[/:]/).pop()
  if (SEMVER_REGEX.test(name)) return name
  return name.replace(/(\d+\.)?(.*)/, '$2').replace(/^index(\.draft)?$/, '').replace(/\.draft$/, '')
}
const describeId = (id) => {
  const [source, ...parts] = id.split(/[:/]/)
  const [, basename, extension] = parts[parts.length - 1]?.match(/(.*)\.([^.]+)$/) || []
  if (basename) parts[parts.length - 1] = basename
  return { source, stem: (parts || []).join('/'), extension, basename: basename || '' }
}
function pathMeta(content) {
  const { basename, extension, stem } = describeId(content.id)
  return { path: generatePath(stem, { slugifyOptions: { lower: true } }), ...content, title: content.title || generateTitle(refineUrlPart(basename)), stem, extension }
}
// ---- module.mjs 1284-1310
function link(state, node) {
  const properties = { ...node.attributes || {}, href: normalizeUri(normaliseLink(node.url)) }
  if (node.title !== null && node.title !== undefined) properties.title = node.title
  const result = { type: 'element', tagName: 'a', properties, children: state.all(node) }
  state.patch(node, result)
  return state.applyData(node, result)
}
function normaliseLink(link2) {
  const match = link2.match(/#.+$/)
  const h = match ? match[0] : ''
  if (link2.replace(/#.+$/, '').endsWith('.md') && (isRelative(link2) || !/^https?/.test(link2) && !link2.startsWith('/')))
    return generatePath(link2.replace('.md' + h, ''), { forceLeadingSlash: false }) + h
  return link2
}
// ---- module.mjs 1413-1461 (theme default material-theme-palenight, compress: true)
let highlightPlugin
async function getHighlightPlugin() {
  if (highlightPlugin) return highlightPlugin
  const langs = ['bash', 'html', 'mdc', 'vue', 'yml', 'scss', 'ts', 'typescript']
  const theme = (await import('shiki/themes/material-theme-palenight.mjs')).default
  const bundledLangs = await Promise.all(langs.map(async (l) => [l, (await import(`@shikijs/langs/${l}`)).default]))
  const highlighter = createShikiHighlighter({
    bundledThemes: { default: theme }, bundledLangs: Object.fromEntries(bundledLangs),
    engine: createOnigurumaEngine(import('shiki/wasm')), getMdcConfigs: () => Promise.resolve([]),
  })
  highlightPlugin = {
    instance: rehypeHighlight,
    options: {
      highlighter: async (code, lang, th, opts) => {
        const result = await highlighter(code, lang, th, opts)
        const stylesMap = {}
        visit({ type: 'element', children: result.tree }, (n) => !!n.properties?.style, (n) => {
          const style = n.properties.style
          stylesMap[style] = stylesMap[style] || 's' + hash(style).substring(0, 4)
          n.properties.class = `${n.properties.class || ''} ${stylesMap[style]}`.trim()
          n.properties.style = undefined
        })
        result.style = Object.entries(stylesMap).map(([s, c]) => `html pre.shiki code .${c}, html code.shiki .${c}{${s}}`).join('') + result.style
        return result
      },
      theme: { default: theme },
    },
  }
  return highlightPlugin
}

// ---- module.mjs 428-467
const formatDate = (date) => {
  const d = toUtcDate(date)
  if (Number.isNaN(d.getTime())) throw new TypeError(`Invalid date value: "${date}"`)
  return d.toISOString().slice(0, 10)
}
const formatDateTime = (datetime) => {
  const d = toUtcDate(datetime)
  if (Number.isNaN(d.getTime())) throw new TypeError(`Invalid datetime value: "${datetime}"`)
  return d.toISOString().slice(0, 19).replace('T', ' ')
}
const STRUCTURED = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$/i
function toUtcDate(value) {
  if (value instanceof Date) return value
  const input = String(value).trim()
  const match = STRUCTURED.exec(input)
  if (!match) return new Date(input)
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3])
  const hour = Number(match[4] || 0), minute = Number(match[5] || 0), second = Number(match[6] || 0)
  const offset = match[7]
  const utc = new Date(Date.UTC(year, month - 1, day, hour, minute, second))
  if (utc.getUTCFullYear() !== year || utc.getUTCMonth() + 1 !== month || utc.getUTCDate() !== day || utc.getUTCHours() !== hour || utc.getUTCMinutes() !== minute || utc.getUTCSeconds() !== second) {
    return new Date(Number.NaN)
  }
  if (offset && offset.toUpperCase() !== 'Z') return new Date(input.includes('T') ? input : input.replace(' ', 'T'))
  return utc
}

// ---- schema (pageStandardSchema + metaStandardSchema), getOrderedSchemaKeys
function schemaFor(collectionType, extra) {
  // type: 'string' | 'json' | 'boolean' ; default
  const s = { id: { t: 'string' }, stem: { t: 'string' }, extension: { t: 'string' }, meta: { t: 'json' } }
  if (collectionType === 'page') Object.assign(s, {
    path: { t: 'string' }, title: { t: 'string' }, description: { t: 'string' },
    seo: { t: 'json', default: {} }, body: { t: 'json' }, navigation: { t: 'json', default: true },
  })
  for (const k of extra) {
    const f = typeof k === 'string' ? { name: k, type: 'string' } : k
    const p = { t: f.type }
    if ('default' in f) p.default = f.default
    s[f.name] = s[f.name] || p
  }
  const keys = Array.from(new Set([s.id ? 'id' : undefined, s.title ? 'title' : undefined, ...Object.keys(s).sort()].filter(Boolean)))
  return { s, keys }
}

export async function nuxtContentParse(id, source, opts = {}) {
  const collectionType = opts.collectionType || process.env.CTYPE || 'page'
  const extra = opts.schema || (process.env.SCHEMA || '').split(',').filter(Boolean)
  const useHighlight = opts.highlight ?? process.env.HIGHLIGHT !== '0'
  const { s, keys } = schemaFor(collectionType, extra)
  let body = String(source)
  if (body.includes('\r\n')) body = body.replace(/\r\n/g, '\n') // 1505-1507
  const rehypePlugins = {}
  if (useHighlight) rehypePlugins.highlight = await getHighlightPlugin()
  const parsed = await parseMarkdown(body, {
    compress: true, // parserOptions.markdown.compress
    highlight: undefined,
    remark: { plugins: { 'remark-emoji': { instance: remarkEmoji, options: {} } } },
    rehype: { plugins: rehypePlugins, options: { handlers: { link } } },
    contentHeading: collectionType === 'page',
  }, { fileOptions: { id, body, path: id, extension: '.md' } })
  const parsedContent = {
    ...parsed.data,
    excerpt: parsed.excerpt ? fromHast(parsed.excerpt) : undefined,
    body: { ...fromHast(parsed.body), toc: parsed.toc },
    id, title: parsed.data?.title || undefined,
  }
  // createParser 1520-1545
  const { id: _id, __metadata, ...fields } = parsedContent
  const result = { id }, meta = {}
  for (const key of Object.keys(fields)) (keys.includes(key) ? (result[key] = parsedContent[key]) : (meta[key] = parsedContent[key]))
  result.meta = meta
  if (keys.includes('seo')) { const seo = result.seo = result.seo || {}; seo.title = seo.title || result.title; seo.description = seo.description || result.description }
  const pm = pathMeta(parsedContent)
  for (const key of ['path', 'title', 'stem', 'extension']) if (keys.includes(key) && result[key] === undefined) result[key] = pm[key]
  // generateCollectionInsert 2528-2558 + refineContentFields: emulate SQL storage round trip
  const row = {}
  for (const key of keys) {
    const p = s[key]
    const v = (typeof result[key] === 'undefined' || String(result[key]) === 'null') ? ('default' in p ? p.default : null) : result[key]
    if (v === null) { row[key] = null; continue }
    if (p.t === 'json') row[key] = JSON.parse(JSON.stringify(v))
    else if (p.t === 'boolean') row[key] = !!v
    else if (p.t === 'number') {
      // INT column: Number(value) is written into the SQL text; NaN/Infinity are not SQL literals, so the insert fails
      const n = Number(v)
      if (!Number.isFinite(n)) throw new TypeError(`Invalid number value: "${v}"`)
      row[key] = n
    } else if (p.t === 'date') row[key] = formatDate(v)
    else if (p.t === 'datetime') row[key] = formatDateTime(v)
    else row[key] = String(v)
  }
  return row
}
