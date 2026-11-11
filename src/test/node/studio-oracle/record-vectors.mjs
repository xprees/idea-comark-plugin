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
// - render-random-vectors.json: the same for trees from a seeded random generator (fixed seed, so re-recording gives the
//   same trees), one vector per line.
//
// Values are encoded like in oracle.json (lib/encode.mjs): {"$type": "date" | "number" | "undefined", "value": ...} for
// values JSON cannot hold, and {"$type": "shared" | "ref", "id": ...} for an array or object reached more than once.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.resolve(here, '../../testData/studio')
const { dump } = await import('js-yaml')
const { stringifyYaml } = await import(pathToFileURL(path.join(here, 'node_modules/comark/dist/internal/yaml.js')).href)
const { renderMarkdown } = await import('comark/render')

const { encode } = await import('./lib/encode.mjs')

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

// Shared objects and arrays (what YAML aliases load as): js-yaml writes &ref_N at the first occurrence, numbered in the
// order the repeats are met, and *ref_N at the others. Cycles too.
const shared = (() => {
  const o = { a: 1 }
  const l = ['x', 'y']
  const e = []
  const eo = {}
  const deep = { inner: { list: l } }
  const cyc = { name: 'self' }
  cyc.self = cyc
  const cycList = ['a']
  cycList.push(cycList)
  const cycDeep = { k: [] }
  cycDeep.k.push({ back: cycDeep })
  return [
    { base: o, derived: { '<<': o, b: 2 } }, { first: l, second: l, third: l }, [o, o, l, l], [l, o, l, o],
    { e, again: e, eo, eo2: eo }, [e, e, eo, eo], { deep, list: l }, { list: l, deep }, { a: [o], b: [o] },
    { anchors: { base: o, derived: { '<<': o, b: 2 } }, other: { x: l, y: { z: l } } }, [[l, l], { m: l }],
    { k: { ':key': o }, ':other': o }, cyc, cycList, cycDeep, { wrap: cyc }, [cycList, cycList],
    { seq: [o, l, e, eo], again: [eo, e, l, o] },
  ]
})()

const values = []
for (const s of strings) {
  values.push(s, { k: s }, [s], { a: { b: [s] } }, { [s]: 1 }, [[s]], [{ k: s }])
}
for (const n of [...numbers, ...others]) values.push(n, { k: n }, [n])
values.push(...collections, undefined, { a: undefined })
for (const c of collections) values.push({ k: c }, [c])
values.push(...shared)

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
// Renderer branches the parsed corpus does not reach, and the tree shapes of Studio's database path.
const sharedProps = { k: 1 }
const sharedList = [1, 2]
trees.push(
  // database path: propsMDCToComark (legacy.js) joins arrays of primitives with spaces, so highlights arrive as a string
  { frontmatter: { title: 'T' }, nodes: [['h1', { id: 't' }, 'T'], ['pre', { filename: 'a.js', highlights: '1 2', language: 'js', meta: undefined, style: undefined }, ['code', { __ignoreMap: '' }, 'const a = 1\nconst b = 2']],
    ['card', {}, ['pre', { highlights: '3', language: 'ts' }, ['code', { __ignoreMap: '' }, 'x']]]] },
  { nodes: [['pre', { highlights: '10', language: 'ts' }, ['code', {}, 'a']], ['pre', { highlights: '2 4 5 10 12', language: 'vue' }, ['code', {}, 'b']],
    ['pre', { highlights: 'b a 1', language: 'x' }, ['code', {}, 'c']], ['pre', { highlights: ' ', language: 'y' }, ['code', {}, 'd']]] },
  // highlights arrays with values that are not numbers
  { nodes: [['pre', { highlights: [3, null, 1] }, ['code', {}, 'a']], ['pre', { highlights: [true, 2] }, ['code', {}, 'b']],
    ['pre', { highlights: ['2', '3', 1] }, ['code', {}, 'c']], ['pre', { highlights: ['1', '11', '12'] }, ['code', {}, 'd']],
    ['pre', { highlights: [2, undefined, 1, 'x', 3] }, ['code', {}, 'e']], ['pre', { highlights: [NaN, 1, 2] }, ['code', {}, 'f']],
    ['pre', { highlights: [1, 1, 2, 2, 4] }, ['code', {}, 'g']], ['pre', { highlights: [-1, 0, 1.5, 2.5] }, ['code', {}, 'h']],
    ['pre', { highlights: [[1], [2], {}] }, ['code', {}, 'i']], ['pre', { highlights: [false, 0, 1] }, ['code', {}, 'j']]] },
  // comments without text inside code, pre and math (textContent ignores them)
  { nodes: [['p', {}, ['code', {}, 'a', [null, {}], 'b']], ['pre', { language: 'js' }, ['code', {}, 'x', [null, {}], 'y']],
    ['p', {}, ['math', {}, 'x', [null, {}, 'c']]], [null, {}]] },
  // escaping in text: processing instructions, closing tags, '<' before a non-tag character, ':' at a line start
  { nodes: [['p', {}, 'x <?php y> z'], ['p', {}, 'x </b> z'], ['p', {}, 'a < b and a <1 and <-'], ['p', {}, 'a\n:b'], ['p', {}, ':start'],
    ['p', {}, 'a <!-- c --> b <![CDATA[x]]> <!x>']] },
  // nested lists with attributes, li className arrays, li attributes
  { nodes: [['ul', {}, ['li', {}, 'x', ['ul', { class: 'inner' }, ['li', {}, 'y']]], ['li', {}, 'z', ['ol', { id: 'o' }, ['li', {}, 'w']]]]] },
  { nodes: [['ol', { start: 4 }, ['li', {}, 'a', ['ol', {}, ['li', {}, 'b', ['ul', { class: 'deep' }, ['li', {}, 'c']]]]]]] },
  { nodes: [['ul', {}, ['li', { className: ['task-list-item', 'x'] }, ['input', { checked: true }], ' done'], ['li', { className: ['a'] }, 'b'],
    ['li', { id: 'i', class: 'c' }, 'with attrs'], ['li', { className: 'task-list-item' }, ['input', {}], ' open']]] },
  // blockquote with attributes and block children
  { nodes: [['blockquote', { class: 'q' }, ['p', {}, 'one'], ['p', {}, 'two']], ['blockquote', { id: 'b' }, ['ul', {}, ['li', {}, 'x']]],
    ['blockquote', {}, ['p', {}, 'plain'], ['pre', {}, ['code', {}, 'c']]]] },
  // tables: header from a bare tr of th cells, string cells, non-row children, an empty table, a table with attributes
  { nodes: [['table', {}, ['tr', {}, ['th', {}, 'H1'], ['th', {}, 'H2']], ['tr', {}, ['td', {}, 'a'], ['td', {}, 'b']]]] },
  { nodes: [['table', {}, ['thead', {}, 'text', ['tr', {}, 'cell text', ['th', {}, 'H']], ['p', {}, 'not a row']], ['tbody', {}, ['tr', {}, ['td', {}, 'x']], 'loose']]] },
  { nodes: [['table', {}]], frontmatter: {} },
  { nodes: [['table', {}, 'just text', ['caption', {}, 'cap']]] },
  { nodes: [['table', { class: 't', id: 'x' }, ['thead', {}, ['tr', {}, ['th', {}, 'A']]], ['tbody', {}, ['tr', {}, ['td', {}, '1']]]]] },
  { nodes: [['card', {}, ['table', {}, ['thead', {}, ['tr', {}, ['th', {}, 'A']]], ['tbody', {}, ['tr', {}, ['td', {}, ['strong', {}, 'b'], ' c']]]]]] },
  // templates without a parent template group, and at the root
  { nodes: [['template', { name: 'default' }, ['p', {}, 'root default']], ['template', { name: 'other', x: 'y' }, 'text']] },
  // html elements: inline tags with only text, separators in html context, object and array attribute values
  { nodes: [['p', {}, 'a ', ['b', { $: { html: 1, block: 0 } }, 'bold'], ' ', ['i', { $: { html: 1, block: 0 }, class: 'c' }, 'it']]] },
  { nodes: [['div', { $: { html: 1, block: 1 } }, ['p', {}, 'one'], ['p', {}, 'two'], ['span', { $: { html: 1, block: 0 } }, 'x']]] },
  { nodes: [['div', { $: { html: 1, block: 1 }, data: { a: 1 }, list: ['x', 'y'], ':bound': 'v' }, 'text']] },
  { nodes: [['section', { $: { html: 1, block: 1 } }, ['div', { $: { html: 1, block: 1 } }, 'inner'], 'tail']] },
  // fences: tilde runs, backtick runs, mermaid without content, pre classes starting with shj
  { nodes: [['pre', { language: 'md' }, ['code', {}, '~~~\nx\n~~~~']], ['pre', {}, ['code', {}, '```\n````\n']], ['pre', { class: 'shj-lang-js user' }, ['code', {}, 'y']],
    ['mermaid', {}], ['mermaid', { content: '~~~' }]] },
  // YAML props blocks whose content needs a longer fence, shared props objects (YAML aliases)
  { nodes: [['card', { a: '---', b: 'x\n---\ny', c: 1, d: 2 }, ['p', {}, 'body']]] },
  { nodes: [['card', { obj: { text: 'a\n---\nb' } }, ['p', {}, 'body']]] },
  { nodes: [['card', { a: sharedProps, b: sharedProps, items: sharedList, more: sharedList }, ['p', {}, 'body']]] },
  { frontmatter: { defaults: sharedProps, page: { '<<': sharedProps }, list: [sharedList, sharedList] }, nodes: [['p', {}, 'x']] },
  // a tag named like a JavaScript prototype key, spans and inline components
  { nodes: [['p', {}, ['toString', {}, 'x'], ' ', ['constructor', { a: 'b' }]], ['hasOwnProperty', {}, ['p', {}, 'y']]] },
  { nodes: [['a', { href: '/x' }, ['span', {}, 'in link']], ['p', {}, ['span', { class: 'c' }, 'span'], ' ', ['badge', { n: { o: 1 } }, 'obj']]] },
)

const renderVectors = []
for (const tree of trees) {
  const doc = { frontmatter: tree.frontmatter || {}, meta: {}, nodes: tree.nodes }
  const encoded = { frontmatter: encode(doc.frontmatter), meta: encode(doc.meta), nodes: encode(doc.nodes) }
  renderVectors.push({ tree: encoded, markdown: await renderMarkdown(doc, { blockAttributesStyle: 'frontmatter' }) })
}

// ---- seeded random trees ---------------------------------------------------------------------------------------------
let seed = 20261111
const rnd = () => {
  seed = (Math.imul(seed, 1103515245) + 12345) >>> 0
  seed ^= seed >>> 13
  seed = Math.imul(seed, 0x5bd1e995) >>> 0
  seed ^= seed >>> 15
  return (seed >>> 0) / 4294967296
}
const pick = (a) => a[Math.floor(rnd() * a.length)]
const rTexts = ['a', 'b c', ' x ', '*', '_a_', 'a_b', '<b>', '<a', 'x>', '<?x', '</b>', '&amp;', '& b', '&#123;', ':x', 'a:b', '{.c}', '{x',
  '#', '# h', '1. x', '- x', '+ ', '>', '=', '===', ':', '\n', 'a\nb', 'a\n# b', 'a\n- b', 'a\n:b', '`', '``', '|', 'a|b', '[', ']', '\\',
  '~', '~~~', '```', '$', '\t', '  ', 'x  ', ' ', 'é', '😀', '---', '***', 'a\n\nb', '1)', '9999999999.']
const rTags = ['p', 'p', 'p', 'strong', 'em', 'del', 'code', 'a', 'span', 'ul', 'ul', 'ol', 'ol', 'li', 'li', 'li', 'blockquote', 'pre', 'h2',
  'hr', 'br', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'template', 'math', 'mermaid', 'alert', 'card', 'card', 'badge', 'div',
  'html', 'mdc', 'comment', 'toString', 'input']
const rValues = ['x', '', 'a b', 'a"b', "a'b", 'a"\'b', 'true', 'false', 1, 0, 2.5, true, false, null, { k: 'v' }, ['a', 'b'], '{"a":1}', '[1]',
  '3', 'default', 'task-list-item', 'language-js', 'text-align:center', 'shj-x', 'a\n---\nb']
const rKeys = ['class', 'id', 'href', 'src', 'alt', 'title', ':bind', ':checked', 'checked', 'name', 'start', 'as', 'language', 'filename',
  'meta', 'highlights', 'code', 'style', 'content', 'k', '1', 'className', 'onclick', 'data-x']
const rHighlights = [[1, 2, 3, 5], [3, 1], [], ['2', '3'], '1 2', '10', '2 4 5', [null, 1], [true, 2], '']
function rAttrs() {
  const o = {}
  const n = rnd() < 0.6 ? 0 : Math.floor(rnd() * 5)
  for (let i = 0; i < n; i++) {
    const k = pick(rKeys)
    o[k] = structuredClone(k === 'highlights' ? pick(rHighlights) : pick(rValues))
  }
  if (rnd() < 0.12) o.$ = { html: 1, block: pick([0, 1]) }
  return o
}
function rNode(depth) {
  if (depth > 5 || rnd() < 0.3) return pick(rTexts)
  if (rnd() < 0.04) return rnd() < 0.5 ? [null, {}, pick(rTexts)] : [null, {}]
  const tag = pick(rTags)
  const kids = []
  const n = Math.floor(rnd() * 5)
  if (tag === 'li' && rnd() < 0.3) kids.push(['input', { type: 'checkbox', checked: rnd() < 0.5 }])
  for (let i = 0; i < n; i++) kids.push(rNode(depth + 1))
  const at = rAttrs()
  if (tag === 'li' && kids[0]?.[0] === 'input') at.class = 'task-list-item'
  return [tag, at, ...kids]
}
const RANDOM_TREES = 1500
const randomVectors = []
let randomThrew = 0
while (randomVectors.length < RANDOM_TREES) {
  const nodes = []
  const n = 1 + Math.floor(rnd() * 3)
  for (let j = 0; j < n; j++) nodes.push(rNode(0))
  const frontmatter = rnd() < 0.1 ? { title: pick(rTexts), n: 1, l: ['a', 'b'] } : {}
  const doc = { frontmatter, meta: {}, nodes }
  const encoded = { frontmatter: encode(frontmatter), meta: {}, nodes: encode(nodes) }
  let markdown
  try {
    markdown = await renderMarkdown(doc, { blockAttributesStyle: 'frontmatter' })
  } catch {
    randomThrew++ // comark throws for some shapes (e.g. a task-list li without an input); the port need not
    continue
  }
  randomVectors.push({ tree: encoded, markdown })
}

fs.writeFileSync(path.join(outDir, 'yaml-vectors.json'), JSON.stringify({
  description: 'js-yaml 5.5.0 dump(value, {indent: 2, lineWidth: -1}) and comark 0.7.0 stringifyYaml(value). Re-record with `node record-vectors.mjs` in src/test/node/studio-oracle.',
  vectors: yamlVectors,
}, null, 1) + '\n')
fs.writeFileSync(path.join(outDir, 'render-vectors.json'), JSON.stringify({
  description: "comark 0.7.0 renderMarkdown(tree, {blockAttributesStyle: 'frontmatter'}) for hand-made trees. Re-record with `node record-vectors.mjs` in src/test/node/studio-oracle.",
  vectors: renderVectors,
}, null, 1) + '\n')
fs.writeFileSync(path.join(outDir, 'render-random-vectors.json'), '{"description": ' + JSON.stringify(
  `comark 0.7.0 renderMarkdown(tree, {blockAttributesStyle: 'frontmatter'}) for ${RANDOM_TREES} seeded random trees (${randomThrew} generated trees that made comark throw are left out). Re-record with \`node record-vectors.mjs\` in src/test/node/studio-oracle.`,
) + ',\n "vectors": [\n' + randomVectors.map((v) => JSON.stringify(v)).join(',\n') + '\n]}\n')
console.log(`wrote ${yamlVectors.length} YAML vectors, ${renderVectors.length} render vectors and ${randomVectors.length} random render vectors`)
