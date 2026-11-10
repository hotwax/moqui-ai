# Upgrade Steps

## Remove ALL_USERS artifact authorization and the AI_OPERATOR user group

`data/SetupData.xml` no longer seeds the AI Ops artifact groups that granted access to every
authenticated user (`ALL_USERS`). Access to MCP-exposed artifacts now comes from the
`MCP_DISPATCH` group, whose `ai.mcp.McpServices.dispatch#Request` member has
`inheritAuthz="Y"`, so the authz cascades to every nested artifact a dispatched tool calls.
`data/McpSecurityData.xml` was folded into `data/SetupData.xml` and deleted.

Removed from seed data:

| Entity | IDs |
|---|---|
| `ArtifactAuthz` | `AI_OPS_ALL_USERS`, `AI_OPS_DATA_READ_ALL`, `AI_OPS_DATA_DOCUMENT_RW_ALL` |
| `ArtifactGroup` (+ its `ArtifactGroupMember` rows) | `AI_OPS_SCREENS`, `AI_OPS_DATA_READ`, `AI_OPS_DATA_DOCUMENT_RW` |
| `UserGroup` | `AI_OPERATOR` |

An ext-seed load only upserts rows. It never deletes them, so on an existing environment these
rows (and the `ALL_USERS` grants they carry) stay in the database until they are removed by hand.

1. Deploy this release and reload ext-seed data, so the `MCP_DISPATCH` `ARTIFACT_GROUP`,
   `ARTIFACT_GROUP_MEMBER` and `McpServicesADMIN` `ARTIFACT_AUTHZ` rows from
   `data/SetupData.xml` are in place before the old grants are removed.
2. Check for members of `AI_OPERATOR` before you delete the group:

   ```sql
   SELECT * FROM USER_GROUP_MEMBER WHERE USER_GROUP_ID = 'AI_OPERATOR';
   ```

   The group was declared but never populated by seed data, so this is normally empty. If a
   deployment granted it to users, those `USER_GROUP_MEMBER` rows block the `USER_GROUP`
   delete (foreign key). Remove them first, or move those users to `ADMIN` if they still need to
   drive or approve other users' AI conversations: `approve#ToolCallRequest` and
   `run#Conversation` honor `AI_OPERATOR` or `ADMIN`.
3. Run the statements in [`UpgradeSQL.sql`](UpgradeSQL.sql) in the order given
   (`ARTIFACT_AUTHZ` → `ARTIFACT_GROUP_MEMBER` → `ARTIFACT_GROUP` → `USER_GROUP`), so that no
   row is deleted while another row still references it.
4. Skip steps 2–3 on a fresh install, which never had these rows.

Verify afterward:

```sql
SELECT ARTIFACT_AUTHZ_ID FROM ARTIFACT_AUTHZ WHERE ARTIFACT_GROUP_ID LIKE 'AI_OPS_%'; -- must be empty
SELECT ARTIFACT_GROUP_ID FROM ARTIFACT_GROUP WHERE ARTIFACT_GROUP_ID LIKE 'AI_OPS_%'; -- must be empty
SELECT USER_GROUP_ID FROM USER_GROUP WHERE USER_GROUP_ID = 'AI_OPERATOR'; -- must be empty
SELECT ARTIFACT_AUTHZ_ID FROM ARTIFACT_AUTHZ WHERE ARTIFACT_GROUP_ID = 'MCP_DISPATCH'; -- must return McpServicesADMIN
```
