// Stand-in for nuxt-studio 1.8.0 dist/module/runtime/composables/useMeta.js (a Vue/Nuxt composable
// that fetches /__nuxt_studio/meta). utils/document/generate.js only reads highlightTheme.value.
// The value is what server/routes/meta.js:30 returns when no mdc highlight theme is configured
// (the default for a Nuxt Content project): { default: github-light, dark: github-dark, light: github-light }.
const meta = {
  highlightTheme: { value: { default: 'github-light', dark: 'github-dark', light: 'github-light' } },
  markdownConfig: { value: {} },
}
export function useHostMeta() {
  return meta
}
