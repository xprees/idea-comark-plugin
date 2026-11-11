---
title: Fences
---

```ts [nuxt.config.ts]
export default defineNuxtConfig({})
```

```vue [app.vue] {2,4-5}
<template>
  <div>
    <NuxtPage />
  </div>
</template>
```

```bash{1}
npm install
```

```json [package.json] {1,2,3,5}
{
  "a": 1,
  "b": 2,
  "c": 3,
  "d": 4
}
```

```diff [patch.diff] meta=value other
- a
+ b
```

```md
::card
inside fence
::
```

```
```

```js
// trailing blank lines

```
