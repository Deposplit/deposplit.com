# --- !Ups

CREATE TYPE share_request_type  AS ENUM ('pick_up', 'retrieve', 'delete');
CREATE TYPE share_request_state AS ENUM ('pending', 'approved', 'denied');

-- One row per share request of any type.
-- All three types use the same symmetric consent model: sender Alice requests something
-- of recipient Bob (pick up a share, send it back, or delete it), and Bob can approve or deny.
--
-- pick_up:  Alice deposits a share for Bob. ciphertext is populated by Alice at creation;
--           delivered to Bob on approval and cleared from the relay. Bob can also deny.
-- retrieve: Alice asks Bob to return a share. Bob provides ciphertext on approval;
--           stored temporarily until Alice collects it. ciphertext is NULL at creation.
-- delete:   Alice asks Bob to delete his local copy. No ciphertext involved.
--
-- share_id is NULL for pick_up rows (they are the root). For retrieve and delete rows it
-- carries the id of the originating pick_up request, supplied by the client. The relay
-- stores it opaquely without enforcing a foreign key (stateless relay design).
--
-- sender_key and recipient_key are Ed25519 public keys (32 bytes).
-- secret_created_at is the client-supplied secret creation timestamp.
-- requested_at is the server-side timestamp when this request was opened.
--
-- Note: partial unique indexes would add defence-in-depth but H2 does not support them.
-- The application layer (ShareRequestsService) enforces uniqueness constraints instead.
-- Add to production PostgreSQL separately:
--   CREATE UNIQUE INDEX uq_pick_up_active
--       ON share_requests (secret_id, recipient_key)
--       WHERE request_type = 'pick_up' AND state != 'denied';
--   CREATE UNIQUE INDEX uq_consent_pending
--       ON share_requests (secret_id, sender_key, recipient_key, request_type)
--       WHERE state = 'pending';
CREATE TABLE share_requests (
    id                UUID                     DEFAULT gen_random_uuid() PRIMARY KEY,
    secret_id         UUID                     NOT NULL,
    label             TEXT                     NOT NULL,
    sender_key        BYTEA                    NOT NULL,
    recipient_key     BYTEA                    NOT NULL,
    request_type      share_request_type       NOT NULL,
    state             share_request_state      NOT NULL DEFAULT 'pending',
    share_id          UUID,
    ciphertext        BYTEA,
    secret_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    responded_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ON share_requests (sender_key);
CREATE INDEX ON share_requests (recipient_key);
CREATE INDEX ON share_requests (secret_id);

# --- !Downs

DROP TABLE share_requests;

DROP TYPE share_request_state;
DROP TYPE share_request_type;
