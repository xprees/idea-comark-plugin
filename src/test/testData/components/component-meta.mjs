export default {
  "ProseA": {
    "mode": "all",
    "prefetch": false,
    "preload": false,
    "filePath": "node_modules/@nuxtjs/mdc/dist/runtime/components/prose/ProseA.vue",
    "pascalName": "ProseA",
    "kebabName": "prose-a",
    "chunkName": "components/prose-a",
    "shortPath": "node_modules/@nuxtjs/mdc/dist/runtime/components/prose/ProseA.vue",
    "export": "default",
    "priority": 0,
    "fullPath": "/home/someone/site/node_modules/@nuxtjs/mdc/dist/runtime/components/prose/ProseA.vue",
    "meta": {
      "type": 1,
      "props": [
        {
          "name": "href",
          "global": false,
          "description": "",
          "tags": [],
          "required": false,
          "type": "string | undefined",
          "declarations": [
            { "file": "/home/someone/site/node_modules/@nuxtjs/mdc/dist/runtime/components/prose/ProseA.vue", "range": [205, 251] }
          ],
          "schema": { "kind": "enum", "type": "string | undefined", "schema": { "0": "undefined", "1": "string" } },
          "default": "\"\""
        },
        {
          "name": "target",
          "global": false,
          "description": "",
          "tags": [],
          "required": false,
          "type": "\"_blank\" | \"_parent\" | \"_self\" | \"_top\" | (string & object) | null | undefined",
          "declarations": [],
          "schema": { "kind": "enum", "type": "...", "schema": ["undefined", "null", "\"_blank\""] },
          "default": "undefined"
        },
        {
          "name": "key",
          "global": true,
          "description": "",
          "tags": [],
          "required": false,
          "type": "PropertyKey | undefined",
          "declarations": [],
          "schema": { "kind": "enum", "type": "PropertyKey | undefined", "schema": ["undefined", "string", "number", "symbol"] }
        }
      ],
      "slots": [
        { "name": "default", "type": "{}", "description": "", "declarations": [], "schema": { "kind": "object", "type": "{}", "schema": {} } }
      ],
      "events": [],
      "exposed": [
        { "name": "$slots", "type": "Readonly<InternalSlots> & { default?(_: {}): any; }", "description": "", "declarations": [], "schema": "Readonly<InternalSlots> & { default?(_: {}): any; }" }
      ]
    }
  },
  "Alert": {
    "mode": "all",
    "global": true,
    "filePath": "components/content/Alert.vue",
    "pascalName": "Alert",
    "kebabName": "alert",
    "chunkName": "components/alert",
    "shortPath": "components/content/Alert.vue",
    "fullPath": "/does/not/exist/components/content/Alert.vue",
    "meta": {
      "type": 1,
      "props": [
        {
          "name": "type",
          "global": false,
          "description": "Visual style of the alert",
          "tags": [ { "name": "default", "text": "'info'" } ],
          "required": false,
          "type": "\"info\" | \"warning\" | \"danger\" | undefined",
          "declarations": [],
          "schema": { "kind": "enum", "type": "\"info\" | \"warning\" | \"danger\" | undefined", "schema": ["undefined", "\"info\"", "\"warning\"", "\"danger\""] }
        },
        {
          "name": "title",
          "global": false,
          "description": "",
          "tags": [],
          "required": true,
          "type": "string",
          "declarations": [],
          "schema": "string"
        },
        {
          "name": "dismissible",
          "global": false,
          "description": "",
          "tags": [],
          "required": false,
          "type": "boolean | undefined",
          "declarations": [],
          "schema": { "kind": "enum", "type": "boolean | undefined", "schema": ["undefined", "false", "true"] },
          "default": "false"
        }
      ],
      "slots": [
        { "name": "default", "type": "{}", "description": "Alert body", "declarations": [], "schema": {} },
        { "name": "icon", "type": "{}", "description": "", "declarations": [], "schema": {} }
      ],
      "events": [ { "name": "close", "type": "[]", "signature": "(event: \"close\"): void", "declarations": [], "schema": [] } ],
      "exposed": []
    }
  }
};
