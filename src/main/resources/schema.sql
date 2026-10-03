CREATE TABLE IF NOT EXISTS voucher (
    id        BIGSERIAL PRIMARY KEY,
    code      VARCHAR(64) NOT NULL UNIQUE,
    title     VARCHAR(255) NOT NULL,
    remaining INT NOT NULL,
    -- last line of defence against overselling, even if app code is buggy
    CONSTRAINT chk_voucher_remaining_non_negative CHECK (remaining >= 0)
);

CREATE TABLE IF NOT EXISTS redemption (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    voucher_id  BIGINT NOT NULL REFERENCES voucher(id),
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT now()
    -- TODO: decide: UNIQUE (user_id, voucher_id)? (one redemption per user per voucher) and document why / why not
);

-- TODO: index to support "history of a user" queries, e.g. (user_id, redeemed_at)

-- Seed data so GET /vouchers returns something
INSERT INTO voucher (code, title, remaining)
VALUES ('WELCOME10', '10% off', 100), ('FREESHIP', 'Free shipping', 50), ('LAST1', 'Only one left', 1)
ON CONFLICT (code) DO NOTHING;
