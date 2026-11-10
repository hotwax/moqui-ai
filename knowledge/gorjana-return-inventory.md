# Return inventory and Item Receipt rules

## Why location resolution exists

The return destination in OMS identifies where the merchandise was processed. NetSuite may require a different inventory location for sellable inventory, damaged inventory, warehouse receiving, or a configured exception. Location resolution prevents damaged goods from increasing sellable stock and prevents store inventory from being posted to a warehouse incorrectly.

## Destination facility

- A facility whose type parent is `DISTRIBUTION_CENTER` is treated as a warehouse.
- A physical store return normally uses its corresponding NetSuite facility location when inventory is restocked there.
- A warehouse return uses the configured warehouse NetSuite location.
- If a required NetSuite location cannot be resolved, synchronization must fail instead of creating an inventory document at an assumed location.

## Damage location

- `NETSUITE_DMGD_LOC` maps OMS return reasons to NetSuite damaged locations.
- The direct reason-code mapping is checked first.
- If necessary, the human-readable reason is mapped next.
- The configured `DEFAULT` damaged location is the fallback; the current hard fallback is NetSuite location `22`.
- For online AfterShip returns, location is resolved per line. Non-damaged reasons route to ECOM/RMA REC BIN, while damaged reasons route to their mapped damaged location.
- Do not apply one line's damage reason or location to every item in a multi-item return.

## Line location behavior

- A restock line normally goes to the destination facility's NetSuite location.
- A damaged or no-restock line goes to its mapped damaged location.
- For a sellable return, receipt belongs to the corresponding store because OMS increases that store's sellable inventory.
- A Store Credit Exception warranty received at the warehouse uses the warehouse receiving location when no explicit damage mapping applies.
- A completed AfterShip warehouse return uses the resolved warehouse/default receipt routing even when OMS did not create a normal ShipmentReceipt first.

## Shopify inventory is a separate decision

- A store AfterShip return submitted with Restock increases Shopify inventory at the store. OMS must import the movement and must not post the same Shopify increase again.
- A damaged reason does not override Shopify's submitted restock choice. If Restock is selected for a damaged item, Shopify can still add it to sellable inventory.
- An online AfterShip return should not increase inventory until the item is physically received.
- AfterShip warranty claims do not post a Shopify inventory movement for the returned damaged item.
- A Shopify `CANCEL` entry can release exchange-order commitment without changing on-hand inventory. Verify on-hand and committed quantities before calling it a returned-item restock.

## Item Receipt creation

- An RMA is required before an Item Receipt, except scenarios where no merchandise is returned.
- Appeasements without returned items do not require an Item Receipt.
- The Item Receipt must contain only quantities currently intended for receipt.
- Missing line, quantity, location, bin, or kit-component information must stop submission rather than produce a partial receipt.

## Store no-restock returns

For a NetSuite-managed store return where all lines are no-restock, OMS may not create the Item Receipt directly. The receipt is expected to be created in NetSuite and synchronized back to OMS. This is an intentional wait when the return is present in `NetSuitePendingItemReceiptSyncView`.

## AfterShip warehouse configuration

The product-store setting `NS_AFTSHIP_RTN_WH_IR` controls automatic Item Receipt creation for ordinary AfterShip warehouse returns. When disabled, absence of an automatically created IR is expected. Warranty rules are evaluated separately.

## Warranty inventory visibility

The currently confirmed Inventory Transfer scope is floor-damage warranties. Other warranty types can be visible in OMS reports without creating a Shopify inventory movement or a NetSuite Inventory Transfer. Do not use report visibility as evidence that an inventory transaction exists.

## Kit products

- A NetSuite Kit/Package parent does not hold inventory.
- The kit parent must be included as received but must not contain inventory detail.
- Hidden NetSuite component transaction lines are resolved through SuiteQL once per RMA.
- Each component uses its own TransactionLine ID as `orderLine` and its SuiteQL quantity.
- Inventory detail is added only to inventory-bearing component lines.
- Components are grouped by their kit parent line and must never be merged only because they share an item ID.
- If any receivable kit parent has unresolved or invalid components, no Item Receipt should be submitted.

## Item Receipt synchronization from NetSuite

The `sync_NetSuiteItemReceipts` job reads from its last successful runtime to the current time. The NetSuite query uses a small overlap buffer to avoid missing records at the cursor boundary.

Only returns in `NetSuitePendingItemReceiptSyncView` are considered. NetSuite Item Receipts are matched by their created-from RMA ID. A match stores the Item Receipt ID in NetSuite return history.

When an IR exists in NetSuite but OMS history is empty, check:

1. The job is not paused.
2. The job `lastRunTime` window included the IR.
3. The return was in the pending IR back-sync view.
4. The stored RMA ID exactly matches the Item Receipt `createdfrom` value.
5. The history update did not fail after the NetSuite response was received.
