# Triage Labels

The skills speak in terms of five canonical triage roles. In this repo the tracker is markdown files
(`docs/agents/issue-tracker.md`), so a "label" is the value of the `triage:` field in a spec's or
ticket's front matter — not a GitHub label.

| Label in mattpocock/skills | Value in our `triage:` field | Meaning                                  |
| -------------------------- | ---------------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`               | Maintainer needs to evaluate this        |
| `needs-info`               | `needs-info`                 | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`            | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`            | Requires human implementation            |
| `wontfix`                  | `wontfix`                    | Will not be actioned                     |

Exactly one value at a time; `triage:` is a single field, not a list. Applying a label is editing that
field, and the change travels in the same PR as whatever else moved.

`state:` is orthogonal and holds `open` or `closed`. A `wontfix` ticket is normally also `closed`; the
two fields answer different questions, so neither implies the other.

Edit the right-hand column to match whatever vocabulary you actually use.
