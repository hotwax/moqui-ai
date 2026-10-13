# Client-confirmed return decisions

Last reviewed from Slack: September 12, 2026.

## How to use this record

This topic captures business decisions and production observations from client Slack discussions. It complements the code-based lifecycle topics; it does not replace live OMS or NetSuite evidence.

Apply this evidence order when statements conflict:

1. A later explicit client confirmation overrides an earlier proposal or assumption.
2. A client-confirmed business outcome explains what should happen.
3. Current code and live data explain what actually happened.
4. A support hypothesis is not a requirement until the client confirms it or the implementation is accepted.
5. An unresolved client question must be reported as open, not converted into a system rule.

Do not copy customer names, addresses, credentials, or raw transaction payloads into an answer. Use the return, order, RMA, Item Receipt, Credit Memo, Customer Refund, or invoice identifiers needed to support the explanation.

## Inventory routing decisions

### Online AfterShip returns

Confirmed on June 20-22, 2026:

- An online return is not considered physically restocked merely because the customer submitted it.
- Each return line is routed independently. One multi-item return can contain both damaged and non-damaged lines.
- Non-damaged reasons, including Other, route to the ECOM warehouse and the RMA REC BIN.
- A damaged reason routes to the damaged location mapped from that line's reason or subreason.
- A damaged item still requires an Item Receipt when it is physically received, but it must not be restocked into sellable Shopify inventory.
- The RMA line location and the Item Receipt destination are both business-significant. A correct RMA location does not prove that the receipt used the correct bin or damage sublocation.

Decision thread: [AfterShip online return routing](https://hotwaxcommerce.slack.com/archives/C06SGQL4J90/p1781892038076479)

### Store AfterShip returns

- When a store employee selects Restock, Shopify records the store inventory increase. OMS must import that state and must not write the same Shopify increase a second time.
- NetSuite still needs the RMA and Item Receipt at the corresponding store location.
- When the employee does not restock, Shopify does not increase sellable inventory. NetSuite receipt routing uses the line's damage mapping; an unmapped damaged line uses the configured primary damage location.
- A damage reason by itself does not prevent Shopify from restocking. Shopify follows the submitted restock choice, so an incorrectly selected Restock option can put a damaged item back into sellable stock.

Decision and production-validation thread: [Return restock and cancellation behavior](https://hotwaxcommerce.slack.com/archives/C06SGQL4J90/p1787758003111179)

### Warranties

- AfterShip warranty claims do not create a Shopify inventory movement for the damaged returned item.
- OMS must not infer a Shopify restock from a warranty claim.
- The currently confirmed Inventory Transfer scope is floor-damage warranties. Posting all warranty types as NetSuite Inventory Transfers is an open enhancement, not current behavior.
- Warranty damage-by-store reporting comes from OMS return data. It does not prove that Shopify has a reverse-fulfillment disposition or that NetSuite has an Inventory Transfer.

### Sellable returns

- A sellable return stays in the store's sellable inventory unless corporate later requests a pullback.
- It must not be routed to a warehouse damage location merely because it was created through the HG application.
- HG Packing List inclusion and NetSuite receipt location are separate questions. Packing-list visibility must not be used as proof of inventory destination.

Decision thread: [HG Packing List and sellable return discussion](https://hotwaxcommerce.slack.com/archives/C0BSSDQGHS6/p1780750310104329)

## Financial settlement decisions

### Store credit

Confirmed on May 29, 2026:

- The RMA records the intent to return merchandise.
- The Item Receipt records physical receipt and inventory impact.
- The Credit Memo reverses the returned merchandise value and tax.
- The store-credit invoice uses the Store Credit Issuance item. The HG Credit Memo item belongs on the Credit Memo when the accounting rule calls for it; it is not the store-credit invoice item.
- The Credit Memo is applied to the store-credit invoice so the financial documents settle each other.
- A no-order store-credit case can have a standalone Credit Memo and invoice without a NetSuite Sales Order.

Decision thread: [HG refund to store credit accounting](https://hotwaxcommerce.slack.com/archives/C0BSSDQGHS6/p1779994005894899)

### Exchange credit

- The exchange-order invoice is applied against the return Credit Memo.
- The Credit Memo and exchange invoice must use the same NetSuite customer.
- A warranty exchange must wait for the replacement order link before settlement; otherwise the Credit Memo can be created for the return customer instead of the exchange-order customer.
- A partially funded exchange is intentionally held when the exchange credit is lower than the exchange-order total, unless an approved business rule explains how the remaining balance will be settled.

### NetSuite eTail identifier

Confirmed on September 1-4, 2026:

- An order-linked replacement uses the original Shopify order eTail ID on the Credit Memo and invoice.
- A blind replacement with no original order uses the new exchange order's Shopify eTail ID.
- A blind store-credit return uses the AfterShip warranty number as the shared eTail identifier. If no warranty number exists, it uses the HotWax return identifier.
- The selected identifier must be present consistently on related NetSuite documents used for reconciliation.

Decision thread: [Blind return and exact replacement eTail mapping](https://hotwaxcommerce.slack.com/archives/C06SGQL4J90/p1788486533305879)

## Cancellation semantics

- A Shopify `CANCEL` inventory record can represent release of an exchange-item commitment. It does not automatically mean the returned item was added to on-hand inventory.
- For a cancelled return, prove inventory impact using on-hand and committed movements rather than the restock label alone.
- A return cancelled before receipt should not create a returned-item inventory adjustment in OMS.
- Duplicate or replacement AfterShip claims can leave stale OMS return-item associations when cancellation is not propagated. This can cause a later valid claim to lose original-order linkage and its eTail identifier.

## Known source-system limitations

- AfterShip can allow both a return and a warranty claim for the same order item. OMS generally permits one active return association for an item, so the two claims can compete and block or mis-link downstream processing.
- AfterShip store-credit behavior has not always produced a consistent Shopify refund transaction. When `refunds.transactions` does not contain the store-credit transaction, OMS lacks authoritative settlement evidence.
- Old Shopify products can be unavailable or missing a NetSuite product mapping. The return and exchange flow must not create partial NetSuite documents when any required returned product cannot be resolved.
- Free-text warranty reasons do not behave like normalized numeric return reasons. Diagnostics must inspect the actual mapped reason and location rather than assume a disposition was created.

## Open questions, not current requirements

- Whether every warranty type should create a NetSuite Inventory Transfer remains outside the currently confirmed floor-damage scope.
- Whether Shopify should receive a zero-quantity warranty disposition for traceability is a client request for future consideration.
- Whether the hourly OMS-to-Shopify inventory reset should be changed is an operational discussion, not a return-sync rule.
- The Inventory Flag application's exact source and refresh cadence must be verified independently; do not infer it from the return-sync job cadence.

## Diagnostic implications

When a client reports a mismatch:

1. Treat the report as a high-priority signal, but verify the exact line-level movements before assigning cause.
2. Compare the current implementation with the latest confirmed decision above.
3. For inventory, separate on-hand, available, and committed changes.
4. For financial settlement, compare the Credit Memo, Customer Refund, store-credit invoice, and exchange invoice as separate artifacts.
5. For multi-item returns, inspect each line's reason, location, quantity, product mapping, and settlement response independently.
6. State whether the behavior is expected, a source-system limitation, a configuration issue, or a code defect.
