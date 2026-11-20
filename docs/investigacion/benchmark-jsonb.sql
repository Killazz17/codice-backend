-- Benchmark for the paper: JSONB + GIN vs. sequential scan vs. normalized tables.
-- Run on a SCRATCH database (it creates its own tables, never touches Codice data).
-- Preferred: Cloud SQL (what production uses), through the Cloud SQL Auth Proxy:
--   cloud-sql-proxy codice-511601:us-central1:codice-db --port 5433
--   (create a separate db first, e.g. codice_bench, NOT the production db "codice")
--   psql -h 127.0.0.1 -p 5433 -U <user> -d codice_bench -f benchmark-jsonb.sql > resultados.txt
-- Alternative: local PostgreSQL (state it as a limitation in the paper):
--   createdb -U postgres codice_bench
--   psql -U postgres -d codice_bench -f benchmark-jsonb.sql > resultados.txt
-- Note: 200k rows on a db-f1-micro with 10 GB HDD is small but may take a few minutes.
-- Copy the "Execution Time" lines and the sizes into the evaluation tables of the paper.

\timing off
DROP TABLE IF EXISTS bench_finding, bench_note;

-- 200k notes; each audit holds 1-4 findings with a verdict and a confidence.
CREATE TABLE bench_note (
    id    BIGSERIAL PRIMARY KEY,
    audit JSONB NOT NULL
);

INSERT INTO bench_note (audit)
SELECT jsonb_build_object(
         'model', 'gemini', 'promptVersion', 'v1',
         'verdicts', (
           SELECT jsonb_agg(jsonb_build_object(
                    'subtopic', 1 + (random()*400)::int,
                    'verdict', (ARRAY['COVERED','PARTIAL','NO_NOTES'])[1 + (random()*2)::int],
                    'confidence', round(random()::numeric, 2)))
           FROM generate_series(1, 1 + (g % 4))))
FROM generate_series(1, 200000) g;

-- Same information, fully normalized (the relational alternative).
CREATE TABLE bench_finding (
    id BIGSERIAL PRIMARY KEY,
    note_id BIGINT NOT NULL REFERENCES bench_note(id),
    subtopic INT NOT NULL, verdict VARCHAR(20) NOT NULL, confidence NUMERIC(3,2) NOT NULL
);
INSERT INTO bench_finding (note_id, subtopic, verdict, confidence)
SELECT n.id, (v->>'subtopic')::int, v->>'verdict', (v->>'confidence')::numeric
FROM bench_note n, jsonb_array_elements(n.audit->'verdicts') v;
CREATE INDEX ON bench_finding (subtopic, verdict);
CREATE INDEX ON bench_finding (note_id);   -- FK lookups; without it the join read is unfairly slow
ANALYZE bench_note; ANALYZE bench_finding;

\echo '=== Q1: notes with verdict COVERED for subtopic 42 -- NO index (seq scan) ==='
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM bench_note
WHERE audit @> '{"verdicts":[{"subtopic":42,"verdict":"COVERED"}]}';

CREATE INDEX idx_bench_audit ON bench_note USING GIN (audit);
ANALYZE bench_note;
\echo '=== Q1: same query WITH GIN (default jsonb_ops) ==='
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM bench_note
WHERE audit @> '{"verdicts":[{"subtopic":42,"verdict":"COVERED"}]}';

\echo '=== Q1 relational equivalent (normalized table) ==='
EXPLAIN (ANALYZE, BUFFERS) SELECT DISTINCT note_id FROM bench_finding
WHERE subtopic = 42 AND verdict = 'COVERED';

\echo '=== Reading one whole document (what the mobile app does): JSONB vs join ==='
EXPLAIN (ANALYZE) SELECT audit FROM bench_note WHERE id = 100000;
EXPLAIN (ANALYZE) SELECT n.id, f.subtopic, f.verdict, f.confidence
FROM bench_note n JOIN bench_finding f ON f.note_id = n.id WHERE n.id = 100000;

\echo '=== Storage ==='
SELECT 'jsonb table (heap)' AS what, pg_size_pretty(pg_table_size('bench_note')) AS size
UNION ALL SELECT 'GIN index',        pg_size_pretty(pg_relation_size('idx_bench_audit'))
UNION ALL SELECT 'normalized table', pg_size_pretty(pg_total_relation_size('bench_finding'));

\echo '=== Write cost: insert 50k rows with and without GIN ==='
\timing on
INSERT INTO bench_note (audit) SELECT audit FROM bench_note LIMIT 50000;   -- with GIN
DROP INDEX idx_bench_audit;
INSERT INTO bench_note (audit) SELECT audit FROM bench_note LIMIT 50000;   -- without GIN
\timing off

DROP TABLE bench_finding, bench_note;
