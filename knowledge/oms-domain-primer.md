# HotWax OMS — Domain Primer

> Core HotWax Commerce OMS vocabulary for reasoning about inventory, orders, and
> fulfillment. Use these definitions when interpreting a user's request or when naming and
> describing agents and tools, so the language matches how the OMS actually works.

## Inventory & availability

- **Quantity on Hand (QOH)** — the total physical quantity of a product at stores or
  warehouses, refreshed daily from ERP / WMS / POS systems.
- **Available to Promise (ATP)** — the sellable physical quantity after deducting reserved
  inventory: **ATP = QOH − reserved**.
- **Online ATP** — the unified pool of sellable inventory published to e-commerce channels.
  It excludes safety stock, thresholds, brokering-queue orders, and excluded facilities.
- **Reserved inventory** — quantity already allocated to fulfill orders; excluded from ATP and
  not available for sale.
- **Safety stock** — buffer inventory held back for in-store sales once store inventory falls
  below a designated quantity; mitigates inventory discrepancies.
- **Threshold** — buffer stock withheld company-wide before committing inventory to online
  channels; reduces the risk of overselling from count inaccuracies.
- **Inventory variance** — discrepancies between expected and actual inventory levels (damage,
  shipment errors, theft).

## Fulfillment & routing

- **Facility** — a physical location (warehouse, distribution center, or store) where inventory
  is stored, managed, and processed.
- **Facility type** — the kind of fulfillment location: Retail Store, Warehouse, Outlet Store,
  Outlet Warehouse.
- **Brokering** — matching and routing orders to the most suitable fulfillment center based on
  inventory availability, proximity, and delivery speed.
- **Brokering queue** — the waiting area holding approved orders until the brokering engine runs.
- **Split shipment** — dividing a single order into multiple shipments so available items ship
  sooner.
- **Advanced Shipping Notice (ASN)** — a supplier notification detailing the contents and
  expected arrival of an inbound shipment; used to receive inventory via the Receiving App.

## Order types

- **Backorder** — an order for a product temporarily out of stock that was previously available
  and will be restocked soon.
- **Pre-order** — an advance order for future inventory not yet released or in production,
  fulfilled once the product becomes available.

---

Source: HotWax Commerce — *Learn HotWax OMS* (docs.hotwax.co/documents/learn-hotwax-oms).
