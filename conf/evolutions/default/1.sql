# --- !Ups

CREATE TYPE share_transaction_type AS ENUM ('deposit', 'retrieval', 'removal', 'inventory');
CREATE TYPE share_request_state    AS ENUM ('pending', 'approved', 'denied');

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
--             records — see deposplit.com/CLAUDE.md "What is next" item 8. No ciphertext
--             (never carries share bytes) but k/n are required. Not consent-gated: created
--             directly in 'approved' state (no pending phase), and the recipient polls for it and
--             deletes the row once consumed.
--
-- share_id is NULL for deposit and inventory rows (both are roots). For retrieval and removal
-- rows it carries the id of the originating deposit request, supplied by the client. The relay
-- stores it opaquely without enforcing a foreign key (stateless relay design).
--
-- k and n are the SSS threshold/share-count populated for deposit and inventory only (NULL for
-- retrieval/removal) — added by item 8, reported by holders during recovery as a cross-holder
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
--       WHERE transaction_type = 'deposit' AND state != 'denied';
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

# --- !Downs

DROP TABLE share_requests;

DROP TYPE share_request_state;
DROP TYPE share_transaction_type;
