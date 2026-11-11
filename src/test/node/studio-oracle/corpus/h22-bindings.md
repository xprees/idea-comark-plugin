---
title: Bindings
author: Jane
tags: [x, y]
---

Hello {{ $doc.author }} and {{ $doc.tags[0] }} and {{missing || 'fallback'}}.

::user{:name="$doc.author" :age="30" :active="true" :tags='["a"]' :meta='{"k":"v"}' :expr="foo.bar" :str="'quoted'"}
Bound props
::

:badge{:count="5"}
