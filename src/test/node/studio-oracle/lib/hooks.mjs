// module.register() resolve hook: redirects the two Nuxt-only specifiers that nuxt-studio's
// document utilities import. Every other module (including all Studio files) loads unmodified
// from node_modules.
const here = new URL('.', import.meta.url)
const NUXT_CONTENT_RUNTIME = new URL('nuxt-content-runtime.mjs', here).href
const USE_META = new URL('use-meta.mjs', here).href

export async function resolve(specifier, context, nextResolve) {
  if (specifier === '@nuxt/content/runtime') {
    return { url: NUXT_CONTENT_RUNTIME, shortCircuit: true }
  }
  const parent = context.parentURL || ''
  if (parent.includes('/node_modules/nuxt-studio/') && /(^|\/)composables\/useMeta\.js$/.test(specifier)) {
    return { url: USE_META, shortCircuit: true }
  }
  return nextResolve(specifier, context)
}
