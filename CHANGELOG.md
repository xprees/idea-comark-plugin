# Changelog

## [Unreleased]

### Added

- Comark language: `.mdc` files, and `.md` files in Nuxt Content / Nuxt MDC / Comark packages, are parsed as Comark on top of the
  bundled Markdown plugin (block components, nested components, YAML props blocks, slots, attributes)
- Settings page: which Markdown files are Comark, formatter and component completion options
- Syntax highlighting, inline Comark highlighting, colour settings and syntax inspections
- Formatter (port of the VS Code extension's) for Reformat Code, with matching indentation on Enter
- Folding, `::` pair matching, YAML in props blocks, live templates, auto-closing, breadcrumbs, structure view
- Component completion, navigation and documentation from Vue components and nuxt-component-meta
- Markdown plugin compatibility (HTML blocks, TODOs, Fill Paragraph) and components in the Markdown preview
- Code style page for Comark (indent 2 by default) and spellchecking of component content and attribute values
- Comark logo as the plugin icon and as the file icon of `.mdc` files
- Nuxt Content route links (`/guide/install#setup`) resolve to their content files: Go to Declaration, anchors in the target page
  and route completion

### Fixed

- Code fences indented inside components no longer log errors from Markdown's fence language injection
- Preview: components inside list items render as components instead of raw `::` text
- Preview: large documents with many link reference definitions render quickly again (references resolved once, not per block)
- Preview: repeated headings get unique ids across components (`usage`, `usage-1`), so in-page anchors reach every heading
- Preview: link reference definitions no longer show up inside an unclosed code fence or HTML block, with correct scroll positions
