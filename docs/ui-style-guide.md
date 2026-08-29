# UI style guide

Conventions for EnderSlicerCura's Compose UI. The app targets phones and
foldables with a dense, tool-first layout: small screens, gloved-ish use,
few chrome elements. Follow the rules below for new or modified screens.

## Layout

- Use the 4 px grid: spacing from `EnderSlicerDimens` (`Space2`, `Space4`,
  `Space6`, `Space8`, `Space12`, `Space16`, `Space24`) instead of ad-hoc
  values.
- Cards: 12 dp padding (`EnderSlicerDimens.CardPadding`); sheets: 12 dp
  padding (`EnderSlicerDimens.SheetPadding`), 8 dp inner spacing.
- Minimum touch target 48 dp (`EnderSlicerDimens.TouchTarget`); sliders and
  timeline controls get at least a 36-40 dp interactive height.
- Full-width primary actions centered; secondary actions in the same row
  with equal weight.

## Buttons

- Exactly three tiers: filled `Button` (one primary action per screen),
  outlined `OutlinedButton` (secondary actions), `TextButton` (tertiary /
  inline actions).
- Disabled buttons rely on Material defaults, but always pair a disabled
  primary-looking control with a one-line caption explaining what is
  missing (e.g. "Slice a model first to export validated G-code").

## Text

- `titleSmall` for card titles, `bodySmall` for content, `labelSmall` for
  helper text - never more than two grey/`onSurfaceVariant` levels per
  card.
- Error and warning text uses `MaterialTheme.colorScheme.error`; capturing
  the count/purpose in a single line ("Cura compatibility warnings: 3").
- Long explanatory copy belongs behind an info affordance or in a legend
  with color swatches, not as a paragraph in the control flow.

## Fields

- `OutlinedTextField` labels, not placeholder-only fields. Right-align
  numeric values with a unit suffix where useful.
- Numbers: format with the device locale via `NumberFormat` (no grouping)
  and trim trailing zeros through the locale decimal separator - never
  mutate a formatted string with '.'-based trimming (it leaves dangling
  separators like `115,`).

## Status vs help

- Transient status messages (restore results, operation outcomes) are
  separate from static gesture help. Gesture hints are dismissible and
  should be shown once per session.

## States

- Every list/empty state says what to do next ("Import an STL from Menu").
- Printer-dependent actions are disabled (with a reason caption) unless the
  printer is operational; never rely on the server's 400/409 alone.
