# Dependency DAG repair (2026-10-05)

`/dependency-orchestrator` (role Developer, team of 2, web fullstack, model claude-sonnet-5-5) mapped the 99 stories
in `.scratch/annual-va/spec.stories.yaml` into 305 nodes and 1,301 edges (`project-dag.json`). The merged DAG failed
the story-level validation, so it was repaired by hand before `dag-processor.py` and the report ran:

1. **Exclusive node ownership.** The merge left 64 node/story ownerships duplicated across stories, including
   Release 2 stories owning Release 1 nodes, which made the release gating circular. Each node now has one owner:
   the lowest-numbered story in the earliest release that listed it. Story 68 (Not Endorsed at Cycle close) was
   left with nothing and took the leaf node `be_va_cycle_close_job` from story 17.
2. **Seventeen edges cut.** The derived story graph still had two-story cycles where one story's nodes sit between
   another story's nodes in the same module (for example the watermark service between the export service and its
   routes). For each cycle the weaker direction's path was cut at its last edge. The removed edges are:
   be_system_routes -> fe_system_page; be_system_smo_link_service -> be_system_routes;
   be_system_db_schema -> be_system_smo_reachability_service; be_system_data_access -> be_system_smo_reachability_service;
   be_system_register_impact_service -> be_system_register_import_routes;
   be_system_retirement_confirm_service -> be_system_register_merge_routes;
   be_va_submission_withdrawal_service -> be_system_register_merge_routes; be_system_routes -> fe_system_list;
   fe_system_page -> fe_submission_page; be_burp_finding_service -> be_finding_ingest_service;
   fe_review_inbox_page -> fe_finding_review_page; be_finding_review_routes -> fe_review_blocked_widget;
   fe_finding_review_page -> fe_review_blocked_widget; be_scan_validation_service -> be_document_routes;
   fe_va_board_list -> fe_va_cycle_page; fe_va_matrix_widget -> fe_va_cycle_page;
   be_report_service -> be_export_watermark_service.

The cuts affect only the derived story ordering and effort attribution in `reports/dev-report.*`; the build order
in `docs/implementation-plan.md` (section 7) remains the authoritative sequence for the tickets.
`project-dag.before-dedupe.json` is the merged DAG before the repair.
