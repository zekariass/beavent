-- Direct-to-storage uploads (plan step 0.9): a file row is created with a pre-signed upload URL, the client
-- uploads, then confirms. Processing (malware scan, content check, image variants) runs asynchronously.
ALTER TABLE platform.files
  ADD COLUMN original_name text CHECK (original_name IS NULL OR length(original_name) <= 200),
  ADD COLUMN uploaded_at   timestamptz,
  ADD COLUMN processed_at  timestamptz,
  ADD COLUMN variants      jsonb NOT NULL DEFAULT '{}'::jsonb;
COMMENT ON COLUMN platform.files.uploaded_at IS 'Set when the client confirmed the upload and its size matched; NULL = upload pending.';
COMMENT ON COLUMN platform.files.variants IS 'Storage keys of derived images by name (w320, w960), in the same bucket as the file.';
CREATE INDEX files_pending_upload_idx ON platform.files (created_at) WHERE uploaded_at IS NULL AND deleted_at IS NULL;
