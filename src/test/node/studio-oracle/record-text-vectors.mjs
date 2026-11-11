#!/usr/bin/env node
// Records text-path vectors for the Kotlin port of Studio's comark parse (StudioTreeBuilder), next to oracle.json:
//
//   npm ci && node record-text-vectors.mjs [--count 3000] [--chars 1000] [--yaml 1000] [--seed 20261112] [--mutations 4]
//                                          [--out <file>]
//
// text-vectors.json holds seeded random Markdown documents: `count` built from the oracle corpus (windows of corpus lines,
// spliced together and mutated with Markdown, Comark, HTML and YAML punctuation), `chars` short strings drawn from an
// alphabet of Markdown-significant characters, and `yaml` documents whose front matter (or component props) is random
// YAML assembled from corpus lines and YAML punctuation. For each one it records what Nuxt Studio
// 1.8.0's text path does with it: the comark tree and front matter `renderMarkdown` receives (`tree`, `frontmatter`,
// encoded like oracle.json) and the text it writes (`text`), or `{"error": "<first line>"}` where Studio throws.
// The generator has a fixed seed, so re-recording gives the same file. One vector per line.
import { register } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

register('./lib/hooks.mjs', import.meta.url)

const here = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def }
const COUNT = Number(opt('--count', 3000))
const MUTATIONS = Number(opt('--mutations', 4))
const CHARS = Number(opt('--chars', 1000))
const YAMLS = Number(opt('--yaml', 1000))
const repo = path.resolve(here, '../../../..')
const outFile = path.resolve(opt('--out', path.join(repo, 'src/test/testData/studio/text-vectors.json')))
const studioRuntime = path.join(here, 'node_modules/nuxt-studio/dist/module/runtime')
const studioUrl = (rel) => pathToFileURL(path.join(studioRuntime, rel)).href
const generate = await import(studioUrl('utils/document/generate.js'))
const legacy = await import(studioUrl('utils/document/legacy.js'))
const { encode } = await import('./lib/encode.mjs')

const DOC_ID = 'content/docs/page.md'
const dropPositions = (node) => {
  if (!Array.isArray(node)) return node
  const [tag, attrs, ...children] = node
  let a = attrs
  if (attrs && typeof attrs === 'object' && attrs.$ && typeof attrs.$ === 'object' && 'line' in attrs.$) {
    const { line: _line, ...rest } = attrs.$
    a = { ...attrs }
    if (Object.keys(rest).length) a.$ = rest
    else delete a.$
  }
  return [tag, a, ...children.map(dropPositions)]
}
const firstLine = (e) => String((e && e.message) || e).split('\n')[0]

// ---- corpus lines ------------------------------------------------------------------------------------------------------
const oracle = JSON.parse(fs.readFileSync(path.join(repo, 'src/test/testData/studio/oracle.json'), 'utf8'))
const docs = oracle.cases.map((c) => c.input.split('\n'))

let seed = Number(opt('--seed', 20261112))
function rand() {
  seed = (Math.imul(seed, 1103515245) + 12345) >>> 0
  seed ^= seed >>> 13
  seed = Math.imul(seed, 0x5bd1e995) >>> 0
  seed ^= seed >>> 15
  return (seed >>> 0) / 4294967296
}
const int = (n) => Math.floor(rand() * n)
const pick = (a) => a[int(a.length)]

const SNIPPETS = ['*', '**', '_', '__', '`', '``', '~~', '[', ']', '(', ')', '{', '}', '<', '>', ':', '::', ':::', '#', '|',
  '\\', '&', '&amp;', '&#35;', '&copy', '!', '-', '+', '1.', '> ', '- ', '* ', '1) ', '    ', '\t', ' ', '  ', '\n', '\n\n',
  '---', '```', '~~~', '{.cls}', '{#id}', '{a="b"}', ':icon', ':smile:', ':rocket:', '[x] ', '[ ] ', '<br>', '</div>', '<div>',
  '<!-- c -->', '<span class="a">', '</span>', '#slot', '::card', '::', 'https://example.com', '<https://a.b>', '"', "'",
  '[link](/path)', '![img](/i.png)', '[span]{.c}', '|a|b|\n|-|-|\n|1|2|']

function windowOf(lines) {
  const start = int(lines.length)
  return lines.slice(start, start + 1 + int(12))
}

function mutate(s) {
  const n = 1 + int(MUTATIONS)
  for (let i = 0; i < n; i++) {
    const pos = int(s.length + 1)
    const r = rand()
    if (r < 0.55) s = s.slice(0, pos) + pick(SNIPPETS) + s.slice(pos)
    else if (r < 0.8) s = s.slice(0, pos) + s.slice(pos + 1 + int(4))
    else s = s.slice(0, pos) + pick(['', '  ', '    ', '> ', '- ', '1. ']) + s.slice(pos).replace(/\n/, '\n' + pick(['', '  ', '    ', '> ']))
  }
  return s
}

function generateInput() {
  const parts = []
  const k = 1 + int(3)
  for (let i = 0; i < k; i++) parts.push(windowOf(pick(docs)).join('\n'))
  let s = parts.join(pick(['\n', '\n\n', '\n\n\n']))
  if (rand() < 0.75) s = mutate(s)
  if (rand() < 0.15) s = '---\n' + pick(['title: T', 'title: "Q"\ndescription: d', 'tags: [a, b]', 'n: 1\nb: true\nx: ~',
    'a: &x {k: v}\nb: *x', 'date: 2024-01-15', 'empty:', 'list:\n  - 1\n  - two', 'bad: [']) + '\n---\n' + s
  if (rand() < 0.5 && !s.endsWith('\n')) s += '\n'
  return s
}

const ALPHABET = ['*', '_', '`', '~', '[', ']', '(', ')', '{', '}', '<', '>', '/', ':', '#', '|', '\\', '&', ';', '!', '-',
  '+', '.', '=', '"', "'", '$', 'a', 'b', 'x', '1', ' ', ' ', ' ', '\t', '\n', '\n', '\r', '\u00a0', 'é', '😀', '\u0085']
function charsInput() {
  let s = ''
  const n = int(120)
  for (let i = 0; i < n; i++) s += rand() < 0.15 ? pick(SNIPPETS) : pick(ALPHABET)
  return s
}

const YAML_BITS = ['title: T', 'a: 1', 'b: -0', 'c: 1.5e3', 'd: 0x1F', 'e: ~', 'f: null', 'g: true', 'h: yes', 'i: 2024-01-15',
  'j: "q\\"x"', "k: 'it''s'", 'l: [a, b, {c: d}]', 'm: {x: 1, y: [2]}', 'n:\n  - 1\n  - x: y', 'o: |\n  line\n  two',
  'p: >-\n  folded\n  text', 'q: &a [1]', 'r: *a', 's: !!str 12', 't: !!int "7"', 'u:', '"v w": 1', '? x\n: y', '- item',
  '# comment', '%YAML 1.2', '---', '...', 'w: @x', 'x: `y`', 'y: a: b', 'z:\tz', '  indented: 1', 'title: T', 'k2: [', 'k3: }',
  'tab:\n\t- x', 'n2: 0o17', 'n3: +1', 'n4: .5', 'n5: 1.', 'key: "multi\n  line"', 'emoji: 😀', 'nul: "\\0"']
function yamlBlock() {
  const n = 1 + int(5)
  const lines = []
  for (let i = 0; i < n; i++) lines.push(rand() < 0.2 ? mutate(pick(YAML_BITS)) : pick(YAML_BITS))
  return lines.join('\n')
}
function yamlInput() {
  const body = windowOf(pick(docs)).join('\n')
  if (rand() < 0.6) return '---\n' + yamlBlock() + '\n---\n' + body + '\n'
  return pick(['::card', '::alert{type="info"}', ':::nested']) + '\n' + pick(['---', '```yaml [props]']) + '\n' + yamlBlock()
    + '\n' + pick(['---', '```']) + '\n' + body + '\n' + pick(['::', ':::', '']) + '\n'
}

// ---- record ------------------------------------------------------------------------------------------------------------
const seen = new Set()
const vectors = []
let errors = 0
// Inputs at markdown-it's nesting limit (maxNesting 100) and other pathological shapes, recorded after the random ones.
const STRESS = [
  '>'.repeat(150) + ' deep quote\n',
  Array.from({ length: 120 }, (_, i) => ' '.repeat(2 * i) + '- item ' + i).join('\n') + '\n',
  Array.from({ length: 110 }, (_, i) => ':'.repeat(i + 2) + 'c' + i).join('\n') + '\n' + Array.from({ length: 110 }, (_, i) => ':'.repeat(111 - i)).join('\n') + '\n',
  Array.from({ length: 105 }, () => '::card').join('\n') + '\ntext\n' + Array.from({ length: 105 }, () => '::').join('\n') + '\n',
  '*'.repeat(500) + 'x' + '*'.repeat(500) + '\n',
  '['.repeat(300) + 'x' + ']'.repeat(300) + '(y)\n',
  '**a _b ~~c `d` e~~ f_ g**'.repeat(200) + '\n',
  '<div>'.repeat(200) + 'x' + '</div>'.repeat(200) + '\n',
  'a <span>'.repeat(150) + 'b' + '</span>'.repeat(150) + '\n',
  ':span[' .repeat(120) + 'x' + ']'.repeat(120) + '\n',
  '[a]{.b}'.repeat(300) + '\n',
  ('word '.repeat(2000)).trim() + '\n',
  '| a | b |\n|---|---|\n' + '| 1 | 2 |\n'.repeat(500),
  '```\n' + 'code\n'.repeat(2000) + '```\n',
  '---\n' + Array.from({ length: 300 }, (_, i) => 'k' + i + ': v' + i).join('\n') + '\n---\n\nbody\n',
  '---\na: ' + '['.repeat(120) + ']'.repeat(120) + '\n---\n\nbody\n',
  '\t'.repeat(50) + '- tabbed\n',
  '\r\n'.repeat(5) + '# CRLF\r\n\r\ntext\r\n',
  '\uFEFF# BOM heading\n',
  '',
  '\n\n\n',
]

const TOTAL = COUNT + CHARS + YAMLS
for (const input of STRESS) {
  seen.add(input)
}
while (vectors.length < TOTAL) {
  const input = vectors.length < COUNT ? generateInput() : vectors.length < COUNT + CHARS ? charsInput() : yamlInput()
  if (seen.has(input) || input.length > 4000) continue
  seen.add(input)
  vectors.push(await record(input))
}

async function record(input) {
  const v = { input }
  try {
    const doc = await generate.documentFromContent(DOC_ID, input, { compress: true, collectionType: 'page' })
    const t = legacy.unbindComarkTree(legacy.comarkTreeFromLegacyDocument(doc))
    v.tree = { nodes: encode(t.nodes.map(dropPositions)) }
    v.frontmatter = encode(t.frontmatter || {})
  } catch (e) {
    v.tree = null
    v.frontmatter = null
  }
  try {
    v.text = await generate.contentFromMarkdownDocument(await generate.documentFromContent(DOC_ID, input, { compress: true, collectionType: 'page' }))
  } catch (e) {
    v.text = { error: firstLine(e) }
    errors++
  }
  return v
}
for (const input of STRESS) vectors.push(await record(input))
fs.writeFileSync(outFile, '{"description": ' + JSON.stringify(
  `Nuxt Studio 1.8.0 text path (comark 0.7.0 parse with Studio's options, unbindComarkTree, renderMarkdown) for ${vectors.length} documents: ${STRESS.length} pathological ones and ${TOTAL} seeded random ones (${COUNT} built from the oracle corpus, ${CHARS} from Markdown punctuation, ${YAMLS} with random YAML front matter or props); ${errors} make Studio throw. Re-record with \`node record-text-vectors.mjs\` in src/test/node/studio-oracle.`,
) + ',\n "vectors": [\n' + vectors.map((v) => JSON.stringify(v)).join(',\n') + '\n]}\n')
console.log(`wrote ${vectors.length} text vectors (${errors} errors) to ${path.relative(repo, outFile)}`)
