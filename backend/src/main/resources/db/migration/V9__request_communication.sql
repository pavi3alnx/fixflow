-- Nullable for legacy attachments; new repair uploads are tied to their work episode.
ALTER TABLE public.attachments ADD COLUMN assignment_id BIGINT REFERENCES public.assignments(id) ON DELETE RESTRICT;
-- Legacy notifications remain readable. New producers use stable logical event keys.
ALTER TABLE public.notifications ADD COLUMN event_key VARCHAR(100);
CREATE UNIQUE INDEX uq_notifications_event_recipient ON public.notifications(recipient_id,event_key) WHERE event_key IS NOT NULL;
