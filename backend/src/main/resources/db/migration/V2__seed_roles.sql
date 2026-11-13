-- Reject incompatible mappings instead of replacing roles or changing IDs.
LOCK TABLE public.roles IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.roles r
        JOIN (VALUES (1::BIGINT, 'REPORTER'), (2::BIGINT, 'TECHNICIAN'),
                     (3::BIGINT, 'ADMIN')) AS expected(id, name)
          ON r.id = expected.id OR r.name = expected.name
        WHERE r.id <> expected.id OR r.name <> expected.name
    ) THEN
        RAISE EXCEPTION 'Existing role IDs/names conflict with FixFlow reference roles';
    END IF;
END $$;

-- Omit IDs so a fresh database advances its identity naturally. Existing correct
-- rows cause no inserts and no sequence operations. Unexpected sequence state
-- fails transactionally rather than rewriting IDs or resetting a sequence.
INSERT INTO public.roles(name)
SELECT expected.name
FROM (VALUES (1, 'REPORTER'), (2, 'TECHNICIAN'), (3, 'ADMIN')) expected(id, name)
WHERE NOT EXISTS (SELECT 1 FROM public.roles r WHERE r.name = expected.name)
ORDER BY expected.id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (VALUES (1::BIGINT, 'REPORTER'), (2::BIGINT, 'TECHNICIAN'),
                             (3::BIGINT, 'ADMIN')) expected(id, name)
        LEFT JOIN public.roles r ON r.id = expected.id AND r.name = expected.name
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Role identity sequence did not produce expected seed IDs; inspect sequence state';
    END IF;
END $$;
