-- The management summary as a PDF (ticket 45, stories 81 and 86, ADR 0053): an export may be made as XLSX or PDF. The
-- PDF is made from the same stored content as the workbook; nothing else about export_request changes.
ALTER TABLE export_request DROP CONSTRAINT export_request_format_check;
ALTER TABLE export_request ADD CONSTRAINT export_request_format_check CHECK (format IN ('XLSX', 'PDF'));
