# Return settlement rules

## Settlement evidence

ReturnItemResponse links the return outcome to an OrderPaymentPreference and, for an exchange, a replacement order. Settlement decisions must use refunded payment preferences rather than only the return channel.

Only payment preferences in `PAYMENT_REFUNDED` status represent a finalized refund outcome for this synchronization.

## Payment buckets

- `SHOP_STORE_CREDIT` means store credit.
- `EXCHANGE_CREDIT` means credit intended for an exchange order.
- `EXT_GIFT_CARD` and `EXT_SHOP_GFT_CARD` mean gift-card refund.
- Other refunded payment method types generally represent refund to the original payment method.
- `EXCHANGE_PAYMENT` is not a customer refund and must not be classified as one.

## Store credit

- Store credit requires the configured Credit Memo and invoice/application path.
- The invoice uses the Store Credit Issuance item. The HG item, when required, belongs on the Credit Memo and must not replace the invoice's Store Credit Issuance item.
- The store-credit payment amount and date come from the refunded OrderPaymentPreference.
- The return channel alone does not prove that store credit was issued.
- A blind store-credit case can use a standalone Credit Memo and invoice without a Sales Order.

## Exchange credit

- Exchange credit represents value applied to a replacement order.
- The ReturnItemResponse must identify the replacement order before warranty settlement can run.
- The Credit Memo and exchange invoice must use the same NetSuite customer or they cannot be applied together.
- If exchange credit exists but `replacementOrderId` is absent, the return is waiting for exchange-order import or linkage.
- If the exchange credit is lower than the exchange-order total, do not assume the documents can be fully applied. The return remains an exception until the remaining balance has an approved treatment.

## Reconciliation identifier

- For an order-linked exchange, related NetSuite documents use the original Shopify order eTail ID.
- For a blind exchange with no original order, use the replacement order's Shopify eTail ID.
- For blind store credit, use the AfterShip warranty number; if it is unavailable, use the HotWax return identifier.
- Apply the resolved identifier consistently to the Credit Memo and its related invoice so accounting can join the records.

## Gift card

- Gift-card refund value is represented in the Credit Memo using the configured gift-card item behavior.
- A gift-card refund is not automatically the same as an original-payment Customer Refund.
- Mixed gift-card and original-payment refunds must preserve both payment buckets.

## Original payment refund

- A refund to the original payment method requires a Customer Refund after the Credit Memo.
- The Customer Refund ID must be stored in return history when NetSuite returns it.
- If the Credit Memo exists but the Customer Refund ID is absent, the financial flow is partial and the NetSuite error must be reviewed before retrying.

## Posting date

Financial transaction dates come from the relevant refunded OrderPaymentPreference `createdDate`. The current time or return creation date is not a safe replacement because it can put transactions in the wrong accounting period.

## Completion gate

- Warranty settlement waits for `RETURN_COMPLETED`.
- Warranty settlement also waits for a refunded settlement response.
- Exchange warranties additionally wait for replacement-order linkage.
- Creating a Credit Memo before these conditions are true can use the wrong date or the wrong customer.

## Mixed outcomes

A return can have store credit, exchange credit, gift card, and original-payment refund activity. Diagnostics must list all payment preferences, amounts, statuses, dates, owning orders, and replacement-order links. Never choose an outcome from an arbitrary first ReturnItemResponse.
