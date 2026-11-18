--liquibase formatted sql

--changeset poc:2
-- Add at_risk column to v_user_progress.
--
-- Logic:
--   at_risk = true  when  percent < 50  AND  deadline < current_date + 7
--             (fewer than 7 days to deadline, less than half complete)
--
-- Boundary cases handled correctly:
--   • Overdue learners  (deadline < current_date)       → at_risk = true  (< current_date + 7)
--   • Null deadline                                      → at_risk = false (NULL comparison is false)
--   • Exactly 7 days away (deadline = current_date + 7) → at_risk = false (not < current_date + 7)
--   • 100% complete, deadline < 7 days                  → at_risk = false (percent not < 50)
--
-- The original v_user_progress was created by changeset poc:1 with no IF EXISTS guard,
-- so we drop and recreate it. The view has no dependents (only used by get_user_progress tool).
DROP VIEW IF EXISTS v_user_progress;

CREATE VIEW v_user_progress AS
  SELECT
    p.tenant_id,
    p.user_id,
    c.name AS course_name,
    p.percent,
    p.deadline,
    CASE
      WHEN p.percent < 50
       AND p.deadline < current_date + 7
      THEN true
      ELSE false
    END AS at_risk
  FROM user_progress p
  JOIN course c ON c.tenant_id = p.tenant_id AND c.id = p.course_id;
