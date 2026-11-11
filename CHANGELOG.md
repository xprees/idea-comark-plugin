# Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.5...HEAD
[2026.3.5]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.2...2026.3.5
[2026.3.2]: https://github.com/xprees/idea-comark-plugin/compare/2026.3.0...2026.3.2
[2026.3.0]: https://github.com/xprees/idea-comark-plugin/commits/2026.3.0
