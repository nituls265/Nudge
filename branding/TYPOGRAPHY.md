# Nudge typography standard

Source of truth in code: `app/src/main/java/com/nitulshah/nudge/ui/theme/Type.kt`.
This text replaces **"3. Typography"** in the PRD sheet (*NudgeProductRequirements → Design Language System*).

## Rules

1. **12sp is the floor.** Nothing renders smaller. `labelSmall` (12sp) is for captions, chart ticks and units only.
2. **Sentences are 14sp or larger** (`bodySmall` and up). Descriptions, help text and hints are never labels.
3. **Anything people scan (row labels, values, the "N pts to Mindful" nudge) is 15sp or larger.**
4. **Pick a role, don't hand-size.** No `fontSize = N.sp` overrides in screens. Weight and colour may vary per call site; size and line-height come from the theme. (Full-screen intervention and consent dialogs, which sit outside the app theme, follow the same sizes by hand.)
5. **Always `sp`, never `dp`, and never cap scaling**, so text follows the system font-size setting.
6. **Line-height is at least 1.4x the font size** for text that can wrap.
7. **Contrast:** primary text Slate50; secondary Slate400 (about 6.9:1 on Slate900). Slate500 (about 3.6:1) is for decorative or tertiary text only and never for body text at 14sp or below.

## Scale

| Role | Size / line | Weight | Used for |
|---|---|---|---|
| headlineLarge | 36 / 44 | Bold | Score in the ring |
| headlineMedium | 30 / 38 | Bold | Large metrics |
| titleLarge | 24 / 32 | SemiBold | Tier name, dialog titles |
| titleMedium | 18 / 26 | SemiBold | Metric values, card headings |
| titleSmall | 16 / 24 | SemiBold | "N pts to Mindful" nudge, insight values |
| bodyLarge | 16 / 24 | Regular | Primary reading text |
| bodyMedium | 15 / 22 | Regular | Score-driver row labels |
| bodySmall | 14 / 20 | Regular | Help text, descriptions, hints |
| labelLarge | 15 / 20 | Medium | Buttons |
| labelMedium | 14 / 20 | Medium | Section headings (SCORE DRIVERS), metric captions, driver points, 30-day average |
| labelSmall | 12 / 16 | Medium | Floor: chart ticks, tier scale, nav labels, units |

Chrome extension popup follows the same floor: 12px minimum, 13px+ for labels.

## History

Before: sizes were overridden per screen, from 8 to 13sp (chart ticks 8-9sp, section headings 10sp, driver labels 11-13sp). An earlier pass (PR 6) raised several to 12-13sp but left the system scattered. Now centralised.
