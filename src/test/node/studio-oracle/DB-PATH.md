# Nuxt Studio's database path D on text-path-normal documents

This document lists every transformation Nuxt Studio 1.8.0's **database path D** applies to a document that is already
**text-path output** (y = T(y), what the IDE's "Nuxt Studio" style writes), relative to what the text path T would write.
It is the target for emulating D in Kotlin: only D(y) for such y is needed, not D on arbitrary Markdown.

The rule ids (`FM-…`, `B-…`) are the categories `lib/db-diff.mjs` assigns and `db-vectors.json` records per vector
(`rules`); the counts quoted are from that file (see [Statistics](#statistics)). `record-db-vectors.mjs` records the data;
`StudioDbVectorsTest` checks it and prints the statistics.

File references:

| short name | file (all under `node_modules/` of this directory unless noted) |
|------------|------------------------------------------------------------------|
| `host.js`, `generate.js`, `legacy.js`, `schema.js`, `utils.js`, `tree.js`, `collection.js` | `nuxt-studio/dist/module/runtime/…` (1.8.0; `utils/document/…` for the document ones) |
| `module.mjs`, `rt/schema.js`, `rt/collection.js` | `@nuxt/content` 3.16.2 `dist/module.mjs`, `dist/runtime/internal/schema.js`, `dist/runtime/internal/collection.js` (not installed here; `npm pack @nuxt/content@3.16.2` to read them; `lib/nuxt-content-parse.mjs` reimplements them) |
| `mdc/index.js`, `mdc/options.js`, `mdc/compiler.js`, `mdc/props.js`, `mdc/code.js`, `mdc/containerComponent.js` | `@nuxtjs/mdc/dist/runtime/parser/…` (0.23.3; `parser/index.js`, `parser/options.js`, `parser/compiler.js`, `parser/utils/props.js`, `parser/handlers/*.js`) |
| `remark-mdc` | `remark-mdc/dist/index.mjs` (3.11.1) |
| `minimark/hast` | `minimark/dist/hast.mjs` (0.2.0) |
| `sort-attributes`, `sort-attribute-values` | `rehype-sort-attributes/lib/index.js`, `rehype-sort-attribute-values/lib/index.js` |
| comark renderer | `comark/dist/internal/stringify/…` (0.7.0) |

## 1. What D is

D(y) is what Studio writes when it saves a document it opened from its database without a code-editor edit (both
editors start from it; a visual-editor save additionally passes the body through TipTap, which is not covered here):

1. **Nuxt Content builds the row** (`module.mjs` `createParser` 1464-1554, emulated by `lib/nuxt-content-parse.mjs`):
   - CRLF → LF (1507-1509).
   - `@nuxtjs/mdc` `parseMarkdown` (`mdc/index.js` 69-102) with Nuxt Content's options: `compress: true`, `remark-emoji`
     added (1490-1498), the shiki highlight rehype plugin (1468, 1484-1489), `contentHeading` on for page collections
     only (1518), and the `.md` link handler (`module.mjs` 1284-1310). `@nuxtjs/mdc`'s defaults add `remark-mdc`,
     `remark-gfm`, `rehype-external-links`, `rehype-sort-attribute-values`, `rehype-sort-attributes` and `rehype-raw`
     (`mdc/options.js` 8-47).
   - Front matter: `remark-mdc` `parseFrontMatter` (`remark-mdc` 48-67) — the `yaml` package (YAML 1.2 core schema,
     `parseDocument(...).toJSON()`), then `flat.unflatten`.
   - `data = Object.assign(contentHeading(body), frontmatter, …)` (`mdc/index.js` 82-86): title/description from the body
     are defaults that front matter keys override.
   - Body: hast → `@nuxtjs/mdc` `compileHast` (`mdc/compiler.js`) → minimark `fromHast` (`minimark/hast` 2-17).
   - Schema split: keys of the collection schema go to columns, everything else into the `meta` JSON column (1519-1530);
     `seo.title/description` default to title/description (1537-1541); `path`, `title`, `stem`, `extension` filled from
     the file path when missing (1542-1548, `pathMeta`/`generateTitle` 1170-1217).
   - SQL insert per column type (`module.mjs` `generateCollectionInsert` 2528-2558) and read back by
     `refineContentFields` (`rt/collection.js`): JSON columns go through `JSON.stringify`/`JSON.parse`, BOOLEAN through
     `!!` and `Boolean()`, INT through `Number()`, DATE/DATETIME through `formatDate`/`formatDateTime` (`module.mjs` 428-467),
     VARCHAR/TEXT through `String()`. Columns are ordered by `getOrderedSchemaKeys` (`rt/schema.js`): `id`, `title`, then
     every other key sorted (`generateCollectionTableDefinition` 2640-2662).
2. **Studio reads the row** (`host.js` 177-188): `ensureComarkBody(sanitizeDocumentTree({ ...row, fsPath }, collection))`.
   `sanitizeDocumentTree` (`tree.js` 2-41) drops a trailing `<style>` node (shiki's), rewrites every `pre` with a `code`
   prop to `["pre", {…, language, style, meta}, ["code", {__ignoreMap: ""}, code]]` with `language: undefined` for a bare
   fence (`language: "text"` and no `meta`), and deletes keys marked `$content.editor.hidden` in the schema.
3. **Studio renders it** (`generate.js` 121-129): `comarkTreeFromLegacyDocument` (`legacy.js` 465-470) decompresses the
   minimark body and converts it with `mdcToComark` (`legacy.js` 29-36) using `cleanDataKeys(document)` (`schema.js` 35-74)
   as the front matter; then `unbindComarkTree` (`legacy.js` 367-396), comark `renderMarkdown(…, {blockAttributesStyle:
   'frontmatter'})`, `padBlankLinesInComponents` (`legacy.js` 401-447) and a final `"\n"` — the same renderer as T.

T(y) instead parses y with comark (`generate.js` 71-100: `autoClose: false`, `autoUnwrap: true`, `linkify: false`, emoji,
shiki and toc plugins) and renders `tree.frontmatter` as parsed. **So every difference between D(y) and T(y) is a difference
between the two renderer inputs**: the front matter (`cleanDataKeys` of the row vs the js-yaml `JSON_SCHEMA` parse of y) and
the tree (Nuxt Content's remark/rehype parse + `mdcToComark` vs comark's markdown-exit parse). `lib/db-diff.mjs` classifies
by comparing exactly these two inputs, after a renderer-equivalent normalisation (section 4).

## 2. Front matter rules

The rendered front matter is `cleanDataKeys(document)` (`schema.js` 35-74) of the row, dumped by comark's js-yaml 5.5 dump
(`StudioYaml`). Its key order is the row's key order: the schema columns in `getOrderedSchemaKeys` order, then the `meta`
keys in source order (`schema.js` 60-64 appends them).

### FM-TITLE-H1, FM-TITLE-FILE — title injected (page collections)

A page collection has a `title` column. When the front matter has no truthy `title`:

- **FM-TITLE-H1**: `contentHeading` (`mdc/index.js` 103-119) takes the text of the first top-level element when it is an
  `h1`, ignoring `hr` elements (but not components or any other element). The text is `nodeTextContent` (`mdc/utils/node.js`
  26-41): all descendant text joined, markup dropped (`# Title with **bold** and \`code\`` → `Title with bold and code`).
- **FM-TITLE-FILE**: otherwise `title = parsed.data?.title || undefined` (`module.mjs` 1252/1263 in the markdown transformer;
  emulation line 128) is filled by `pathMeta` (1542-1548): `generateTitle(refineUrlPart(basename))` (1196-1206): drop a
  numeric `N.` prefix, `index` → `''`, a `.draft` suffix, keep semver-like names, then split on whitespace and `-` and
  `pascalCase` each part (scule). Recorded examples: `page.md` → `Page`, `1.getting-started.md` → `Getting Started`,
  `2.api-reference.draft.md` → `Api Reference`, `2024-01-15-hello-world.md` → `2024 01 15 Hello World`, `my_file name.md`
  → `MyFile Name`, `1.2.0.md` → `'120'` (a string column, dumped quoted), `index.md` → `''`.
- An empty title (`index.md`, or `title: ''` in the source) is dropped again by `cleanDataKeys` (`schema.js` 54-56):
  **FM-DROP-EMPTY-TITLE** also covers an explicit `title: ''` / `description: ''` that disappears.
- The title is a schema column, so it is written **first** (`getOrderedSchemaKeys` puts `title` right after `id`), before
  every other key, wherever the source had it (that move is FM-KEY-ORDER when the source had a title elsewhere).

Data collections have no `title` column and `contentHeading` is off (`module.mjs` 1518): nothing is injected, and a
front matter `title` is an ordinary `meta` key that keeps its position.

### FM-DESCRIPTION — description injected (page collections)

Same mechanism with the `description` column: `contentHeading` takes the first top-level element **after** the optional
h1 (ignoring `hr`) when it is a `p` (`mdc/index.js` 111-114), as plain text. A component, list, heading, code block, table
etc. in that position gives no description. Written right after the title for the default page schema; with a custom
schema it takes its alphabetical column position (`author`, `date`, `description`, `draft`, …). An empty description is
dropped (`schema.js` 57-59).

### FM-KEY-ORDER — column order

Schema columns come first, in `getOrderedSchemaKeys` order (`rt/schema.js`, the same function in `collection.js`
28-35): `id`, `title`, then **all other schema keys sorted by code unit**, regardless of the order in `content.config.ts`
(`date, author, tags, image` declared before `title` come out as `title, author, date, description, draft, image, order,
tags`). Reserved columns (`id`, `stem`, `extension`, `path`, `body`, `meta`, …) are omitted (`schema.js` 4, 36). Then the
`meta` keys (everything not in the schema) in source order. For the default page schema the visible columns are `title`,
`description`, `seo`, `navigation`; `seo` and `navigation` are columns, so they move before all `meta` keys too.

### FM-DEFAULT — schema defaults injected

A column with a `default` gets the default when the key is missing or its value stringifies to `"null"`
(`generateCollectionInsert` 2533-2535). `navigation` (default `true`) and `seo` (default `{}`) are removed again by
`cleanDataKeys` (`schema.js` 37-53), so with the default schema nothing shows; a user default such as
`draft: z.boolean().default(false)` is written into every document (`draft: false`, 4603 of 4753 vectors in
`pageSchema`).

### FM-COERCE — column type coercion

Values of non-JSON columns are converted by the SQL round trip (`generateCollectionInsert` 2537-2556,
`refineContentFields`):

| column (zod type) | conversion | recorded example |
|-------------------|------------|------------------|
| VARCHAR / TEXT (`z.string()`, `z.enum()`) | `String(v)` | `author: 42` → `author: '42'`; `author: true` → `'true'`; `author: {name: A}` → `'[object Object]'` |
| INT (`z.number()`) | `Number(v)`; NaN is not a SQL literal, so the row is not inserted (see `error`) | `order: '5'` → `order: 5`; `order: abc` → no document |
| BOOLEAN (`z.boolean()`) | `!!v` → `Boolean()` | `draft: 'false'` → `draft: true` |
| DATE (`z.date()`; zod-to-json-schema with `dateStrategy: 'format:date'`, `module.mjs` 3116) | `formatDate`: `toUtcDate(v).toISOString().slice(0, 10)`; invalid → the file is skipped | `date: 2024-01-15T10:20:30Z` → `'2024-01-15'`; `date: 2024` → `'2024-01-01'`; `date: 2024-02-30` → no document |
| DATETIME (`format: date-time`) | `formatDateTime`: `YYYY-MM-DD HH:MM:SS` UTC | |
| JSON (`z.array()`, `z.object()`, unions) | JSON round trip (FM-JSON) | `tags: single` stays a string |

`title` and `description` are VARCHAR columns too, so a non-string title (`title: 42`) becomes a string.

### FM-JSON — JSON round trip of `meta` and JSON columns

`meta`, `seo`, `navigation` and every JSON column go through `JSON.stringify`/`JSON.parse`: YAML anchors/aliases (shared
objects, which T dumps as `&ref_0`/`*ref_0`) are expanded into copies, `-0` becomes `0`, `undefined` disappears, `NaN`/
`Infinity` become `null` (then dropped, FM-DROP-NULL), Date objects (not produced by the `yaml` 1.2 parse) would become ISO
strings.

### FM-DROP-NULL, FM-DROP-EMPTY-LIST, FM-DROP-NAVIGATION, FM-SEO, FM-RESERVED — dropped keys

`cleanDataKeys` (`schema.js` 35-74):

- **FM-RESERVED**: `id`, `fsPath`, `stem`, `extension`, `__hash__`, `path`, `body`, `meta`, `rawbody` are omitted, also when
  they come from `meta` (a data collection's `path: …` front matter key disappears) (4, 36, 60-64).
- **FM-DROP-NAVIGATION**: `navigation: true` or `'true'` (37-39). `navigation: false` and objects stay.
- **FM-SEO**: `seo.title` / `seo.description` are removed when empty or equal to the document's title/description, and
  `seo` when it becomes empty (40-53).
- **FM-DROP-EMPTY-TITLE**: falsy `title` / `description` (54-59).
- **FM-DROP-NULL**: top-level keys whose value is `null` (65-68). Nested nulls stay.
- **FM-DROP-EMPTY-LIST**: top-level empty arrays (69-71). Empty objects (`{}`) stay; nested empty lists stay.

### FM-UNFLATTEN — dotted keys

`remark-mdc` `parseFrontMatter` ends with `flat.unflatten` (`remark-mdc` 64-66): `nested.key: v` becomes
`nested:\n  key: v`. The same happens to component YAML props (`remark-mdc` 2128-2132 → `parseFrontMatter`), which
appears as B-ATTR-OTHER.

### FM-EXCERPT — `<!--more-->`

An HTML comment `more` makes `@nuxtjs/mdc` produce an excerpt (`mdc/compiler.js` 71-83); Nuxt Content stores it as
`excerpt` (minimark of the part before the comment, `nuxt-content-parse.mjs` 126), which lands in `meta` and is written into
the front matter as a YAML object (`excerpt: {type: minimark, value: …}`).

### FM-YAML, FM-OTHER — YAML 1.2 vs js-yaml JSON_SCHEMA

D parses the front matter with the `yaml` package (YAML 1.2 core; `remark-mdc` 52-55), T with js-yaml `JSON_SCHEMA`
(comark `internal/yaml.js` 2-8). On T output both agree for nearly every scalar, because js-yaml's dump quotes anything
its default schema would resolve differently. Differences that remain on T-stable y (11 vectors): a front matter that is not
a mapping (a scalar or list: Studio spreads it into the document, so a string becomes `'0': …, '1': …` keys — FM-OTHER/FM-YAML),
and values whose YAML 1.2 resolution differs (FM-YAML).

## 3. Body rules

Each rule names where the D tree differs from the T tree. Counts are over T-stable y in the `data` configuration (no front
matter injection), out of 3943 documents D can read.

### B-ATTR-ORDER — attributes sorted by document-wide frequency (197)

`rehype-sort-attributes` (`mdc/options.js` 31-33; `sort-attributes` 13-99) reorders the properties of **every element
by how often that property name occurs on elements with the same tag name in the whole document**, most frequent first,
ties broken by code unit order (`compare`, 115-121). Component props are hast properties too, so inline props
(`::callout{icon color to}` → `{color icon to}`), YAML block props and slot attributes are all reordered. A JSON-bound prop
is stored as `:key` (`remark-mdc` `bindData` 2135-2146: non-string values become `:key: JSON`), so it sorts with its
colon: `:count`, `:enabled`, … before `date`, `description`, `title`. Note the order is **not** per element: adding a prop
to one `::card` can reorder the props of every other `::card`.

`rehype-sort-attribute-values` (`sort-attribute-values` 1-60) likewise reorders the values of space-separated list
properties (`className`, `rel`, …) by document-wide frequency (**B-CLASS-ORDER**).

### B-ATTR-UNBIND — `:prop` bindings lose their colon (109)

`propsMDCToComark` → `unbindMDCProps` (`legacy.js` 311-366): a `:key` whose value parses as JSON and is not a boolean is
replaced by `key` with the parsed value; arrays of primitives are joined with spaces (335-341). The renderer then writes a
plain `key="1"` (or a YAML value). So `::badge{:count="5"}` → `::badge{count="5"}`, and in YAML props `:a: 1` becomes `a: 1`.
`:key="true"`/`"false"` stay bound (348-353).

### B-ATTR-CAMEL — hast property names (5)

Props pass through hast, so HTML attribute names become hast property names: `data-x` → `dataX`, `autoplay` →
`autoPlay`, `class` → `className` (turned back into `class` by `propsMDCToComark` 322-330).

### B-ATTR-REL — `rel` removed (3)

`propsMDCToComark` deletes every `rel` (`legacy.js` 331-334), including a `rel` written in the source and the
`rel="nofollow"` that `rehype-external-links` (`mdc/options.js` 25-27) adds to absolute `http(s)` links.

### B-ATTR-UNSAFE, B-ATTR-ENTITY, B-ATTR-OTHER — other attribute differences (153 together)

- **B-ATTR-UNSAFE**: `validateProps` (`mdc/props.js` 90-113) drops `on*` handlers, `srcdoc`/`formaction`/…, and
  `href`/`src`/`action` values that `isAnchorLinkAllowed` (20-35) rejects (an unparsable URL such as
  `https://gi:thub.com/…`). It also drops `name` when a `:name` exists (98-100).
- **B-ATTR-ENTITY**: character references in attribute values are decoded (`name="a&#35;b"` → `name="a#b"`).
- **B-ATTR-OTHER**: everything else at attribute level, mostly: attributes after a space (`## Heading {.x}`,
  `Paragraph {.x}`) are attributes for comark but text for remark-mdc (written back as `\{.x}`); boolean HTML attributes
  become `true`; YAML props with dotted keys are unflattened; numeric attribute values become numbers.

### B-LINK-MD — relative `.md` links rewritten (10)

Nuxt Content's link handler (`module.mjs` 1284-1310) rewrites a relative link ending in `.md` (optionally with `#hash`) to
`generatePath(link without .md)`: path segments slugified and numeric prefixes removed (`./other.md` → `./other`,
`../dir/01.intro.md#Install` → `../dir/intro#Install`).

### B-AUTOLINK — bare URLs become links (91)

`remark-gfm` (`mdc/options.js` 14-16) turns literal URLs and e-mail addresses into links; Studio's comark parse has
`linkify: false` (`generate.js` 81). `See https://example.com` → `See [https://example.com](https://example.com)`.

### B-HTML — raw HTML is parsed (245)

T keeps raw HTML as html-marked nodes (`$: {html: 1}`) and writes it back as HTML. D parses it with `rehype-raw`
(`mdc/options.js` 34-39) into ordinary elements, which the renderer writes as Markdown or components (`<details>` →
`::details`, `<b>x</b>` → `:b[x]`, `<img>` → `![](…)`, `<table>` → a Markdown table, `<br>` → a hard break). HTML that
spans blocks (`<div>` opened in one paragraph) restructures the document. The IDE keeps raw HTML verbatim
(`StudioSafety` RAW_HTML), so this rule does not apply to blocks it rewrites.

### B-COMMENT — HTML comments removed (38)

minimark's `fromHast` drops comment nodes (`minimark/hast` 9), so `<!-- … -->` disappears (and `<!--more-->` adds
FM-EXCERPT). Kept verbatim by the IDE (HTML_COMMENT).

### B-LI-PARAGRAPHS — list item paragraphs merged (25)

`compileHast` (`mdc/compiler.js` 21-38) unwraps every `p` inside an `li` and puts a `br` before each paragraph after the
first. A loose item `1. first\n\n   second` becomes `1. first  \nsecond` (a hard break), and a component after the first
paragraph becomes inline (`:code-group[…]`). The IDE keeps components in list items verbatim (COMPONENT_IN_LIST_ITEM).

### B-WHITESPACE — whitespace in text and code (253)

- `detab` in the code handler (`mdc/code.js` 6) expands tabs in code blocks to spaces (4-column stops).
- A paragraph followed by a nested block in a list item keeps a trailing `"\n"` (`li: [p "text\n", ul]`), which renders as
  a blank line before the nested list.
- Trailing spaces/tabs at line ends are trimmed by remark.

### B-CODE, B-CODE-HIGHLIGHTS — code blocks (187, 19)

- **B-CODE**: an empty fenced code block has no `code` prop, so `sanitizeDocumentTree` (`tree.js` 13-31) does not rewrite
  it and the highlighter's `class="language-text shiki …"` survives: `` ``` `` + `` ``` `` becomes
  `::pre{.language-text}` around `` ```text ``. Other `pre` attribute differences (meta, filename) also count here.
- **B-CODE-HIGHLIGHTS**: `{2,4-5}` is parsed to `[2, 4, 5]` (`parser/handlers/utils.js` `parseHighlightedLines` 41-47, called from `mdc/code.js` 5), then
  `propsMDCToComark` joins the array to the string `"2 4 5"` (`legacy.js` 335-341) and comark's `formatHighlights`
  spreads the string's characters: `{2,4-5}` → `{ , ,2,4,5}`. The IDE keeps highlights verbatim (FENCE_HIGHLIGHTS).

### B-TABLE-ALIGN — column alignment lost (10)

The `align` of `th`/`td` does not survive minimark + `mdcToComark`, so `| :--- |` becomes `| --- |`. Kept verbatim by the
IDE (TABLE_ALIGNMENT).

### B-BINDING — `{{ }}` becomes `:binding` (22)

`remark-mdc` parses `{{ expr || 'default' }}` into a `binding` text component (`remark-mdc` 623-634), which the renderer
writes as `:binding{value="expr" defaultValue="default"}` (as a block component when it is alone in a slot).

### B-COMPONENT-HR — `---` at the start of a component (124)

A component whose first line is `---` opens a YAML props block for remark-mdc (`remark-mdc` 545, 644-650, 686), while
comark (T) reads it as a thematic break. D swallows the content up to the next `---` as props (often everything, giving
an empty component).

### B-BRACKET-SPAN, B-INLINE-COMPONENT — inline syntax only comark knows (70, 63)

- **B-BRACKET-SPAN**: comark parses a bare `[text]` (no link, no attributes) as a `span`; remark-mdc as text, which the
  renderer escapes (`\[text\]`) where needed.
- **B-INLINE-COMPONENT**: comark accepts inline components that remark-mdc does not (`:1.`, `:not_an_emoji:`, names with
  characters remark-mdc rejects); D keeps them as text.

### B-SLOT — slot structure (11)

`#slot` lines that comark treats as text (no blank line before them, e.g. after a paragraph) are slots for remark-mdc
(`template` elements), and a component alone in a slot becomes a block component.

### B-EMOJI, B-CLOSING-MARKER, B-TEXT — text content (6, 4, 104)

- **B-EMOJI**: `remark-emoji` (`module.mjs` 1493) converts `:name:` shortcodes that comark's emoji plugin left
  alone (in contexts comark does not convert); written back as the emoji character.
- **B-CLOSING-MARKER**: `repairMdcRoot` (`legacy.js` 75-169) removes lines consisting only of `::` that remark-mdc left in
  a paragraph (an escaped `\::` in y is such a line for remark-mdc).
- **B-TEXT**: other differences inside a text node (escapes, entity decoding, emphasis parsed differently).

### B-STRUCTURE — other parse divergence (318)

Element structure that differs for reasons not covered above: remark-mdc/micromark and comark/markdown-exit disagree on
the block or inline structure (task list markers `[ ]` vs `[]`, emphasis runs, lazy continuation, attribute spans, nested
list indentation, …). `db-vectors.json` `summary.byConfig.<config>.structureNotes` lists the 25 most frequent shapes as
`parent: [T children] vs [D children]`.

## 4. How the classifier compares

`lib/db-diff.mjs` compares the renderer inputs of T(y) and D(y) for the same y and returns every rule that explains a
difference. Before comparing it applies only transformations under which `renderMarkdown` gives the same text (checked
with the renderer): the shiki `class` on `pre` and all attributes of the `code` inside `pre` are ignored (the renderer
only reads a `language-*` class when `pre` has no `language`, stringify `handlers/pre.js` 6-11, and drops `shiki*` classes,
`attributes.js` 93-96); keys whose value is `undefined` are ignored; and comark's `applyAutoUnwrap`
(`comark/dist/internal/parse/auto-unwrap.js`) is applied to both trees (a node whose only child is a plain paragraph renders
like the paragraph's children). Number/string attribute values that render the same are equal. Meta categories:

| id | meaning |
|----|---------|
| `Y-NOT-T-STABLE` | T(y) != y, so part of the difference is the text path's own (only for y that are not T-stable; the IDE never produces these) |
| `Y-T-THROWS` | T throws on y (y not re-parsable), no tree to compare |
| `error` | D has no document: Nuxt Content's parse crashed (remark-mdc micromark errors on some inputs) or a column value cannot be inserted (FM-COERCE) |
| `RENDER` | the inputs are equal but the text differs (not expected; 0 on T-stable y) |
| `FM-OTHER` / `B-STRUCTURE` | a difference no specific rule explains |

## Statistics

From `db-vectors.json` (4753 distinct y; 3966 of them T-stable, T(y) == y). Configurations: `pageDefault` (page
collection, Studio's default schema), `pageSchema` (page collection with `date`, `author`, `tags`, `image`, `order`,
`draft` (default `false`)), `data` (data collection). Counts over **T-stable y**:

| | pageDefault | pageSchema | data |
|---|---:|---:|---:|
| D has no document | 23 | 35 | 23 |
| D(y) == y | 330 | 0 | 2388 |
| T(D(y)) == D(y) | 3645 / 3943 | 3633 / 3931 | 3645 / 3943 |
| D(D(y)) == D(y) | 3632 / 3943 | 3619 / 3931 | 3636 / 3943 |
| both (D(y) is a dual fixed point) | 3580 / 3943 | 3567 / 3931 | 3584 / 3943 |

D(y) != y by rule (T-stable y; a document can have several rules):

| rule | pageDefault | pageSchema | data |
|------|---:|---:|---:|
| FM-DEFAULT | | 3917 | |
| FM-TITLE-FILE | 2688 | 2682 | |
| FM-DESCRIPTION | 2061 | 2052 | |
| FM-TITLE-H1 | 114 | 108 | |
| FM-JSON | 52 | 52 | 52 |
| FM-COERCE | 4 | 34 | |
| FM-KEY-ORDER | 19 | 32 | |
| FM-DROP-NULL / -EMPTY-LIST / -NAVIGATION / -EMPTY-TITLE | 13 / 9 / 5 / 4 | 13 / 9 / 5 / 6 | 13 / 9 / 0 / 4 |
| FM-UNFLATTEN, FM-SEO, FM-RESERVED, FM-EXCERPT | 8, 4, 3, 2 | 8, 4, 3, 2 | 8, 0, 3, 2 |
| FM-YAML, FM-OTHER | 11, 11 | 11, 11 | 11, 11 |
| B-STRUCTURE | 389 | 394 | 318 |
| B-ATTR-ORDER | 272 | 284 | 197 |
| B-WHITESPACE | 258 | 261 | 253 |
| B-HTML | 245 | 245 | 245 |
| B-CODE | 188 | 188 | 187 |
| B-ATTR-OTHER | 180 | 187 | 153 |
| B-ATTR-UNBIND | 131 | 140 | 109 |
| B-COMPONENT-HR | 124 | 124 | 124 |
| B-TEXT | 104 | 104 | 104 |
| B-AUTOLINK | 91 | 91 | 91 |
| B-BRACKET-SPAN | 70 | 70 | 70 |
| B-INLINE-COMPONENT | 63 | 63 | 63 |
| B-COMMENT | 38 | 38 | 38 |
| B-LI-PARAGRAPHS | 25 | 25 | 25 |
| B-BINDING | 22 | 22 | 22 |
| B-CODE-HIGHLIGHTS | 21 | 21 | 19 |
| B-SLOT | 11 | 11 | 11 |
| B-LINK-MD, B-TABLE-ALIGN | 10, 10 | 10, 10 | 10, 10 |
| B-EMOJI, B-ATTR-CAMEL, B-CLOSING-MARKER | 6, 5, 4 | 6, 5, 4 | 6, 5, 4 |
| B-ATTR-ENTITY, B-ATTR-REL, B-ATTR-UNSAFE | 3, 3, 3 | 3, 3, 3 | 3, 3, 3 |

The body counts are higher for the page configurations because there nearly every document is classified (its front
matter changes), so tree differences that happen not to change the text (for example the order of a code block's
attributes, which the renderer writes in a fixed order) are counted too. The `data` column counts only documents whose text
changes.

Reading the numbers:

- For **page collections D almost never leaves y alone** (330 of 3943), and essentially always because of the front
  matter: the injected title (from the first h1 or the file name) and description, plus schema defaults. These are exact,
  data-only rules (section 2) that do not depend on the body parse beyond `contentHeading`.
- With the front matter out of the way (`data`), **D(y) == y for 61% of T-stable documents**. The body rules that change
  the remaining ones are dominated by attribute order (B-ATTR-ORDER), whitespace in code and lists, raw HTML, code blocks
  and parse divergences. Several of these (raw HTML, comments, components in list items, table alignment, highlights) are
  blocks the IDE already keeps verbatim.
- **D's output is itself a dual fixed point for about 91%** of documents (3580 of 3943): T(D(y)) == D(y) and D(D(y)) ==
  D(y). Where it is not, D(y) is not the stable target, and decision 1 (prefer the text-path form) applies.
- The Kotlin text path (`StudioFormatter.format`) reproduces T on all 4753 y and on all 11317 recorded D(y) != y
  (`StudioDbVectorsTest`).

## Fidelity

The D side runs the real Studio code on the row `lib/nuxt-content-parse.mjs` emulates, with the caveats of README.md
("Fidelity caveats"). Specific to this file: the `pageSchema` columns are emulated from the zod → JSON schema mapping
(`rt/schema.js` `getPropertyType`), there is no `$content.editor.hidden` key, and Nuxt Content's zod validation is not run
(it does not validate in 3.16.2 either: values are inserted as parsed).
