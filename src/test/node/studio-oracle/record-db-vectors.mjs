#!/usr/bin/env node
// Records the DATABASE-path (D) ground truth on text-path-normal documents, next to oracle.json:
//
//   npm ci && node record-db-vectors.mjs [--out ../../testData/studio/db-vectors.json] [--limit N]
//
// The IDE only ever needs D(y) for documents y that are already what Studio's text path T writes, so for every oracle
// corpus input x and every text-vectors.json input x where T succeeds, this takes y = T(x) (identical y are recorded once)
// and records, under three collection configurations, D(y), T(D(y)) and D(D(y)). See DB-PATH.md for what D does and
// README.md for the file format. D runs the unmodified Studio code (tree.js sanitizeDocumentTree, legacy.js
// ensureComarkBody, generate.js contentFromMarkdownDocument) on the row lib/nuxt-content-parse.mjs emulates.
// Deterministic: recording twice gives identical bytes.
import { register } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

register('./lib/hooks.mjs', import.meta.url)

const here = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def }
const repo = path.resolve(here, '../../../..')
const outFile = path.resolve(here, opt('--out', path.join(repo, 'src/test/testData/studio/db-vectors.json')))
const LIMIT = Number(opt('--limit', Infinity))

const { checkPins } = await import('./lib/pins.mjs')
const { studioFiles, rendererFiles } = checkPins(args)
const studioRuntime = path.join(here, 'node_modules/nuxt-studio/dist/module/runtime')
const studioUrl = (rel) => pathToFileURL(path.join(studioRuntime, rel)).href
const generate = await import(studioUrl('utils/document/generate.js'))
const legacy = await import(studioUrl('utils/document/legacy.js'))
const tree = await import(studioUrl('utils/document/tree.js'))
const { nuxtContentParse } = await import('./lib/nuxt-content-parse.mjs')
const { classify, startsWithH1 } = await import('./lib/db-diff.mjs')
const pkgVersion = (name) => JSON.parse(fs.readFileSync(path.join(here, 'node_modules', name, 'package.json'), 'utf8')).version
const versions = Object.fromEntries(['nuxt-studio', 'comark', 'js-yaml', '@nuxtjs/mdc', 'remark-mdc', 'minimark', 'shiki']
  .map((n) => [n, pkgVersion(n)]))

// ---- collection configurations -----------------------------------------------------------------------------------------
// `schema` lists the user fields of the collection's content.config.ts schema, in declaration order. Nuxt Content orders
// the table columns (and so the row's keys) by getOrderedSchemaKeys: id, title, then all keys sorted, whatever the
// declaration order (runtime/internal/schema.js getOrderedSchemaKeys; module.mjs generateCollectionTableDefinition).
const CONFIGS = {
  pageDefault: {
    note: "page collection, no user schema: Nuxt Content's page + meta standard schema (Studio's defaults)",
    collectionType: 'page',
    schema: [],
  },
  pageSchema: {
    note: 'page collection whose content.config.ts schema declares, before the standard fields: date: z.date(), ' +
      'author: z.string(), tags: z.array(z.string()), image: z.object({ src, alt }), order: z.number(), ' +
      'draft: z.boolean().default(false)',
    collectionType: 'page',
    schema: [
      { name: 'date', type: 'date' },
      { name: 'author', type: 'string' },
      { name: 'tags', type: 'json' },
      { name: 'image', type: 'json' },
      { name: 'order', type: 'number' },
      { name: 'draft', type: 'boolean', default: false },
    ],
  },
  data: {
    note: 'data collection, no user schema: only the meta standard schema (id, stem, extension, meta); every front matter ' +
      'key lands in `meta`, and there is no title/description extraction (contentHeading is off for data collections)',
    collectionType: 'data',
    schema: [],
  },
}
const CONFIG_NAMES = Object.keys(CONFIGS)
// Content ids (collection `content`); the file name decides the title Nuxt Content derives when a page has none
// (module.mjs pathMeta: generateTitle(refineUrlPart(basename))). Vector i, configuration c uses FILES[(3i + c) % n].
const FILES = [
  'content/docs/page.md',
  'content/docs/1.getting-started.md',
  'content/docs/index.md',
  'content/blog/2024-01-15-hello-world.md',
  'content/guide/2.api-reference.draft.md',
  'content/docs/1.2.0.md',
  'content/docs/my_file name.md',
  'content/index.md',
]

// ---- the two save paths (as in record.mjs, with the content id and collection as parameters) ---------------------------
// D: file -> (emulated) Nuxt Content row -> host.document.db.get (host.js:177-188) -> contentFromMarkdownDocument.
// A file Nuxt Content cannot insert (an invalid DATE value, a non-numeric INT) is not in the database
// (module.mjs: '"<id>" is ignored because parsing is failed'), so Studio has no document: recorded as an error.
async function dbDocument(src, id, config) {
  let row
  try {
    row = await nuxtContentParse(id, src, { collectionType: config.collectionType, schema: config.schema })
  } catch (e) {
    throw new Error('not in database: ' + firstLine(e))
  }
  const fsPath = id.split('/').slice(1).join('/')
  return legacy.ensureComarkBody(tree.sanitizeDocumentTree({ ...row, fsPath }, null))
}
const D = async (src, id, config) => generate.contentFromMarkdownDocument(await dbDocument(src, id, config))
// T: documentFromContent (comark parse with Studio's options) -> contentFromMarkdownDocument. The collection type and id
// only change document fields that contentFromMarkdownDocument does not read, so T does not depend on them.
const textDocument = (src, id, config) => generate.documentFromContent(id, src, { compress: true, collectionType: config.collectionType })
const T = async (src, id, config) => generate.contentFromMarkdownDocument(await textDocument(src, id, config))
// The renderMarkdown inputs of each path (generate.js:121-129), for classifying differences.
const renderInput = (doc) => {
  const t = legacy.comarkTreeFromLegacyDocument(doc)
  if (!t) return null
  const body = legacy.unbindComarkTree(t)
  return { frontmatter: body.frontmatter || {}, nodes: body.nodes }
}
// Column kinds and defaults of a configuration (for FM-COERCE / FM-DEFAULT)
const columnsOf = (config) => Object.fromEntries([
  ...(config.collectionType === 'page' ? [['title', 'string'], ['description', 'string'], ['path', 'string'], ['seo', 'json'], ['navigation', 'json']] : []),
  ...config.schema.map((f) => [f.name, f.type]),
])
const defaultsOf = (config) => Object.fromEntries(config.schema.filter((f) => 'default' in f).map((f) => [f.name, f.default]))

const firstLine = (e) => String((e && e.message) || e).split('\n')[0]
const safe = async (fn) => { try { return await fn() } catch (e) { return { error: firstLine(e) } } }
const isStr = (v) => typeof v === 'string'

// ---- inputs ------------------------------------------------------------------------------------------------------------
const oracle = JSON.parse(fs.readFileSync(path.join(repo, 'src/test/testData/studio/oracle.json'), 'utf8'))
const textVectors = JSON.parse(fs.readFileSync(path.join(repo, 'src/test/testData/studio/text-vectors.json'), 'utf8')).vectors
const inputs = []
for (const c of oracle.cases) if (isStr(c.text)) inputs.push({ source: 'oracle:' + c.name, x: c.input, text: c.text })
textVectors.forEach((v, i) => { if (isStr(v.text)) inputs.push({ source: 'text-vectors:' + i, x: v.input, text: v.text }) })

// Front matter probes: the corpus has few documents with the custom schema's fields, so every front matter block below is
// recorded with each body (bodies decide what title / description Nuxt Content extracts).
const FM_PROBES = [
  '', 'title: Given\n', "title: ''\n", 'description: Given description\n', "title: Given\ndescription: ''\n",
  'description: D\ntitle: T\nauthor: A\n', 'zeta: 1\nalpha: 2\ntitle: T\n', 'extra: x\ndate: 2024-01-15\n',
  'date: 2024-01-15\nauthor: Jane\ntags:\n  - a\n  - b\nimage:\n  src: /a.png\n  alt: A\norder: 3\ndraft: true\ntitle: With schema\n',
  "date: '2024-01-15T10:20:30Z'\n", 'date: 2024-01-15 10:20:30 +02:00\n', 'date: 2024-02-30\n', 'date: not a date\n', 'date: 2024\n',
  "order: '5'\n", 'order: abc\n', 'order: 1.5\n', 'author: 42\n', 'author: true\n', 'author:\n  name: A\n',
  'tags: single\n', 'tags: []\n', "draft: 'false'\n", 'draft: false\n', 'navigation: true\n', 'navigation: false\n',
  'navigation:\n  title: Nav\n', 'seo:\n  title: S\n', 'seo:\n  title: T\ntitle: T\n', 'nested.key: v\n',
  'nullish: null\nempty: []\nemptyMap: {}\n', "yes: yes\nn: 0o17\nd: 2024-01-15\nnum: '12'\n",
]
const FM_BODIES = [
  '# Heading text\n\nFirst paragraph of the page.\n',
  'First paragraph without a heading.\n\n## Section\n\nMore.\n',
  '# Title with **bold** and `code`\n\n---\n\nDescription with [a link](/x) and *emphasis*.\n',
  '::note\nComponent first\n::\n\n# Late heading\n',
]
FM_PROBES.forEach((fm, i) => FM_BODIES.forEach((body, j) => {
  inputs.push({ source: `fm-probe:${i}.${j}`, x: (fm ? '---\n' + fm + '---\n\n' : '') + body, text: null })
}))

// Database-path reproducers (db-reproducers.json): documents on which a seeded local fuzz run of the Kotlin emulation
// (StudioDbPath) against this recorder's D found a difference (`fuzz`, each already a text-path output y), and hand-written
// documents exercising the constructs the emulation follows (`probes`, recorded through T like the other inputs).
const reproducers = JSON.parse(fs.readFileSync(path.join(here, 'db-reproducers.json'), 'utf8'))
reproducers.fuzz.forEach((y, i) => inputs.push({ source: `db-fuzz:${i}`, x: y, text: null }))
reproducers.probes.forEach((x, i) => inputs.push({ source: `db-probe:${i}`, x, text: null }))

const byY = new Map()
const order = []
for (const it of inputs) {
  // y = T(x): recomputed, and it must be what the committed oracle / text vectors say Studio writes
  const y = await safe(() => T(it.x, FILES[0], CONFIGS.pageDefault))
  if (!isStr(y)) continue
  if (it.text !== null && y !== it.text) {
    console.error(`T(x) differs from the recorded text for ${it.source}; re-record oracle.json / text-vectors.json first`)
    process.exit(3)
  }
  const e = byY.get(y)
  if (e) { e.inputs++; continue }
  if (order.length >= LIMIT) continue
  const entry = { y, source: it.source, inputs: 1 }
  byY.set(y, entry)
  order.push(entry)
}

// ---- record ------------------------------------------------------------------------------------------------------------
const vectors = []
const stats = Object.fromEntries(CONFIG_NAMES.map((n) => [n, {
  vectors: 0, dErrors: 0, dEqualsY: 0, tOfDEqualsD: 0, dOfDEqualsD: 0, dualFixedPoint: 0, categories: {}, structureNotes: {},
  // the same counts over the vectors with T(y) == y, the documents the IDE produces
  onTStable: { vectors: 0, dErrors: 0, dEqualsY: 0, tOfDEqualsD: 0, dOfDEqualsD: 0, dualFixedPoint: 0, categories: {} },
}]))
let yStable = 0
for (const [i, entry] of order.entries()) {
  const { y } = entry
  const ty = await safe(() => T(y, FILES[0], CONFIGS.pageDefault))
  const v = { y, source: entry.source }
  if (entry.inputs > 1) v.inputs = entry.inputs
  v.yStable = ty === y
  if (!v.yStable) v.ty = ty
  else yStable++
  for (const [c, name] of CONFIG_NAMES.entries()) {
    const config = CONFIGS[name]
    const id = FILES[(3 * i + c) % FILES.length]
    const d = await safe(() => D(y, id, config))
    const r = { id }
    const s = stats[name]
    const st = v.yStable ? s.onTStable : null
    s.vectors++
    if (st) st.vectors++
    if (!isStr(d)) {
      r.d = d
      s.dErrors++
      s.categories['error'] = (s.categories['error'] || 0) + 1
      if (st) { st.dErrors++; st.categories['error'] = (st.categories['error'] || 0) + 1 }
      v[name] = r
      continue
    }
    const td = await safe(() => T(d, id, config))
    const dd = await safe(() => D(d, id, config))
    r.dEqualsY = d === y
    if (!r.dEqualsY) r.d = d
    r.tOfDEqualsD = td === d
    if (!r.tOfDEqualsD) r.td = td
    r.dOfDEqualsD = dd === d
    if (!r.dOfDEqualsD) r.dd = dd
    if (r.dEqualsY) s.dEqualsY++
    if (r.tOfDEqualsD) s.tOfDEqualsD++
    if (r.dOfDEqualsD) s.dOfDEqualsD++
    if (r.tOfDEqualsD && r.dOfDEqualsD) s.dualFixedPoint++
    if (st) {
      if (r.dEqualsY) st.dEqualsY++
      if (r.tOfDEqualsD) st.tOfDEqualsD++
      if (r.dOfDEqualsD) st.dOfDEqualsD++
      if (r.tOfDEqualsD && r.dOfDEqualsD) st.dualFixedPoint++
    }
    if (!r.dEqualsY) {
      const tDoc = await safe(() => textDocument(y, id, config))
      const dIn = renderInput(await dbDocument(y, id, config))
      // a y that T cannot parse again (T throws on it) has no text-path tree to compare with
      const notes = []
      r.rules = tDoc.error ? ['Y-T-THROWS'] : classify(renderInput(tDoc), dIn,
        { notes, yStable: v.yStable, firstH1: dIn ? startsWithH1(dIn.nodes) : false, columns: columnsOf(config), defaults: defaultsOf(config) })
      for (const rule of r.rules) s.categories[rule] = (s.categories[rule] || 0) + 1
      if (st) for (const rule of r.rules) st.categories[rule] = (st.categories[rule] || 0) + 1
      for (const n of new Set(notes.map((x) => x.replace(/\[[^\]]{60,}\]/g, '[…]')))) s.structureNotes[n] = (s.structureNotes[n] || 0) + 1
    }
    v[name] = r
  }
  vectors.push(v)
  if ((i + 1) % 250 === 0) console.error(`${i + 1}/${order.length}`)
}
const byCount = (o, limit = Infinity) => Object.fromEntries(Object.entries(o).sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, limit))
for (const s of Object.values(stats)) {
  s.categories = byCount(s.categories)
  s.onTStable.categories = byCount(s.onTStable.categories)
  // the most frequent unexplained structure differences (B-STRUCTURE): `<parent tag>: [T children] vs [D children]`
  s.structureNotes = byCount(s.structureNotes, 25)
}

const summary = {
  inputs: inputs.length,
  inputsFromOracle: inputs.filter((it) => it.source.startsWith('oracle:')).length,
  inputsFromTextVectors: inputs.filter((it) => it.source.startsWith('text-vectors:')).length,
  inputsFromFrontMatterProbes: inputs.filter((it) => it.source.startsWith('fm-probe:')).length,
  inputsFromDbReproducers: inputs.filter((it) => it.source.startsWith('db-fuzz:') || it.source.startsWith('db-probe:')).length,
  vectors: vectors.length,
  yStable,
  byConfig: stats,
}
const header = {
  description: `Nuxt Studio 1.8.0 database path D on text-path output: for ${vectors.length} distinct documents y = T(x) ` +
    `(x = every oracle.json input and text-vectors.json input where T succeeds, and ${FM_PROBES.length * FM_BODIES.length} ` +
    'front matter probes, and the database-path reproducers of db-reproducers.json), D(y), T(D(y)) and D(D(y)) under ' +
    `${CONFIG_NAMES.length} collection configurations. Re-record with \`node record-db-vectors.mjs\` in ` +
    'src/test/node/studio-oracle; see DB-PATH.md and README.md.',
  versions,
  studioFiles,
  rendererFiles,
  configs: CONFIGS,
  files: FILES,
  summary,
}
const head = JSON.stringify(header, null, 1)
fs.mkdirSync(path.dirname(outFile), { recursive: true })
fs.writeFileSync(outFile, head.slice(0, -2) + ',\n "vectors": [\n' + vectors.map((v) => JSON.stringify(v)).join(',\n') + '\n]}\n')
console.log(JSON.stringify(summary, null, 1))
console.log('wrote', path.relative(process.cwd(), outFile), (fs.statSync(outFile).size / 1024).toFixed(0) + ' KiB')
