// Checks that a freshly deployed relay answers, the last step of cleverCloud.yml.
//
//   node .github/scripts/smoke-check.mjs https://api.deposplit.com
//
// The relay has no health endpoint, so this asks it a real question instead: it lists the share
// requests for a key generated here and forgotten when the process ends. Nobody has ever sent
// anything to that key, so the answer is an empty JSON array, and getting one proves that the
// request was routed, that its signature was checked, and that the database answered the query
// behind it. An unsigned request would prove only the routing, since the relay refuses it before
// any query. Listing writes nothing: the relay does not store nonces.
//
// What it cannot prove is which build answered, because the relay does not say. It retries for a
// few minutes, so an instance that is still starting gets the time it needs.
//
// The signing string is AuthHelper's: nonce, method, path with query and hex SHA-256 of the body,
// joined by newlines, signed with Ed25519.

import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto'

const base = process.argv[2]
if (!base) {
  console.error('usage: node smoke-check.mjs <relay base URL>')
  process.exit(2)
}

const path = '/share-requests?role=recipient'
const deadline = Date.now() + 5 * 60 * 1000
const pause = 10 * 1000

const { publicKey, privateKey } = generateKeyPairSync('ed25519')
const verifyKey = publicKey.export({ format: 'jwk' }).x // the raw 32 bytes, base64url
const emptyBodyHash = createHash('sha256').update('').digest('hex')

async function attempt() {
  const nonce = `${Date.now()}.${randomBytes(8).toString('hex')}`
  const canonical = `${nonce}\nGET\n${path}\n${emptyBodyHash}`
  const signature = sign(null, Buffer.from(canonical, 'utf8'), privateKey).toString('base64url')
  const response = await fetch(base + path, {
    headers: {
      'X-Deposplit-Verify-Key': verifyKey,
      'X-Deposplit-Nonce': nonce,
      'X-Deposplit-Signature': signature,
    },
    signal: AbortSignal.timeout(15 * 1000),
  })
  const body = await response.text()
  let empty = false
  try {
    const parsed = JSON.parse(body)
    empty = Array.isArray(parsed) && parsed.length === 0
  } catch {}
  return { ok: response.status === 200 && empty, said: `${response.status} ${body.slice(0, 200)}` }
}

for (let round = 1; ; round++) {
  let outcome
  try {
    outcome = await attempt()
  } catch (error) {
    outcome = { ok: false, said: String(error.cause ?? error) }
  }
  console.log(`attempt ${round}: ${outcome.said}`)
  if (outcome.ok) {
    console.log(`${base} answers a signed request from its database.`)
    process.exit(0)
  }
  if (Date.now() + pause > deadline) {
    console.error(`${base} gave no empty list to a signed request within five minutes.`)
    process.exit(1)
  }
  await new Promise((resolve) => setTimeout(resolve, pause))
}
