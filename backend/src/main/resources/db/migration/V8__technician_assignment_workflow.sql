-- Distinguish administrative suspension from normal pending email verification.
ALTER TABLE public.users ADD COLUMN administratively_disabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 CHECK (token_version >= 0);
-- Keep the reason on the ended episode even when reassignment does not change status.
ALTER TABLE public.assignments ADD COLUMN reassignment_reason TEXT;
ALTER TABLE public.assignments ADD CONSTRAINT ck_assignment_reassignment_reason
    CHECK (reassignment_reason IS NULL OR (length(btrim(reassignment_reason)) BETWEEN 5 AND 2000));
-- Attachments are created by the later upload workflow; FF-08 only references existing evidence.
CREATE TABLE public.assignment_evidence (
    assignment_id BIGINT NOT NULL REFERENCES public.assignments(id) ON DELETE RESTRICT,
    attachment_id BIGINT NOT NULL UNIQUE REFERENCES public.attachments(id) ON DELETE RESTRICT,
    PRIMARY KEY (assignment_id, attachment_id)
);
