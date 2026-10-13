# Return synchronization diagnostic playbook

## Required starting identifier

Prefer the OMS return ID. If only an external identifier is available, resolve it through ReturnIdentification first. Relevant identifiers include Shopify return ID, Shopify refund ID, AfterShip return ID, and AfterShip warranty RMA number.

## Investigation sequence

1. Read ReturnHeader and determine source channel, status, type, customer, and destination facility.
2. Read every ReturnItem, including cancelled lines, and separately identify active lines.
3. Read ReturnAdjustments because taxes, discounts, shipping, and appeasements may not be represented only by items.
4. Read ReturnItemResponses and their OrderPaymentPreferences to identify finalized refund outcomes.
5. Read original and replacement orders. Do not assume the first linked order is the correct exchange order.
6. Read ShipmentReceipts to determine whether inventory was physically recorded in OMS.
7. Read NetSuite header and item history to determine which remote documents have already been recorded.
8. Check RMA, Item Receipt, settlement, and IR back-sync eligibility independently.
9. For warranties, read the stored AfterShip webhook message sequence to confirm which source events were received and consumed.
10. Read recent `AlcNetSuiteReturnSyncError` messages for the exact remote or validation failure.
11. Query live NetSuite only when stored history and OMS evidence cannot prove the remote state.

## Expected waiting states

- Active no-proof-of-purchase warranty hold.
- Warranty or AfterShip return not completed when settlement requires completion.
- Exchange credit present but replacement order not linked.
- NetSuite-managed store Item Receipt waiting for the IR back-sync job.
- Ordinary AfterShip warehouse auto-IR disabled by product-store setting.
- Requested return waiting for source completion or physical receipt.

These are not retryable technical failures unless the prerequisite has remained absent beyond the expected business process.

## Defect indicators

- History is `SYNCED` while an expected artifact ID is null.
- RMA exists but item-level history was never stored.
- Credit Memo exists but required Customer Refund or invoice is absent.
- An exchange Credit Memo customer differs from the exchange invoice customer.
- The return is expected to be actionable but appears in none of the eligibility views.
- Item Receipt exists in NetSuite but cannot be matched to the stored RMA.
- A kit parent carries inventory detail.
- A damaged line was received into a sellable store location.
- The same replacement order is linked to multiple active returns.
- One reason or location was copied across all lines of a mixed-item return.
- A sellable return was received at the primary warehouse damage location instead of its store.
- A blind return has a replacement order or warranty identifier but related NetSuite documents have no eTail reconciliation identifier.
- A later AfterShip claim lost its original-order linkage because an earlier duplicate or cancelled claim still owns the return-item association.

## Common production explanations

- A `CANCEL` restock entry may be releasing an exchange-item commitment; it is not proof of an on-hand increase.
- A warranty can be visible in OMS without a Shopify inventory movement because AfterShip warranties do not post returned-item inventory to Shopify.
- An old Shopify SKU without a NetSuite product mapping blocks the corresponding return and exchange accounting flow.
- An exchange order can wait because its credit amount is lower than the order total and no approved treatment exists for the remaining balance.
- Missing Shopify store-credit refund transactions leave OMS without the settlement evidence needed to classify and post the return safely.

## Evidence standards

- State every conclusion with the exact return ID and relevant order or NetSuite record IDs.
- Include the ReturnItemResponse ID and OrderPaymentPreference ID when describing a refund outcome.
- Include payment method, status, amount, and created date.
- Include destination facility and resolved NetSuite location when explaining inventory.
- Distinguish stored history from a live NetSuite verification.
- Treat client reports as authoritative descriptions of the business concern, then verify the technical cause from line-level data.
- When Slack decisions conflict, use the latest explicit client confirmation and identify any unresolved question.
- Do not claim a remote document exists only because the scenario expects it.
- Do not expose credentials, OAuth signatures, tokens, or secrets.

## Response format

Use this order in a human-facing explanation:

1. `What this return is`: source and business scenario.
2. `What should happen`: expected NetSuite documents and why.
3. `What has happened`: actual OMS and NetSuite history evidence.
4. `Why it is waiting or failing`: one precise blocker.
5. `What to check next`: the smallest safe corrective action.

Avoid implementation-language explanations unless the user specifically asks for code details.
