# Comark

<!-- Plugin description -->
Support for <b>Comark</b> (Components in Markdown, formerly MDC) as used by <b>Nuxt Content</b>, in WebStorm, IntelliJ IDEA,
Rider and other JetBrains IDEs.

<ul>
  <li><b>Highlighting</b> of block components (<code>::alert{type="info"}</code> … <code>::</code>), nested components (<code>:::</code>),
      YAML props blocks, named slots (<code>#footer</code>), inline components (<code>:badge[New]{color="blue"}</code>), spans and attributes</li>
  <li><b>Reformat Code</b> with a port of the VS Code extension's formatter, or in the style <b>Nuxt Studio</b> writes when it saves
      a file (content Studio would damage is kept as written), and indentation on Enter that matches it</li>
  <li><b>Component completion</b>, <b>Go to Declaration</b> and <b>documentation</b> for components, props and slots, from your
      <code>components/</code> Vue files and nuxt-component-meta output; <b>Find Usages</b> and <b>Rename</b> of component files</li>
  <li>Folding, matching <code>::</code> pairs, YAML editing in props, live templates, auto-closing of components, breadcrumbs and a
      structure view; components rendered in the Markdown preview</li>
  <li><b>Nuxt image paths</b> (<code>/images/hero.webp</code>) in images, component attributes, props and front matter resolve to the Nuxt Image
      and <code>public/</code> directories: Go to Declaration, completion with thumbnails, preview, Rename/Move, documentation and a
      missing-image inspection</li>
</ul>

The bundled Markdown plugin keeps working for Comark files. <code>.mdc</code> files are always Comark; <code>.md</code> files are Comark
when they belong to a package that depends on Nuxt Content, Nuxt MDC or Comark (configurable in
<i>Settings › Languages &amp; Frameworks › Comark</i>).
<!-- Plugin description end -->

Works in JetBrains IDEs based on the IntelliJ Platform **2026.1 and newer** that bundle the Markdown plugin (IntelliJ IDEA, WebStorm,
Rider, PyCharm, …). The plugin is built and tested against the oldest supported IDE (`platformVersion` in `gradle.properties`, which also
gives its `since-build`) and verified against the latest IDEA, WebStorm and Rider releases in CI (the full version matrix is opt-in: the
`full-verify` pull request label or a manual run of the *Build* workflow). It declares no upper build limit.

## Which files are Comark

| File | Comark when |
|---|---|
| `*.mdc` | always |
| `*.md` | *Auto* (default): the file is in a content directory of a package whose `package.json` (in the file's directory or above) depends on `@nuxt/content`, `@nuxtjs/mdc`, `comark`, `@comark/*`, `docus` or `@nuxt/ui-pro`. Content directories are any `content/` directory below the package (layers and nested apps included, not `node_modules`), the `cwd` of collection sources in `content.config.ts` and the `base` of Nuxt Content 2 `fs` sources in `nuxt.config.ts`; `README.md`, `CHANGELOG.md` and other Markdown files elsewhere stay plain Markdown. *Nuxt Content config* in the settings points at the configuration to use instead (for a config file in another place or with another name); the `content/` directory next to it and its sources are then Comark, with or without a `package.json`. Or *All .md files*, or never. Files matching an exclusion pattern are never Comark |
| Parsing | *Read components like*: **Nuxt Content and Nuxt Studio** (remark-mdc, default): a component opener counts at any indentation when it starts a block (after a blank line or another component), so nested components may be indented four or more spaces; a closer may sit up to three columns deeper than its opener. **Comark**: strict CommonMark like the comark parser, a line indented four or more spaces past its component is code, and Reformat Code indents nested components by at most 2 spaces |

The setting lives in *Settings › Languages & Frameworks › Comark*, together with exclusion patterns: globs, one per line, matched
like `.gitignore` entries against the path relative to the project directory (`CHANGELOG.md` or `legacy` match a name at any level,
`docs/legacy/**` or `/README.md` match from the project directory, and a matching directory excludes everything in it).

Comark files show the Comark icon. They keep the Markdown file type, so everything the Markdown plugin offers (preview, structure
view, lists, tables, header anchors, code fence injection, …) still applies.

## Features

| Area | What you get |
|---|---|
| Highlighting | Component markers, names, closers, attributes (`#id`, `.class`, `key="value"`, `:binding`), slots, props fences; inline components, spans, attribute groups and `{{ bindings }}` in text. Colours in *Settings › Editor › Color Scheme › Comark* |
| Inspections | *Unclosed block component* (with a fix inserting the closer), *Ignored text after component opener*, *Unknown Comark component* (off by default; HTML element names such as `::div` and `:br` are not reported), and in the Nuxt Studio style *Nuxt Studio will rewrite this* and *Emoji shortcode or character reference Nuxt Studio converts* (see [Formatting](#formatting)). Markdown's unresolved-link and unresolved-anchor inspections understand Comark spans and `{#id}` anchors |
| Formatting | *Code › Reformat Code* uses a line-by-line port of the VS Code extension's formatter (`@nuxtlabs/monarch-mdc` 0.10.0; the Comark VS Code extension 0.6.2 ships 0.9.0, whose formatter output is identical), checked byte for byte against the original on 58,766 cases. Where the original would change what a document says, the IDE keeps it instead (see *Known limitations*); everywhere else the result is the same as in VS Code. Indent size: *Settings › Editor › Code Style › Comark* (2 by default; EditorConfig wins); indentation is always spaces. Enter and *Code › Auto-Indent Lines* indent lines the same way |
| Nuxt Studio style | Reformat Code can also write what Nuxt Studio writes when it saves a file, or a custom variant of it, with inspections for what Studio would change; see [Formatting](#formatting) |
| Components | Completion of component names (`::`, `:::`, inline `:`), props inside `{…}` and in props YAML, prop values from string unions, slot names after `#`; Go to Declaration to the `.vue` file, prop and `<slot>`; quick documentation; Find Usages of a `.vue` file (or from a usage) lists its block and inline usages in Comark files, and renaming the file renames them, keeping each usage's style (`info-box`, `InfoBox`, `infoBox`). Sources: Vue SFCs in `components/` (Nuxt naming, including grouping folders and `islands/`; files in `components/content/` subfolders get Nuxt Content's name without the folder prefix), `.nuxt/component-meta.mjs` / `.json`, an optional metadata URL. Props come from `defineProps<…>()` type literals, local or imported interfaces and type aliases (`import type { Props } from './types'`, relative paths and the `~/`, `@/`, `~~/`, `@@/` aliases, `extends`, `A & B`, re-exports, member types that name type aliases such as `variant?: Variant`), runtime `defineProps({…})`, `withDefaults` and the options API. No Vue plugin needed |
| Route links | In Nuxt Content packages, page routes in links (`[Install](/guide/install#setup)`) resolve to their content file (`content/1.guide/2.install.md`, `index` files for directories, ordering prefixes and extensions dropped): Go to Declaration opens the page, `#anchor` resolves and completes against its headings, and routes complete after `](/`. Routes that match no content file are not reported (custom pages may exist) |
| Editor | Folding of components, slots and props (Markdown folds kept); matching `::name` / `::` highlighting and navigation; YAML highlighting and checks in props blocks; live templates from the VS Code snippets; Enter after an unclosed opener inserts its closer; breadcrumbs and sticky lines; structure view with headers, components and slots; spellchecking of component content and attribute values |
| Images | Site paths of images (`![](/images/hero.webp)`, reference definitions, image-like attributes and props such as `src`, `image`, `heroImage`, `cover`, `logo`, and front matter keys such as `image`, `seo.image`, `og:image`, `image.src`) resolve to the image in the Nuxt image directories (see [Images](#images)): Go to Declaration, completion with thumbnails and pixel sizes, quick documentation with a preview, size and directory, Find Usages of image files, Rename and Move of image files and directories, a *Missing Nuxt image* inspection with a fix to the closest existing path, and images shown in the preview |
| Preview | Components, props and slots render as labelled blocks in the built-in (JCEF) Markdown preview; image paths load from the Nuxt image directories, and image props of components show as thumbnails |

## Formatting

*Settings › Editor › Code Style › Comark › Style* picks what *Code › Reformat Code* writes for Comark files:

| Preset | What Reformat Code does |
|---|---|
| **VS Code (monarch-mdc)** (default) | Indentation and trailing whitespace only, like the Comark VS Code extension (see *Formatting* under [Features](#features)) |
| **Nuxt Studio** | Writes the document in the form Nuxt Studio 1.8.0 keeps unchanged when it saves the file, from its code editor and from its visual editor alike |
| **Custom** | The Nuxt Studio style with your own choices for the options below |

**What the Nuxt Studio style guarantees.** Studio saves a document in two ways: its code editor writes the text it was given
(the *text path*), and its visual editor, or any save of a document Studio loaded from Nuxt Content, writes what Nuxt Content
parsed (the *database path*). The Nuxt Studio style writes each block in the form both of them leave unchanged; where the two
disagree on the markup, the code editor's form wins. The engine reproduces the text path byte for byte and emulates the
database path where it can follow Nuxt Content's parser (both checked against the real nuxt-studio 1.8.0 code on 480 recorded
documents, over 5,000 random ones and 4,800 database-path recordings): `-` bullets, `1.`
items, `*em*`, `**strong**`, backtick fences, one blank line between blocks, components nested by two spaces, up to three props
inline and more as a YAML block, props in the order Studio's database path gives them (most frequent names on that tag first),
tables aligned like Studio. **Front matter** gets Studio's data rules for the file's Nuxt Content collection (read from
`content.config.ts`, or Studio's defaults for a page collection when there is none): for page collections a missing `title`
(the first `#` heading, else the file name) and `description` (the first paragraph) are added, keys are ordered as Nuxt Content
orders its columns (`title`, then the schema fields sorted by name, with schema defaults such as `draft: false` filled in, then
other keys), and `navigation: true`, nulls and empty lists are dropped; data collections get nothing added. Saving a file
formatted this way again from Studio's code editor changes nothing outside the reported blocks: those kept as written, and
the blocks after them that Studio's damage to them runs into (the engine runs the code editor's save path, ported exactly, on
its own output to find them). Studio's database path is
checked only where the engine can emulate it: blocks it cannot follow are written in the code editor's form and reported as
not checked, so Studio may still rewrite them when it saves a document opened from Nuxt Content. The real nuxt-studio 1.8.0 save
paths are run on the formatted output of the test corpora (`style-vectors.json`); the counts of what they still change, and
where, are pinned in the tests and summarized under [Known limitations](#known-limitations). Reformatting twice gives the same
text. After a whole-file
reformat the file is converted to `\n` line breaks without a BOM, as Studio writes it (each its own undoable step), and the
preset turns off wrap on typing. A selection reformats the whole top-level blocks it touches and the blank lines next to them,
and leaves the other blocks (and front matter the style would add) alone; formatter off/on markers keep the blocks they touch.

**What is kept as written, with a warning.** Some content Studio would damage on save. Reformat Code leaves these blocks exactly
as they are, and the *Nuxt Studio will rewrite this* inspection (weak warning) marks the first line of each with what Studio
would do:

- raw HTML (turned into components) and HTML comments (dropped)
- reference-style links (inlined) and link reference definitions (deleted); footnotes (expanded into components)
- table column alignment (dropped); fix: *Remove column alignment*
- heading `{#id}` attributes (dropped or escaped into the heading text); fix: *Remove the heading id*
- code fence line highlights `{1,3}` and fence file names containing `]`
- YAML comments in front matter or props (dropped), YAML that does not load, a front matter `body` key, front matter that is
  not a mapping (spread into numbered keys)
- components inside list items or indented as code, multi-line props, inline components with object props, unclosed `{`
  attribute lists, attribute lists after plain text (`## Install {#id} now`) or with a quote inside a quoted value, escaped
  `\{` `\}`, inline code containing a backtick, multi-line setext headings, task list items holding a nested list,
  neighbouring lists with different markers, and blocks whose Studio output changes again on the next save
- a thematic break (`***`, `___`) starting a document without front matter, which Studio writes as `---` so that the next
  `---` line closes it as front matter
- content Studio's database path turns into a different document (text, structure or props change; `{{ }}` bindings, which
  it writes as `:binding` components; a line break removed between words and an image or inline component)

Blocks the engine could not check against Studio's database path get a weaker note from the same inspection ("Nuxt Studio may
rewrite this"); so does injected front matter whose title or description may come from a kept block.

Every warning has *Suppress for this block*, which puts `<!-- @formatter:off -->` / `<!-- @formatter:on -->` around the block;
blocks between these markers are not reported. Reformat Code honours the markers when they are enabled in *Code Style ›
Formatter* (Studio itself still rewrites the block and drops the comments).

**What Studio converts.** Emoji shortcodes (`:rocket:`) and HTML character references (`&copy;`, `&#169;`) become the
characters when Studio saves. The *Emoji shortcode or character reference Nuxt Studio converts* inspection reports them, with a
fix that writes what Studio writes (`🚀`, `©`, or an escape such as `\<` for `&lt;tag>`); Reformat Code converts them too.

Both inspections are on by default and active only when the file's style is Nuxt Studio or Custom. Projects that depend on
nuxt-studio but still use the VS Code style get a one-time notification instead (not when their `.editorconfig` sets
`ij_comark_style_preset`), offering *Use Nuxt Studio style* (which sets the project code style, moving the project to a project
scheme if needed), *Not now* or *Don't ask again*.

**Options of the Custom style**: indent size (component nesting), bullet marker, ordered list delimiter, emphasis and strong
markers (`_` only where CommonMark allows it), code fence character, blank lines kept between top-level blocks, props written
inline at most (Studio: 3), props in Studio's order or in the order written, front matter with Studio's data rules, only
re-dumped by js-yaml (as Studio's code editor does) or kept as written. The options are applied to a block only where the result
reads the same (with an indent of 4 or more, blocks with nested components keep Studio's two spaces, which would otherwise read
as code); with any option other than Studio's, Studio rewrites the file to its own style when it saves it. Picking a preset sets
the options; changing an option switches to Custom.

**Sharing the settings.** They are part of the code style scheme (shareable in `.idea/codeStyles`) and can be set in
`.editorconfig`; the options apply with `custom` only:

| Key | Values |
|---|---|
| `ij_comark_style_preset` | `vscode` (default), `nuxt_studio`, `custom` |
| `ij_comark_bullet_marker` | `dash`, `asterisk`, `plus` |
| `ij_comark_ordered_delimiter` | `dot`, `parenthesis` |
| `ij_comark_emphasis_marker`, `ij_comark_strong_marker` | `asterisk`, `underscore` |
| `ij_comark_fence_marker` | `backtick`, `tilde` |
| `ij_comark_max_blank_lines` | `1` and up |
| `ij_comark_props_inline_max` | `0` to `20` |
| `ij_comark_sort_props` | `false`, `true` |
| `ij_comark_front_matter` | `studio`, `redump`, `keep` |

**Version pin.** The Nuxt Studio style reproduces nuxt-studio 1.8.0 with comark 0.7.0. When the package of a Comark file depends
on another nuxt-studio release (read from `package.json` and the nearest `package-lock.json`, `npm-shrinkwrap.json`,
`pnpm-lock.yaml`, `yarn.lock` or `bun.lock`), or resolves comark only in other versions, files in the Studio styles show a banner
naming the versions found. *Dismiss* hides it for those versions; it comes back when the project moves to yet another version.

## Images

Nuxt serves images by site path (`/images/hero.webp`), not by a path relative to the document, so the Markdown plugin can't find
them. In Comark files the plugin resolves a path starting with `/` against these directories, the first match winning:

1. The *Image directories* setting, when it is not empty: only those directories, in the order given (absolute, or relative to
   the project directory).
2. Otherwise, for the app and each of its layers, the Nuxt Image directory when the app uses `@nuxt/image` (in `package.json` or
   `modules`): `image.dir` of `nuxt.config`, relative to the layer's `srcDir` (`srcDir`, `app/` in Nuxt 4 layouts, or the layer
   root), defaulting to the public directory.
3. Then the public directory of the app and each layer: `dir.public` of `nuxt.config`, defaulting to `public/`, next to `app/`
   in Nuxt 4 and inside `srcDir` in Nuxt 3. The version comes from `future.compatibilityVersion` or the `nuxt` dependency.

Layers are the app itself first, then `layers/*` with a `nuxt.config`, then local `extends` entries (remote layers and packages
are not read). An app that is itself a layer of an outer app resolves through the outer app. Before matching, the plugin removes
what Nuxt adds to an image URL: the CDN URL (`app.cdnURL`, or the *CDN URL* setting, which wins), the `app.baseURL`, an
`/_ipx/<modifiers>/` prefix of Nuxt Image, and a `?query` or `#hash`. Paths under an `image.alias` prefix are remote and left
alone; relative paths, `..` and http(s) URLs are not Nuxt image paths either (the Markdown plugin still handles relative ones).
Image-like keys are those whose last word is `src`, `image`, `img`, `icon`, `cover`, `poster`, `logo`, `avatar` or `thumbnail`
(and `src`, `url` or `path` under one), never bindings (`:src`).

What this gives: Go to Declaration (Ctrl+click) on the path part; completion of every image in the directories after `/`, with
thumbnails, pixel sizes and the directory as type text; quick documentation with a preview, pixel size, file size and directory;
Find Usages of image files; Rename and Move of image files and of directories inside an image directory, keeping prefixes, queries and the way a
path was written; a *Missing Nuxt image* inspection (weak warning, Nuxt projects only) with a fix to the closest existing path,
which also replaces the Markdown plugin's unresolved-file warning for these paths; and the preview showing the images.

Settings (*Settings › Languages & Frameworks › Comark › Images*): the switch for all of this, the image directories override, a CDN
URL, *Preview public images from the CDN* (the preview loads images of public directories from the CDN URL instead of the local
files) and the directories detected for the current project.

## Settings

*Settings › Languages & Frameworks › Comark*: which `.md` files are Comark and which are excluded, whether Reformat Code uses the Comark formatter (otherwise
the Markdown one), component completion, an optional component metadata URL and its cache time, and Nuxt image paths (see
[Images](#images)).
*Settings › Editor › Code Style › Comark*: the style Reformat Code writes (*Style* tab: VS Code, Nuxt Studio or Custom, see
[Formatting](#formatting)) and the indentation used by the formatter and Enter.
The platform's formatter controls apply: *Code Style › Formatter › Do not format* entries, and formatter markers in comments
(`<!-- @formatter:off -->` … `<!-- @formatter:on -->` or `[//]: # (@formatter:off)`) leave those files or lines as they are.

## Known limitations

- Some Markdown plugin features check for the plain Markdown language and stay off in Comark files: *Create Link*, *Insert Image*,
  table column/row actions and image drag and drop. HTML `<a id>` anchors are not link targets.
- In the VS Code style, Reformat Code re-aligns top-level tables only; tables inside components are left as they are (the Nuxt
  Studio and Custom styles align them too, as Studio does).
- Reformat Code deliberately differs from the VS Code extension's formatter where that one changes content or structure, so a
  document formatted in both editors can differ in these places: components inside list items stay in the list item (indented to
  its content column; VS Code moves them out), indented code blocks keep their indentation (VS Code turns them into paragraphs),
  tabs in the indentation of code fence content are kept (VS Code turns each into one space), and two or more trailing spaces
  before a continuing line (a hard line break) are kept (VS Code strips them).
- The Nuxt Studio style checks a block against Studio's database path only where the engine can follow Nuxt Content's parser;
  blocks with constructs it does not follow (emoji-like `:words:`, bare URLs, `[text]` spans, empty code blocks, ...) are written
  in the code editor's form and reported as not checked. Running the real nuxt-studio 1.8.0 save paths on the formatted output
  of the test corpora (5,501 documents, most of them random) leaves 2,119 documents unchanged by both paths; in 3,341 more, what
  Studio changes stays inside the blocks the formatter reports (kept as written, rewritten because of a kept block before them,
  unstable, or not checked; for the database path also everything after an HTML tag that is never closed, and the footnote
  section it appends, both named in the warning); 40 change elsewhere on the database path, mostly where Nuxt Content's parser
  carries a kept component (an indented one, or one with broken props) over the blocks after it. The exact counts, by kind,
  are pinned in `StudioStyleOracleTest`. Where a document holds blocks kept as
  written, prop order and the injected title and description are worked out without them, so Studio may still reorder props
  there or write a different description (reported when a kept block may supply it). The collection schema is read from
  `content.config.ts` as text: collections defined in a variable are followed, but a schema held in a variable or built with
  `.extend()` / `.merge()`, or defaults that are not plain literals, are not seen. A file outside every collection is treated as
  a page with Studio's defaults.
- The formatter always indents with spaces, so the Comark code style offers no tab options: Markdown counts a leading tab as four
  columns, which would turn tab-indented nested components into code blocks.
- The preview renders components only with the built-in JCEF preview; Markdown settings list "JCEF Browser" twice (both entries are
  the same choice), a side effect of how the plugin hooks the preview.
- Imported prop types are resolved textually: only from the project's own `.ts` / `.d.ts` files (not packages in `node_modules`
  or `tsconfig` `paths` other than the Nuxt aliases), and mapped or conditional types, generics and default exports are not
  evaluated (the members that can be found are used). Components inside `node_modules` are known only through
  nuxt-component-meta output or the metadata URL.
- Find Usages and Rename cover component names in Comark files only (not Vue templates or plain Markdown), skip Markdown examples
  in code fences, and don't update usages when a component file is moved to another directory.
- Image directories come from a textual reading of `nuxt.config` and `package.json`: computed values (spreads, imported objects,
  environment variables) are not evaluated; the *Image directories* and *CDN URL* settings are the way around that. They apply to
  every project, with relative entries resolved per project. An open preview shows changed image settings or a changed
  `nuxt.config` after its next update (an edit, or reopening it). Completion and documentation show thumbnails for PNG, JPEG, GIF
  and BMP; WebP, AVIF, SVG and ICO get their pixel size (except AVIF and ICO) but no thumbnail.
- Route links follow Nuxt Content's default routing from the package's `content/` directory; collection `prefix` / `source` settings
  in `content.config.ts` and custom routes in front matter (`path:`) are not read, so such links don't resolve (and aren't reported).

## How it works

Comark is registered as a dialect of the bundled Markdown language and Markdown files are switched to it by a `LanguageSubstitutor`
(`lang/`). A Comark file is still a `MarkdownFile`, so Markdown features keep working.

The parser (`parser/ComarkTreeBuilder.kt`) finds the Comark block structure line by line, following the reference parser
([comark `components.ts`](https://github.com/comarkdown/comark)): component openers and closers (matching colon counts, code fences
and same-depth nesting), `---` props blocks right after an opener and `#slot` lines. The Markdown between those lines is parsed by the
Markdown plugin's parser with the component's own indentation removed, exactly as Comark does (so indented content inside a component
is a paragraph, not a code block), and mapped back into one tree. The lexer and PSI parser are built from that tree the same way the
Markdown plugin builds its own.

## Building

```shell
./gradlew test          # parser and feature tests
./gradlew runIde        # sandbox IDE with the plugin
./gradlew buildPlugin   # build/distributions/comark-<version>.zip
./gradlew verifyPlugin  # JetBrains Plugin Verifier against IDEA, WebStorm and Rider
```

Requires JDK 21.

### Versions and releases

The plugin version is `major.minor.patch`, derived from git tags:

- **Tagged commit:** the tag is the version (`0.1.3`, `v0.1.3`, or `0.2.0-beta.1`, whose suffix selects the Marketplace channel: no suffix = stable, `-beta.N` = beta).
- **Any other build:** `major.minor` come from `pluginVersionMajor` / `pluginVersionMinor` in `gradle.properties` (the `PLUGIN_VERSION_MAJOR` / `PLUGIN_VERSION_MINOR` environment variables override them; fallback `0.1`), and the patch is the next free one: the highest existing `major.minor.N` tag plus one, or `0` if there is none.
- `-PpluginVersion=x.y.z` overrides everything.

**On every merge to `master`**, once build, tests and plugin verification are green, the *Build* workflow tags the merge commit with the next version and creates a draft GitHub release for that tag. Major and minor come from the repository variables `PLUGIN_VERSION_MAJOR` and `PLUGIN_VERSION_MINOR` (*Settings → Secrets and variables → Actions → Variables*); if they are not defined, the values in `gradle.properties` are used. The patch increases automatically.

The draft release already has the built plugin attached: `comark-<version>.zip`, plus `comark-<version>-signed.zip` when the signing secrets (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) are configured. That zip is also what you upload by hand for the very first Marketplace release.

To start a new line (say `0.2`), change the two variables; the next merge is tagged `0.2.0`. To ship a build, publish its draft release: the *Release* workflow then builds that exact tag, signs it and publishes it to the Marketplace (`PUBLISH_TOKEN` secret). Signing and publishing are refused for builds that aren't from a tag. For a beta, edit the draft's tag before publishing (e.g. `0.2.0-beta.1`).

## License

Apache-2.0, see [LICENSE](LICENSE). Code ported from other projects is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
