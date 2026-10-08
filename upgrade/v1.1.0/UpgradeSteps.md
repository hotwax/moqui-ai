# Upgrade Steps

## Rename AiToolCallRequestStatus / AiToolCallRequestFlow

`data/AiStatusData.xml` renamed the `StatusType` `AiToolCallRequestStatus` (23 chars) to
`AiToolCallReqStatus` (19 chars) and the `StatusFlow` `AiToolCallRequestFlow` (21 chars) to
`AiToolCallReqFlow` (17 chars), so every `statusTypeId`/`statusFlowId` in this component stays
at or under 19 characters. The `AI_TCREQ_PENDING`, `AI_TCREQ_APPROVED` and `AI_TCREQ_REJECTED`
`StatusItem` rows keep their `statusId` (PK) unchanged - only their `statusTypeId` column moves
to the new type.

1. Deploy this release and reload ext-seed data (`data/AiStatusData.xml`), so the
   `AI_TCREQ_*` `STATUS_ITEM` rows are upserted to point at `AiToolCallReqStatus` and the
   `AiToolCallReqFlow` `STATUS_FLOW`/`STATUS_FLOW_TRANSITION` rows are created.
2. Run the three `DELETE FROM STATUS_...` statements in
   [`UpgradeSQL.sql`](UpgradeSQL.sql) to remove the orphaned `AiToolCallRequestStatus`/
   `AiToolCallRequestFlow` rows an ext-seed load never deletes on its own. Run them in the order
   given (`STATUS_FLOW_TRANSITION` → `STATUS_FLOW` → `STATUS_TYPE`) and only after step 1, so the
   `STATUS_ITEM` rows already reference the new type before the old one is removed.
3. Skip this on a fresh install - it never had the old names.

Verify afterward:

```sql
SELECT STATUS_TYPE_ID FROM STATUS_TYPE WHERE STATUS_TYPE_ID LIKE 'AiToolCallRequest%'; -- must be empty
SELECT STATUS_TYPE_ID FROM STATUS_ITEM WHERE STATUS_ID LIKE 'AI_TCREQ_%'; -- must all read AiToolCallReqStatus
```
