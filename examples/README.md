# Example workflows

`order-processing.sw.json` — a generic order-processing workflow used for the
documentation screenshots. It exercises every feature the plugin surfaces:

| Feature in plugin | Where in the file |
|---|---|
| **State types** in the diagram (operation / event / switch) | Mix throughout |
| **Bidirectional select** | Any state |
| **`onErrors` red edges** | `submitOrder`, `processPayment`, `prepareShipment` |
| **Mutation badges** (state / phase / continuationPoint) | Any state calling a `setX*` expression function |
| **Unreachable / Unused state** (grey, struck through) | `legacyApprovalReview` — defined but no transition refers to it |
| **End state** (pink terminal) | `confirmShipping`, `cancelExpiredOrder` |
| **Switch with `defaultCondition`** | `checkPaymentMethod`, `checkInventory` |
| **Event state with `timeouts`** | All `await*` states |
| **Expression functions** mutating runtime fields | All `setX*` functions |

## Taking screenshots from a clean copy

The repo doesn't open as an IDE project on its own — drop the file into any
existing project (or a scratch directory) and open it there.

To demo error-flow / quick-fix features (screenshot `04-inspections.png`),
introduce a typo manually — e.g. rename `awaitShipping` → `awaitShippingX`
in **one** `transition` value so the inspection underlines the unresolved
reference and Alt+Enter offers the `Add 'awaitShippingX' to states` quick-fix.
Undo when you're done.
