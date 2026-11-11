---
title: Aliases
defaults: &defaults
  layout: docs
  tags: &tags
    - a
    - b
page:
  <<: *defaults
  extra: *tags
list:
  - *tags
  - *defaults
  - &item
    x: 1
  - *item
---

# Shared YAML values

::card
---
a: &x
  k: 1
b: *x
items: &list [1, 2]
more: *list
---
Body
::
