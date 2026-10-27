# --- !Ups

CREATE TYPE share_transaction_type AS ENUM ('deposit', 'retrieval', 'removal', 'inventory', 'withdrawal');
CREATE TYPE share_request_state    AS ENUM ('pending', 'approved', 'denied');

-- One row per share request of any type.
-- deposit, retrieval, and removal share a symmetric consent model: sender Alice requests
-- something of recipient Bob (deposit a share, send it back, or remove it), and Bob can approve
-- or deny. inventory and withdrawal are different: holder-initiated *pushes*, not consent-gated
-- (see below).
--
-- Every row is a message, deleted by the party it is addressed to once acted on, so no row
-- stands for a relationship. A picked-up deposit is deleted by Alice as soon as she has seen the
-- pickup, an answered retrieval or removal by Alice, a push by its recipient.
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
-- withdrawal: Bob tells Alice he has stopped holding a share, unilaterally. Created 'approved'
--             like inventory and deleted by Alice once consumed. No ciphertext, k, n or
--             mime_type. Signed by Bob like any other row, so a relay cannot invent one to make
--             Alice forget a holder who still has the share. See docs/protocol.md.
--
-- A retrieval or removal row carries no pointer to the deposit it follows, and needs none.
-- (secret_id, recipient_key) already identifies the share it is about. That is the pair the
-- uq_deposit_active index below enforces while a deposit is in flight, and the pair both
-- parties match on.
--
-- k and n are the SSS threshold/share-count populated for deposit and inventory only (NULL for
-- retrieval/removal/withdrawal), reported by holders during recovery as a cross-holder
-- consistency check. Signed as part of sender_signature.
--
-- mime_type follows the same rule: populated for deposit and inventory, NULL for the other three,
-- and signed as part of sender_signature. It is the sender's claim about what the secret is, kept so
-- the recipient can decide how to render it once reconstructed. The relay never interprets it and
-- could not check it if it wanted to, the payload being ciphertext. Unlike the label it is stored in
-- the clear. ShareRequestsService refuses a blank one, because PayloadCanonical renders NULL and the
-- empty string as the same signed bytes.
--
-- sealed_label is the secret's label, sealed by the sender to the recipient's encryption key with
-- the same box as a share, so the relay stores bytes it cannot read. Required on every type, because
-- the recipient shows it. Signed as part of sender_signature.
--
-- sender_key and recipient_key are Ed25519 public keys (32 bytes).
-- secret_created_at is the client-supplied secret creation timestamp.
-- requested_at is the server-side timestamp when this request was opened.
--
-- sender_signature and recipient_signature are Ed25519 signatures (64 bytes) that ride with the
-- row so any reader (not just this relay) can independently re-verify authorship — required for
-- BYOR, since a third-party relay performs no verification of its own. sender_signature is set
-- at INSERT and never cleared. recipient_signature is NULL while pending, set on response (it
-- stays NULL for inventory and withdrawal rows — there is no response phase). See hexagons/relay's
-- PayloadCanonical for the exact bytes signed.
--
-- Note: partial unique indexes would add defence-in-depth but H2 does not support them.
-- The application layer (ShareRequestsService) enforces uniqueness constraints instead.
-- Add to production PostgreSQL separately. (A semicolon anywhere in these comments splits the
-- script, so whatever follows one is parsed as SQL. The two below are safe only because each
-- falls at the end of its line, leaving the next fragment starting with a comment marker.)
--   CREATE UNIQUE INDEX uq_deposit_active
--       ON share_requests (secret_id, recipient_key)
--       WHERE transaction_type = 'deposit' AND state <> 'denied';
--   CREATE UNIQUE INDEX uq_consent_pending
--       ON share_requests (secret_id, sender_key, recipient_key, transaction_type)
--       WHERE state = 'pending';
CREATE TABLE share_requests (
    id                UUID                     DEFAULT gen_random_uuid() PRIMARY KEY,
    secret_id         UUID                     NOT NULL,
    sealed_label      BYTEA                    NOT NULL,
    sender_key        BYTEA                    NOT NULL,
    recipient_key     BYTEA                    NOT NULL,
    transaction_type  share_transaction_type   NOT NULL,
    state             share_request_state      NOT NULL DEFAULT 'pending',
    ciphertext        BYTEA,
    k                 INTEGER,
    n                 INTEGER,
    mime_type         TEXT,
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
-- secret_id singular, no consent phase). Like key_rotations it is a message: the owner deletes it
-- once read, keeping what it told her on her own device, and the holder re-emits every interval.
-- Until it is read, a newer push from the same holder to the same owner replaces it, so this
-- table holds at most one row per (holder_key, owner_key) pair. The replacement takes a fresh
-- id, so an owner deleting the row she read cannot delete one she has not (see hexagons/relay's
-- CustodyHeartbeat and docs/protocol.md). A row that is neither read nor superseded expires once
-- its created_at is older than relay.retention.custody-heartbeats in application.conf.
--
-- secret_ids is a comma-joined list of UUID strings — opaque to the relay, which only stores and
-- forwards it. Kept as TEXT rather than a native array column for portability (H2/Postgres) and
-- consistency with how every other opaque payload on this table is stored.
--
-- emitted_at is the holder's own clock at signing, and is signed. It is the time the owner dates
-- custody from. created_at is this relay's receipt time of the latest push, unsigned, and used
-- for expiry and by operators, never by the owner.
--
-- signature is an Ed25519 signature by holder_key's private key over (ownerKey || sortedSecretIds
-- || optedOut || emittedAt) — see hexagons/relay's PayloadCanonical.forHeartbeat for the exact bytes.
CREATE TABLE custody_heartbeats (
    id           UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
    holder_key   BYTEA   NOT NULL,
    owner_key    BYTEA   NOT NULL,
    secret_ids   TEXT    NOT NULL,
    opted_out    BOOLEAN NOT NULL DEFAULT false,
    emitted_at   TIMESTAMP WITH TIME ZONE NOT NULL,
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
