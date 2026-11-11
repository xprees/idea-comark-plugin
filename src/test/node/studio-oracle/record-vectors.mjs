#!/usr/bin/env node
// Records test vectors for the Kotlin port of Studio's save pipeline, next to oracle.json:
//
//   npm ci && node record-vectors.mjs
//
// - yaml-vectors.json: js-yaml 5.5.0 `dump(value, { indent: 2, lineWidth: -1 })` (`dump`) and comark 0.7.0's
//   `stringifyYaml(value)` (`comark`, the dump plus its ':key' unquote pass) for several hundred values: numeric-looking
//   strings, YAML 1.1 booleans and nulls, dates, indicators, multi-line strings, unicode, numbers and nested or empty
//   collections, each at the root, as a mapping value, as a sequence item, nested and (strings) as a key.
// - render-vectors.json: comark 0.7.0 `renderMarkdown(tree, { blockAttributesStyle: 'frontmatter' })` for hand-made trees
//   with attribute values the parsed corpus does not produce (numbers, booleans, null, objects, bindings, ...).
//
// Values JSON cannot hold are encoded like in oracle.json: {"$type": "date" | "number" | "undefined", "value": ...}.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.resolve(here, '../../testData/studio')
const { dump } = await import('js-yaml')
const { stringifyYaml } = await import(pathToFileURL(path.join(here, 'node_modules/comark/dist/internal/yaml.js')).href)
const { renderMarkdown } = await import('comark/render')

function encode(v) {
  if (v instanceof Date) return { $type: 'date', value: Number.isNaN(v.getTime()) ? 'Invalid Date' : v.toISOString() }
  if (typeof v === 'number' && !Number.isFinite(v)) return { $type: 'number', value: String(v) }
  if (typeof v === 'number' && Object.is(v, -0)) return { $type: 'number', value: '-0' }
  if (v === undefined) return { $type: 'undefined' }
  if (Array.isArray(v)) return v.map(encode)
  if (v && typeof v === 'object') {
    const o = {}
    for (const [k, x] of Object.entries(v)) o[k] = encode(x)
    return o
  }
  return v
}

// ---- YAML ------------------------------------------------------------------------------------------------------------
const strings = [
  // numeric-looking
  '0', '1', '-1', '+1', '01', '08', '0777', '0o17', '0x1F', '0xG', '0b101', '0b', '0b_', '0x_', '0_', '1_000', '1,000',
  '1:30', '1:30:45', '190:20:30.15', '1:60', '1.5', '-1.5e-3', '1e5', '1E5', '1e+5', '1.e+5', '1.0e5', '1e400', '.5', '5.',
  '.', '-', '+', '-.', '._', '.inf', '-.Inf', '+.INF', '.NaN', '.nan', 'NaN', 'Infinity', '-Infinity', '0.0.1', '1.2.3',
  '123456789012345678901234567890', '9'.repeat(400), '1_', '_1', '1__2', '0.', '-0', '+0', '00', '1e', 'e5', '0x', '12abc',
  // booleans and nulls
  'yes', 'Yes', 'YES', 'yEs', 'no', 'No', 'NO', 'on', 'On', 'ON', 'off', 'Off', 'OFF', 'y', 'Y', 'n', 'N', 'true', 'True',
  'TRUE', 'tRue', 'false', 'False', 'FALSE', 'yes please', 'nope', 'onward', '', '~', 'null', 'Null', 'NULL', 'nULL', 'nil',
  'None', 'none',
  // dates and times
  '2020-01-01', '2020-1-1', '2020-02-29', '2021-02-29', '2020-02-30', '2020-13-01', '2020-00-10', '0000-01-01',
  '2020-01-01T10:00:00Z', '2020-01-01 10:00:00', '2020-01-01t10:00:00.5+02:00', '2020-01-01T25:00:00', '2020-01-01T10:60:00',
  '2020-01-01T10:00:00 +5', '2020-01-01T10:00:00+24:00', '2020-01-01T10:00', '2020-01-01x', '20200101', '12:30', '10:00:00',
  // indicators and punctuation
  '#', '# comment', 'a #b', 'a#b', 'a# b', 'a: b', 'a:b', 'a:', ':a', ':', '::', ': a', '- a', '-a', '--', '? a', '?a', '?',
  '[a]', ']', '{a}', '}', 'a,b', ', a', '*a', '&a', '!a', '!', '|', '| a', '>', '> a', "'", '"', "'a'", '"a"', '%', '%a', '@a',
  '`a`', "a'b", 'a"b', 'it\'s "q"', '---', '--- a', '---a', '...', '... a', 'a ', ' a', '  ', ' ', '\t', 'a\tb', '=', '==',
  '<<', '<', '<a>', 'a\\b', '\\', 'http://x.y/z?a=b#c', 'mailto:a@b.c', '~a', 'a~', 'a, b', 'a ,b', 'a [b]', '{{ x }}', '$ref',
  'C:\\path', 'key: value: more', 'a  b', 'a - b', '-1 apples', 'v1.0', '1.0.0-beta', '#hash', 'trailing:', 'x:y:z',
  // multi-line
  'a\nb', 'a\n', 'a\n\n', '\n', '\na', '\n\na', ' a\nb', 'a\n b', 'a \nb', 'a\t\nb', 'line1\nline2\n', 'a\r\nb', 'a\rb', '\n\n',
  'a\n\n\nb', '  indented\nnot', 'a\n  b\n    c', '- a\n- b', '# x\ny', 'a\n---\nb', 'a\n...\n', '---\na', 'a\u2028b', ' \n',
  'a\n ', 'trailing space \nx', 'tab\tinside\nx', 'a\n\n',
  // unicode and control characters
  'café', '日本語', '😀', 'a😀b', 'a\u0085b', '\u00a0', 'a\u00a0b', '\ufeffx', 'x\ufeff', '\u007f', '\u0000', '\u0007', '\u0008',
  '\u000b', '\u000c', '\u001b', '\u001f', '\ud800', 'x\udc00', '\u2029', '\ufffe', '\uffff', '\ue000', '\u2000', 'a\u3000',
  'Ünïcödé key', 'ñ: ñ', '\u200b', 'emoji 🎉 party', '\ud83d\ude00\n\ud83d\ude00',
  // long
  'x'.repeat(200), ('word '.repeat(60)).trim(), 'a'.repeat(1100),
]
const numbers = [0, -0, 1, -1, 1.5, -2.25, 1e21, 1e-7, 1e-6, 123456789012345680000, 0.1, 0.1 + 0.2, NaN, Infinity, -Infinity, 1e300,
  5e-324, 2 ** 53, 2 ** 53 + 2, 100, 1e20, 12345.678, -0.5, 3e-10, 1.7976931348623157e308]
const others = [true, false, null, new Date('2020-01-01T00:00:00Z'), new Date('1999-12-31T23:59:59.999Z'),
  new Date('2024-02-29T12:34:56.789Z')]
const collections = [
  [], {}, [[]], [{}], { a: [] }, { a: {} }, [1, [2, 3]], [[1, 2], [3]], [{ a: 1, b: 2 }], { a: { b: { c: 1 } } },
  { a: [{ b: [{ c: 'd' }] }] }, [[[]]], [null, undefined, 1], { u: undefined, v: 1 }, { 1: 'one', b: 'bee', 0: 'zero' },
  { 10: 'a', 9: 'b', x: 'c', '-1': 'd', '01': 'e', 4294967295: 'f', 4294967294: 'g' }, { y: 1, n: 2, '': 3, '1': 4, ':key': 5 },
  { ':key with space': 1, ':a': { ':b': 2 }, "'": 3, '"': 4, ':\'q\'': 5, 'a: b': 6, '#': 7, '-': 8, '? x': 9 },
  { 'key\nwith newline': 1 }, { ['k'.repeat(1100)]: 1 }, { list: ['a', 'b'], nested: { list: [{ x: 1 }, { y: [] }] } },
  { multi: 'line1\nline2\n', keep: 'a\n\n', strip: 'a', last: 'end\n\n' }, ['a\n\n'], { a: '\n' }, { title: 'Hello', draft: true,
    tags: ['x', 'y'], date: new Date('2020-01-01T00:00:00Z'), n: 3, nothing: null },
  [{ a: 'x\ny' }, ['p\nq']], { s: ' leading', t: 'trailing ', both: ' both ' }, { a: [[], {}, [[]], [{}]] },
]

const values = []
for (const s of strings) {
  values.push(s, { k: s }, [s], { a: { b: [s] } }, { [s]: 1 }, [[s]], [{ k: s }])
}
for (const n of [...numbers, ...others]) values.push(n, { k: n }, [n])
values.push(...collections, undefined, { a: undefined })
for (const c of collections) values.push({ k: c }, [c])

const yamlVectors = []
for (const value of values) {
  yamlVectors.push({ value: encode(value), dump: dump(value, { indent: 2, lineWidth: -1 }), comark: stringifyYaml(value) })
}

// ---- renderMarkdown --------------------------------------------------------------------------------------------------
const trees = [
  { nodes: [['p', {}, 'Number ', ['badge', { n: 1, x: 2.5, big: 1e21 }, 'b']]] },
  { nodes: [['p', {}, 'Bool ', ['badge', { ok: true, no: false, ':on': 'true', ':off': 'false' }, 'b']]] },
  { nodes: [['p', {}, 'Null ', ['badge', { nothing: null }, 'b']]] },
  { nodes: [['badge', { n: 1, b: true, s: 'x' }]] },
  { nodes: [['card', { a: '1', b: '2', c: '3', d: '4' }, ['p', {}, 'body']]] },
  { nodes: [['card', { a: 'true', b: 'false', ':c': '{"x":[1,2]}', ':d': 'not json', ':e': '12', ':f': 'null' }, ['p', {}, 'x']]] },
  { nodes: [['card', { obj: { a: 1, b: [1, 'two', null] }, list: [] }, ['p', {}, 'x']]] },
  { nodes: [['card', { 2: 'two', 1: 'one', z: 'zed', y: 'why' }, ['p', {}, 'x']]] },
  { nodes: [['card', { class: ['a', 'b'], id: 7 }, 'text']] },
  { nodes: [['card', { class: 'x  y', title: 'say "hi"', alt: 'it\'s "x"' }, 'text']] },
  { nodes: [['card', { 'date': new Date('2020-01-01T00:00:00Z') }, ['p', {}, 'x']]] },
  { nodes: [['ol', { start: '3' }, ['li', {}, 'a'], ['li', {}, 'b']], ['ol', { start: 0 }, ['li', {}, 'c']],
    ['ol', { start: 2.5 }, ['li', {}, 'd']], ['ol', { start: 'x' }, ['li', {}, 'e']], ['ol', { start: true }, ['li', {}, 'f']]] },
  { nodes: [['ul', { class: 'contains-task-list' }, ['li', { class: 'task-list-item' }, ['input', { checked: true }], ' done'],
    ['li', { class: 'task-list-item' }, ['input', { ':checked': 'false' }], ' string false'],
    ['li', { class: 'task-list-item' }, ['input', {}], ' open']]] },
  { nodes: [['ul', {}, ['li', {}, ['p', {}, 'para'], ['pre', { language: 'js' }, ['code', {}, 'x']], [null, {}, ' c ']]]] },
  { nodes: [['pre', { language: 'ts', filename: 'a]b.ts', highlights: [3, 1, 2, 5], meta: 'meta=1', code: 'let a\n\n' }]] },
  { nodes: [['pre', { highlights: [], class: 'shiki shiki-themes . user' }, ['code', { class: 'language-py' }, '```\nx']]] },
  { nodes: [['p', {}, 'inline ', ['math', {}, 'x^2']], ['math', { class: 'block' }, 'y']] },
  { nodes: [['mermaid', { content: 'graph TD\n```\n' }], ['mermaid', { content: 'a', id: 'm' }]] },
  { nodes: [['tabs', {}, ['template', { name: 'default' }, ['p', {}, 'only default']]], ['tabs', {}, ['template', { name: 'one', x: '1' }, ['p', {}, 'a']], ['template', { name: 'default' }, ['p', {}, 'b']]]] },
  { nodes: [['div', { $: { html: 1, block: 1 }, class: 'x', hidden: true, off: false, n: 3, ':b': 'true', ':o': '{"a":1}', 'a&b': 'x', title: 'a & b #1; <c>' }, ['p', {}, 'raw *text*']]] },
  { nodes: [['p', {}, 'x ', ['span', { $: { html: 1, block: 0 } }, 'inline'], ' y ', ['br', { $: { html: 1, block: 0 } }]]] },
  { nodes: [['p', {}, ['img', { src: '/a.png', alt: 'A', title: 'T', width: 10 }], ' ', ['img', { src: '/b.png', alt: null }]]] },
  { nodes: [['p', {}, ['a', { href: 5 }, '5'], ' ', ['a', { href: 'https://x.y' }, 'https://x.y'], ' ', ['a', { href: '/z', target: '_blank' }, ['span', {}, 'in']]]] },
  { nodes: [['blockquote', { as: 'note' }, ['p', {}, 'quoted']], ['blockquote', { class: 'c' }, 'plain text']] },
  { nodes: [['table', {}, ['thead', {}, ['tr', {}, ['th', { style: 'text-align: center' }, 'Emoji'], ['th', { style: 'TEXT-ALIGN:RIGHT' }, 'B']]],
    ['tbody', {}, ['tr', {}, ['td', {}, '😀😀'], ['td', {}, 'a|b\nc']], ['tr', {}, ['td', {}, 'x']]]]] },
  { nodes: [['table', { class: 't' }, ['tr', {}, ['td', {}, 'no header'], ['td', {}, '2']]]] },
  { nodes: [['p', {}, 'a', ['hr', {}], 'b'], ['hr', {}], ['h2', { id: 'x', class: 'y' }, 'Title'], ['code', { lang: 'js' }, 'a`b']] },
  { nodes: [['p', {}, ':colon and {.attr} and <b> and &amp; and & and a_b_c and _x_']] },
  { frontmatter: { title: 'T', n: 1.5, when: new Date('2020-01-01T00:00:00Z'), tags: ['a', 'b'], nested: { ok: true, '1': 'x' }, ':key': 'v' }, nodes: [['p', {}, 'x']] },
  { frontmatter: { only: 'frontmatter' }, nodes: [] },
  { frontmatter: { text: 'multi\nline\n\n' }, nodes: [['p', {}, 'x']] },
  { nodes: [['outer', {}, ['inner', { a: '1', b: '2', c: '3', d: '4' }, ['p', {}, 'deep']]]] },
  { nodes: [['ul', {}, ['li', {}, ['card', { x: 1 }, ['p', {}, 'in list']]]]] },
  { nodes: [['comment', {}, 'tag named comment'], ['html', {}, 'tag named html'], ['mdc', {}, 'tag named mdc']] },
]
const renderVectors = []
for (const tree of trees) {
  const doc = { frontmatter: tree.frontmatter || {}, meta: {}, nodes: tree.nodes }
  renderVectors.push({ tree: encode(doc), markdown: await renderMarkdown(doc, { blockAttributesStyle: 'frontmatter' }) })
}

fs.writeFileSync(path.join(outDir, 'yaml-vectors.json'), JSON.stringify({
  description: 'js-yaml 5.5.0 dump(value, {indent: 2, lineWidth: -1}) and comark 0.7.0 stringifyYaml(value). Re-record with `node record-vectors.mjs` in src/test/node/studio-oracle.',
  vectors: yamlVectors,
}, null, 1) + '\n')
fs.writeFileSync(path.join(outDir, 'render-vectors.json'), JSON.stringify({
  description: "comark 0.7.0 renderMarkdown(tree, {blockAttributesStyle: 'frontmatter'}) for hand-made trees. Re-record with `node record-vectors.mjs` in src/test/node/studio-oracle.",
  vectors: renderVectors,
}, null, 1) + '\n')
console.log(`wrote ${yamlVectors.length} YAML vectors and ${renderVectors.length} render vectors`)
