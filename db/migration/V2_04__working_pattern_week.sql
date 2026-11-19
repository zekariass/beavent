-- Rotations longer than a week (repeat_weeks > 1) need to know which week an entry belongs to: week 1 is the week
-- (Monday to Sunday) containing effective_from, week 2 the next one, and so on, repeating every repeat_weeks.
ALTER TABLE team.working_patterns
  ADD COLUMN week_index smallint NOT NULL DEFAULT 1,
  ADD CONSTRAINT working_patterns_week_index_check CHECK (week_index BETWEEN 1 AND repeat_weeks);
CREATE INDEX working_patterns_staff_idx ON team.working_patterns (staff_id, effective_from);
