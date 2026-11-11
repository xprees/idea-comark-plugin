---
title: T
author: Me
---

Hello {{ $doc.title }} by {{ $doc.author || 'nobody' }}.

::card{:items='["a","b"]' :obj='{"k":1}' :num="1"}
Bound
::
