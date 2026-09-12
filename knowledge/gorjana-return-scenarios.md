# Return synchronization scenarios

## Channel meanings

- `POS_RTN_CHANNEL`: return originated from Shopify POS.
- `AFTSHIP_RTN_CHANNEL`: return originated from the AfterShip return workflow for ecommerce or POS. A warranty may also use this channel when its identifier starts with `WARRANTY-`.
- `AFTSHP_OL_WTY`: standard AfterShip online warranty.
- `AFTSHP_NO_POP`: AfterShip warranty without proof of purchase.
- `AFTSHP_HG_LOSS`: lost or stolen Happiness Guarantee warranty.
- `AFTSHP_HG_REPAIR`: AfterShip repair warranty.
- `AFTSHP_HG_FLOOR`: floor-damage warranty.
- `AFTSHP_HG_EMPL`: employee Happiness Guarantee warranty.
- `AFTSHP_SCE_WTY`: Store Credit Exception warranty.
- `HAPPINESS_GUARANTEE`: return created through the Happiness Guarantee workflow.
- `SELLABLE_RETURN`: sellable merchandise return handled outside the normal Shopify POS return channel.
- `LOOP_RETURN_CHANNEL`: Loop-owned return. It is excluded from this NetSuite return synchronization.

## Standard Shopify POS return

- The return is associated with an original order.
- An RMA is expected.
- Receipt behavior depends on the inventory disposition and destination facility.
- Settlement waits for the completed return and its refunded payment response.
- Original-payment refunds require a Credit Memo and Customer Refund.
- Exchange credit must remain associated with the replacement order.

## AfterShip return

- The return can enter OMS as requested before the source workflow is completed.
- An RMA may be created while the return is requested.
- Financial settlement waits for completion and a resolved refund response.
- Warehouse Item Receipt creation is controlled by the `NS_AFTSHIP_RTN_WH_IR` product-store setting.
- A store no-restock return may wait for NetSuite receipt activity to be synchronized back to OMS.
- Online non-damaged lines route to ECOM/RMA REC BIN. Online damaged lines route to the damage location mapped from that line's reason or subreason.
- Each item keeps its own reason and location in a mixed return.

## Standard warranty

- A non-lost warranty follows the RMA and Item Receipt path.
- Settlement waits until the warranty return is completed.
- Settlement also waits for a refunded ReturnItemResponse and OrderPaymentPreference.
- When the outcome is an exchange, the replacement order must be linked before the Credit Memo is created. This ensures that the Credit Memo customer matches the exchange invoice customer.
- AfterShip warranty processing does not itself increase Shopify inventory for the damaged returned item.

## Lost or stolen warranty

- No RMA is expected because no merchandise is physically returned.
- No Item Receipt is expected.
- The flow creates a standalone Credit Memo after the return is completed and its settlement response exists.
- If the resolution is exchange credit, the replacement order must be linked before settlement.
- The Credit Memo uses the exchange-order customer when the credit will be applied to the exchange invoice.

## No-proof-of-purchase warranty

- An active `NO_POP_WARRANTY_HOLD` intentionally blocks synchronization.
- The hold must be resolved before the return becomes actionable.
- The absence of NetSuite documents while the hold is open is expected behavior.

## Store Credit Exception warranty

- `AFTSHP_SCE_WTY` identifies a warranty whose resolution follows the store-credit exception process.
- It remains a warranty and requires an RMA unless it also qualifies as lost or stolen.
- For warehouse handling, inventory is routed to the warehouse receiving location instead of treating the warranty parent as ordinary sellable stock.
- A mapped damage reason still takes precedence when the line is explicitly damaged.

## Happiness Guarantee and sellable returns

- Both are treated as warranty-style channels by the synchronization decision logic.
- Their settlement outcome must be read from ReturnItem types and linked payment responses, not assumed from the channel alone.
- Exact replacement and store credit can require different downstream documents.
- A mixed return can contain more than one resolution. Diagnostics must report each payment bucket and must not summarize the entire return from only the first item.
- Sellable returns are received into the corresponding store's sellable inventory, not the primary warehouse damage location.

## Appeasements

- An appeasement with items can require an RMA before settlement.
- An appeasement without items has no merchandise to authorize or receive and follows a standalone Credit Memo and Customer Refund path.
- The source order can be represented by a ReturnAdjustment or ReturnItemResponse even when no ReturnItem exists.
