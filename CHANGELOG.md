# Changelog

## [Unreleased]

## [2026.3.15] - 2026-11-13

### Added

- *Code Style › Comark › Style*, Custom style: *Component props* picks how block components write their props: always inline
  (`::card{title="A" icon="i-x"}`, any number of props), always in a YAML props block, or inline up to a number of props (Nuxt
  Studio's rule, up to 3). Object and array props always use a YAML block. In `.editorconfig`: `ij_comark_props_layout =
  inline`, `block` or `up_to`
- *Code Style › Comark › Style*: Reformat Code can write the **Nuxt Studio** style, next to the VS Code formatter (the default).
  The result is a form Nuxt Studio's code editor keeps unchanged when it saves the file, and its visual editor too wherever the
  engine can emulate that save path (other blocks are reported as not checked). Content Studio would damage is kept as written.
  The front matter is completed by Studio's data rules for the file's collection in `content.config.ts`: title and description
  added, keys in Nuxt Content's column order with schema defaults, `navigation: true`, nulls and empty lists dropped
- A **Custom** variant of the Nuxt Studio style: bullet and ordered list markers, emphasis and strong markers, fence character,
  blank lines between blocks, props inline or as YAML, prop order, front matter rules (Studio's, re-dump only, or kept) and
  indent size. Stored in the code style scheme and readable from `.editorconfig` (`ij_comark_style_preset = nuxt_studio`, ...).
  Selection reformat rewrites only the blocks it touches, formatter off/on markers are honoured, and a whole-file reformat in
  the Studio styles converts the file to `\n` line breaks without a BOM
- *Nuxt Studio will rewrite this* inspection (Nuxt Studio and Custom styles): marks the blocks Reformat Code keeps as written
  because Studio would damage them on save (raw HTML, HTML comments, reference links, table alignment, heading ids, YAML
  comments, ...) and says what Studio would do, with fixes to remove table alignment or a heading id, or to suppress the warning
  for the block with formatter off/on comments; blocks the formatter could not check against Studio's database path get a
  weaker "Nuxt Studio may rewrite this" note
- *Emoji shortcode or character reference Nuxt Studio converts* inspection (Nuxt Studio and Custom styles): `:rocket:`,
  `&copy;` and similar, with a fix that writes the character Studio writes
- A banner on Comark files in the Studio styles when the project uses another nuxt-studio (or comark) version than the one the
  style matches (nuxt-studio 1.8.0, comark 0.7.0), read from `package.json` and the npm, pnpm, Yarn or Bun lockfile; dismissible
  per version
- Projects that depend on nuxt-studio and still use the VS Code style (and do not set the preset in `.editorconfig`) are
  offered the Nuxt Studio style once (*Use Nuxt Studio style*, *Not now*, *Don't ask again*)

### Changed

- Nuxt Studio style: blocks that Studio's code editor rewrites because a damaged block before them runs into them (a kept
  raw HTML block or `::a` component swallowing the next heading, for example) are now reported with *Nuxt Studio will rewrite
  this*, naming the line of the block that causes it. The formatter finds them by running its exact port of the code editor's
  save on its own output. Against the real nuxt-studio 1.8.0, the documents of the test corpora that Studio changes outside the
  reported blocks drop from 138 to 40, none of them on the code editor's path
- The raw HTML warning says when a tag is never closed (`<Counter />` included, since HTML ignores the `/>` of a non-void
  element): Studio's database path puts everything after it inside that element. The footnote warning says the definitions move
  to a section at the end of the document

### Fixed

- Custom style: with an indent size of 4 or more, components that contain other components ignored the props choice and the
  other options (a YAML props block turned back into inline props); they now get them, nested by Studio's two spaces
- *Code Style › Comark › Style*: a number typed into *Inline props, at most* or *Blank lines between blocks* is now saved when you
  click OK or Apply right away; before, it was taken only after Enter or leaving the field, so Reformat Code kept the old value

## [2026.3.8] - 2026-11-11

### Added

- `Prose*` components can be written without the prefix (`::accordion-item` for `ProseAccordionItem`), as Comark and Nuxt UI
  resolve them: completion offers the short name (typing `prose-` keeps the prefix), and both names are known to the inspection,
  navigation, Find Usages and Rename

## [2026.3.5] - 2026-11-11

### Added

- *Read components like* setting: Nuxt Content and Nuxt Studio (remark-mdc, the default) or the comark parser
- Nuxt image paths (`/images/hero.webp`) in images, reference definitions, image-like component attributes and props, and front
  matter (`image`, `seo.image`, `og:image`) resolve to the Nuxt Image directory and the `public/` directories of the app and its
  layers, understanding `srcDir`, `image.dir`, `dir.public`, `app.baseURL`, `app.cdnURL`, `/_ipx/` and `image.alias`
- Go to Declaration, completion with thumbnails and pixel sizes, and quick documentation with a preview for image paths
- Find Usages of image files, and Rename and Move of image files and directories update the image paths in Comark files
- *Missing Nuxt image* inspection with a fix to the closest existing image; the Markdown plugin no longer reports these paths as
  unresolved files
- The preview shows images from the Nuxt image directories (optionally from the CDN) and image props of components as thumbnails
- *Images* settings: on/off, image directories that replace the detected ones, a CDN URL, CDN images in the preview, and the
  directories detected for the current project

### Fixed

- Nested components indented four or more spaces (Reformat Code with an indent size of 4, e.g. from EditorConfig) are components
  again, as Nuxt Content renders them, instead of code blocks that broke highlighting, folding and the preview

## [2026.3.2] - 2026-11-11

### Added

- Installs, updates and uninstalls without an IDE restart; open Markdown files switch to Comark (and back) right away

### Fixed

- No more deprecated API usage reported by the Plugin Verifier for IDE 2026.3 (Markdown parser calls, dynamic plugin listener)

## [2026.3.0] - 2026-11-11

First release: Comark (Components in Markdown, formerly MDC) support for JetBrains IDEs 2026.1 and newer, on top of the bundled
Markdown plugin.

### Added

- `.mdc` files are always Comark; `.md` files are Comark in the content directories (`content/`, collection sources of
  `content.config.ts`) of packages that depend on Nuxt Content, Nuxt MDC, Comark, Docus or Nuxt UI Pro, so `README.md` and other
  Markdown files stay plain Markdown (or all `.md` files, or none), with a setting for the Nuxt Content configuration file and
  exclusion patterns for files and directories
- Parsing of block components and nested components, YAML props blocks (`---` and `` ```yaml [props] ``), named slots,
  attributes, inline components, spans and bindings, following the comark reference parser (components inside list items stay
  in the list, as in comark)
- Comark files keep every Markdown plugin feature (preview, structure view, lists, tables, header anchors, code fence injection,
  HTML blocks, TODOs) and show the Comark logo as their file icon
- Syntax highlighting with colour settings, and inspections for unclosed components and ignored text after an opener
- Folding, `::` pair matching, YAML editing in props blocks, live templates from the VS Code extension's snippets, auto-closing of
  components on Enter, breadcrumbs, sticky lines and a structure view with components and slots
- Spellchecking of component content and attribute values
- *Reformat Code* with a port of the VS Code extension's formatter: `@nuxtlabs/monarch-mdc` 0.10.0, checked byte for byte
  against the original on 58,766 cases. The Comark VS Code extension 0.6.2 (`Nuxt.mdc`) ships monarch-mdc 0.9.0, whose
  formatter gives identical output on all of them (the two versions differ only in syntax highlighting rules)
- Deliberate differences from VS Code where its formatter would change what a document says: indented code blocks, tabs in code
  fences and hard line breaks are kept, and components inside list items stay in the list item
- Enter and *Auto-Indent Lines* indent the same way as *Reformat Code*; a Comark code style page (indent 2 by default,
  EditorConfig wins); *Do not format* entries and `<!-- @formatter:off -->` / `@formatter:on` markers are honoured
- Completion of component names, props (including values from string unions and type aliases) and slot names, Go to Declaration
  to the `.vue` file, prop and `<slot>`, and quick documentation
- Components from Vue SFCs in `components/` (Nuxt and Nuxt Content naming), `.nuxt/component-meta.mjs` / `.json` from current
  nuxt-component-meta versions, and an optional metadata URL; props from `defineProps` type literals, local or imported
  interfaces and type aliases (relative paths and the Nuxt `~/`, `@/`, `~~/`, `@@/` aliases), runtime props, `withDefaults`
  and the options API. No Vue plugin needed
- Find Usages and Rename for components: renaming a `.vue` file updates every usage in the style it was written in
- *Unknown Comark component* inspection (off by default; HTML elements used as components are not reported)
- Nuxt Content route links (`/guide/install#setup`) resolve to their content files, with anchors and route completion; links to
  comark heading ids and `{#id}` / `id` attributes resolve too
- Components, props and slots render as labelled blocks in the built-in Markdown preview

[Unreleased]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.15...HEAD
[2026.3.15]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.8...2026.3.15
[2026.3.8]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.5...2026.3.8
[2026.3.5]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.2...2026.3.5
[2026.3.2]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.0...2026.3.2
[2026.3.0]: https://github.com/xprees/idea-comark-plugin/commits/2026.3.0
