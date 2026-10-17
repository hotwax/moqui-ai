# Upgrade Steps

## MCP grant, tool-call audit source and denylist now apply on upgrade

`upgrade/UpcomingRelease/UpgradeData.xml` now carries the rows that `data/SetupData.xml` and
`data/AiStatusData.xml` (both `ext-seed`) were the only source for: the `MCP_DISPATCH` artifact
group with its `McpServicesADMIN` grant, the `AiToolCallSource` enumerations, and the five
`AiToolDenylist` rows. The component upgrade runner applies them, so an instance no longer needs a
manual `ext-seed` load to run authenticated MCP tools.

No action is needed when upgrading through the runner. An instance that skipped the `ext-seed`
load on an earlier release (every authenticated MCP tool answers "not authorized for this tool",
`AI_TOOL_CALL` stays empty, `AI_TOOL_DENYLIST` is empty) gets the rows on this upgrade.

Verify afterward:

```sql
SELECT ARTIFACT_AUTHZ_ID FROM ARTIFACT_AUTHZ WHERE ARTIFACT_GROUP_ID = 'MCP_DISPATCH'; -- must return McpServicesADMIN
SELECT COUNT(*) FROM AI_TOOL_DENYLIST; -- at least 5
SELECT ENUM_ID FROM ENUMERATION WHERE ENUM_TYPE_ID = 'AiToolCallSource'; -- AI_TCS_AGENT, AI_TCS_MCP
```
