ALTER TABLE postings ADD CONSTRAINT fk_postings_account
    FOREIGN KEY (account_id) REFERENCES accounts (id);
