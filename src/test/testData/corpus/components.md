---
title: Components
navigation:
  title: Components
---

# Block

::alert{type="info"}
This is an important message!
::

::divider
::

::card{title="My Card" .featured #main bool}
Card content with **markdown** support

```js [file.js]
::not-a-component
```
::

## Inline

Check out this :icon-star component in the middle of text.
Click the :button[Submit]{type="primary"} to continue.
This is [highlighted text]{.highlight .yellow} in a paragraph.

::component
```yaml [props]
title: My Component
items:
  - First item
```
Component content goes here
::

::card
#header
## Card Title

#content
This is the main content of the card

#footer
Footer text here
::

::outer-component
Content in outer

:::inner-component{variant="compact"}
Content in inner
:::

More content in outer
::

::card{title="Hello" variant="primary"}
  :::badge{:color="props.variant" :text="props.title"}
  :::
::

::unclosed
still inside
