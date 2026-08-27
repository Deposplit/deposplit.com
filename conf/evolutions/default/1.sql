# --- !Ups

CREATE TYPE share_transaction_type AS ENUM ('deposit', 'retrieval', 'removal', 'inventory');
CREATE TYPE share_request_state    AS ENUM ('pending', 'approved', 'denied', 'withdrawn');

-- One row per share request of any type.
-- deposit, retrieval, and removal share a symmetric consent model: sender Alice requests
-- something of recipient Bob (deposit a share, send it back, or remove it), and Bob can approve
-- or deny. inventory is different: a holder-initiated *push*, not consent-gated (see below).
--
-- deposit:    Alice deposits a share for Bob. ciphertext is populated by Alice at creation,
--             delivered to Bob on approval and cleared from the relay. Bob can also deny. k/n
--             (see below) are required.
-- retrieval:  Alice asks Bob to return a share. Bob provides ciphertext on approval, stored
--             temporarily until Alice collects it. ciphertext is NULL at creation.
-- removal:    Alice asks Bob to delete his local copy. No ciphertext involved.
-- inventory:  Bob (a holder) pushes a metadata-only report about a share of his back to its
--             owner, so a recovering Alice (fresh device, empty local state) can rebuild her
--             records — see docs/trust-model.md. No ciphertext
--             (never carries share bytes) but k/n are required. Not consent-gated: created
--             directly in 'approved' state (no pending phase), and the recipient polls for it and
--             deletes the row once consumed.
--
-- The 'withdrawn' state applies only to deposit rows: when a holder unilaterally stops
-- holding a share, the relay flips that row to 'withdrawn' instead of hard-deleting it, so the
-- sender's next poll can observe the tombstone. It is a best-effort, fire-and-forget courtesy,
-- not authoritative — the relay may still garbage-collect the row at any time, so its absence
-- must never be read as a signal, only an explicitly observed 'withdrawn' row counts. See
-- ShareRequests.withdrawShareRequests and docs/protocol.md.
--
-- share_id is NULL for deposit and inventory rows (both are roots). For retrieval and removal
-- rows it carries the id of the originating deposit request, supplied by the client. The relay
-- stores it opaquely without enforcing a foreign key (stateless relay design).
--
-- k and n are the SSS threshold/share-count populated for deposit and inventory only (NULL for
-- retrieval/removal), reported by holders during recovery as a cross-holder
-- consistency check. Signed as part of sender_signature.
--
-- sender_key and recipient_key are Ed25519 public keys (32 bytes).
-- secret_created_at is the client-supplied secret creation timestamp.
-- requested_at is the server-side timestamp when this request was opened.
--
-- sender_signature and recipient_signature are Ed25519 signatures (64 bytes) that ride with the
-- row so any reader (not just this relay) can independently re-verify authorship — required for
-- BYOR, since a third-party relay performs no verification of its own. sender_signature is set
-- at INSERT and never cleared. recipient_signature is NULL while pending, set on response (it
-- stays NULL for inventory rows — there is no response phase). See hexagons/relay's
-- PayloadCanonical for the exact bytes signed.
--
-- Note: partial unique indexes would add defence-in-depth but H2 does not support them.
-- The application layer (ShareRequestsService) enforces uniqueness constraints instead.
-- Add to production PostgreSQL separately:
--   CREATE UNIQUE INDEX uq_deposit_active
--       ON share_requests (secret_id, recipient_key)
--       WHERE transaction_type = 'deposit' AND state NOT IN ('denied', 'withdrawn');
--   CREATE UNIQUE INDEX uq_consent_pending
--       ON share_requests (secret_id, sender_key, recipient_key, transaction_type)
--       WHERE state = 'pending';
CREATE TABLE share_requests (
    id                UUID                     DEFAULT gen_random_uuid() PRIMARY KEY,
    secret_id         UUID                     NOT NULL,
    label             TEXT                     NOT NULL,
    sender_key        BYTEA                    NOT NULL,
    recipient_key     BYTEA                    NOT NULL,
    transaction_type  share_transaction_type   NOT NULL,
    state             share_request_state      NOT NULL DEFAULT 'pending',
    share_id          UUID,
    ciphertext        BYTEA,
    k                 INTEGER,
    n                 INTEGER,
    secret_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    responded_at      TIMESTAMP WITH TIME ZONE,
    sender_signature    BYTEA NOT NULL,
    recipient_signature BYTEA
);

CREATE INDEX ON share_requests (sender_key);
CREATE INDEX ON share_requests (recipient_key);
CREATE INDEX ON share_requests (secret_id);

-- The signed rotate(K_old -> K_new) push: a holder's proactive "I am now K_new, previously
-- K_old" notice, addressed to one contact at a time. Deliberately not a share_requests row: it
-- has no secret_id, no consent phase, and none of the share-specific columns above would ever be
-- populated, so it earns its own small table instead of growing share_requests' NULL-column
-- sprawl further.
--
-- old_verify_key is the trusted key the recipient already knows this contact by — both the
-- routing key and the key `signature` must verify against, proving continuity of key control.
-- new_verify_key/new_enc_key are the contact's new identity. new_cipher_suite (crypto
-- agility) is the signing + key-agreement algorithm pairing that identity uses (one value exists
-- today, ed25519+x25519-v1). No old_cipher_suite column — the recipient already has it pinned
-- on the existing contact record being rotated away from. signature is a signature by
-- old_verify_key's private key over (recipientKey || newVerifyKey || newEncKey ||
-- newCipherSuite) — see hexagons/relay's PayloadCanonical.forRotation for the exact bytes.
--
-- No state machine: like inventory, this is a fire-and-forget push, not a consent request. The
-- recipient polls, auto-verifies against the old key it already trusts, updates its local
-- contact record in place, and deletes the row once consumed.
CREATE TABLE key_rotations (
    id                UUID  DEFAULT gen_random_uuid() PRIMARY KEY,
    old_verify_key    BYTEA NOT NULL,
    recipient_key     BYTEA NOT NULL,
    new_verify_key    BYTEA NOT NULL,
    new_enc_key       BYTEA NOT NULL,
    new_cipher_suite  TEXT  NOT NULL,
    signature         BYTEA NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ON key_rotations (recipient_key);

-- The signed custodial-heartbeat push: a holder's proactive "still guarding {secretIds}
-- for you" notice (or, when opted_out is true, a signed "my silence from here on is not a loss
-- signal" notice), addressed to one owner at a time. Deliberately not a share_requests row (no
-- secret_id singular, no consent phase) and, unlike key_rotations/inventory, deliberately NOT
-- consumed-and-deleted: this table holds only the LATEST heartbeat per (holder_key, owner_key)
-- pair, upserted on every push, since it represents an ongoing "last seen" status rather than a
-- one-shot delivery. The owner's durable freshness/opt-out state lives on the owner's own
-- device, refreshed each time it observes this row — this table may be GC'd or pruned by
-- operators at any time without consequence (see hexagons/relay's CustodyHeartbeat and
-- docs/protocol.md).
--
-- secret_ids is a comma-joined list of UUID strings — opaque to the relay, which only stores and
-- forwards it. Kept as TEXT rather than a native array column for portability (H2/Postgres) and
-- consistency with how every other opaque payload on this table is stored.
--
-- signature is an Ed25519 signature by holder_key's private key over (ownerKey || sortedSecretIds
-- || optedOut) — see hexagons/relay's PayloadCanonical.forHeartbeat for the exact bytes.
CREATE TABLE custody_heartbeats (
    id           UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
    holder_key   BYTEA   NOT NULL,
    owner_key    BYTEA   NOT NULL,
    secret_ids   TEXT    NOT NULL,
    opted_out    BOOLEAN NOT NULL DEFAULT false,
    signature    BYTEA   NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (holder_key, owner_key)
);

CREATE INDEX ON custody_heartbeats (owner_key);

# --- !Downs

DROP TABLE custody_heartbeats;
DROP TABLE key_rotations;
DROP TABLE share_requests;

DROP TYPE share_request_state;
DROP TYPE share_transaction_type;
