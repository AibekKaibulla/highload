-- 1000 polls with 4 options each, for the load tests.
INSERT INTO polls (creator_id, question)
SELECT (g % 100000) + 1, 'Seed poll #' || g || ': which option do you like the most?'
FROM generate_series(1, 1000) AS g;

INSERT INTO options (poll_id, text, position)
SELECT p.poll_id, 'Option ' || k || ' of poll ' || p.poll_id, k - 1
FROM polls p CROSS JOIN generate_series(1, 4) AS k
WHERE p.question LIKE 'Seed poll #%';
