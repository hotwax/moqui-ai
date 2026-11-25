# Update Existing OMS Products with Data Manager

Use this when someone asks to change fields on existing OMS products. A request like "mark these
SKUs as High Value" requires the active tenant's product classification rules. Look for the tenant's
knowledge topic first; do not assume an OMS field or modify another system.

1. Resolve every requested identifier to one existing OMS `productId`. Use `execute_query` from
   moqui-gql to read the current values. Use exact SKU lookup according to the tenant's mapping or
   an active typed product identification. `productByIdentification` alone does not check
   effective dates; use `goodIdentifications` to confirm them when they matter. Do not upload rows
   for missing or ambiguous matches.
2. Confirm the tenant has an update-only Product Data Manager config and that the requested fields
   belong to `Product`. Related records, such as identifiers or facility settings, require their own
   workflow. Confirm that the fields can be read through moqui-gql before promising verification.
3. Show the exact product IDs and current and requested values for approval. Include only
   `productId` and the approved fields in each row. Do not send blank values unless clearing the field
   was explicitly requested and the import supports it. Exclude rows that already match.
4. After approval, use the authorized `upload_data_manager_records` tool if available, or provide the
   rows for upload to the configured import in Maarg Data Manager. Record the import log ID. A
   queued upload is not a completed update. Use `get_data_manager_logs` or the Data Manager import
   history to check the run, and inspect the error file if there are rejected rows. Do not re-upload
   just because a run is still queued.
5. After processing, read each product again by `productId` using moqui-gql. Report which rows
   were verified, already correct, failed, did not match, or are still pending. If the field is not
   exposed or the read fails, say that it could not be verified. Notify the requester with the result
   and import log ID; do not claim that another system was updated.
