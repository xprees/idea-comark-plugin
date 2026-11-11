---
title: Structures
navigation:
  title: Nav
  icon: i-x
seo:
  title: SEO title
  description: SEO description
anchors:
  base: &base
    a: 1
  derived:
    <<: *base
    b: 2
nestedList:
  - name: one
    items: [1, 2]
  - name: two
    items:
      - deep: true
':colon': key starting with colon
"quoted key": v
123: numeric key
---

Body.
