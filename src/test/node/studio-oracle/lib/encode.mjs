// JSON encoding of the JavaScript values in a comark tree, shared by record.mjs and record-vectors.mjs and read back by
// the Kotlin tests (StudioJson):
//
// - values JSON cannot hold become {"$type": "date" | "number" | "undefined" | "bigint", "value": ...};
// - an array or plain object reached more than once from the encoded root (YAML anchors and aliases load as one shared JS
//   object, which js-yaml's dump writes as `&ref_N` / `*ref_N`) is written in full once, as
//   {"$type": "shared", "id": N, "value": ...}, and as {"$type": "ref", "id": N} everywhere else, in depth-first order.
//   Cycles are encoded the same way.
const isContainer = (v) => v !== null && typeof v === 'object' && !(v instanceof Date)

export function encode(root) {
  const seen = new Map()
  const count = (v) => {
    if (!isContainer(v)) return
    const n = seen.get(v) || 0
    seen.set(v, n + 1)
    if (n === 0) for (const x of Array.isArray(v) ? v : Object.values(v)) count(x)
  }
  count(root)
  const ids = new Map()
  const enc = (v) => {
    if (v instanceof Date) return { $type: 'date', value: Number.isNaN(v.getTime()) ? 'Invalid Date' : v.toISOString() }
    if (typeof v === 'number' && !Number.isFinite(v)) return { $type: 'number', value: String(v) }
    if (typeof v === 'number' && Object.is(v, -0)) return { $type: 'number', value: '-0' }
    if (v === undefined) return { $type: 'undefined' }
    if (typeof v === 'bigint') return { $type: 'bigint', value: String(v) }
    if (!isContainer(v)) return v
    const plain = () => {
      if (Array.isArray(v)) return v.map(enc)
      const o = {}
      for (const [k, x] of Object.entries(v)) o[k] = enc(x)
      return o
    }
    if (seen.get(v) > 1) {
      if (ids.has(v)) return { $type: 'ref', id: ids.get(v) }
      const id = ids.size
      ids.set(v, id)
      return { $type: 'shared', id, value: plain() }
    }
    return plain()
  }
  return enc(root)
}

const isSpecial = (v, keys) => typeof v.$type === 'string' && Object.keys(v).every((k) => keys.includes(k))

export function decode(root) {
  const shared = new Map()
  const dec = (v) => {
    if (Array.isArray(v)) return v.map(dec)
    if (!v || typeof v !== 'object') return v
    if (isSpecial(v, ['$type', 'value'])) {
      if (v.$type === 'date') return new Date(v.value)
      if (v.$type === 'number') return Number(v.value)
      if (v.$type === 'undefined') return undefined
      if (v.$type === 'bigint') return BigInt(v.value)
    }
    if (v.$type === 'ref' && isSpecial(v, ['$type', 'id'])) return shared.get(v.id)
    if (v.$type === 'shared' && isSpecial(v, ['$type', 'id', 'value'])) {
      const target = Array.isArray(v.value) ? [] : {}
      shared.set(v.id, target)
      if (Array.isArray(v.value)) for (const x of v.value) target.push(dec(x))
      else for (const [k, x] of Object.entries(v.value)) target[k] = dec(x)
      return target
    }
    const o = {}
    for (const [k, x] of Object.entries(v)) o[k] = dec(x)
    return o
  }
  return dec(root)
}
