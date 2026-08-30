---
name: thesis
description: Draft or revise a chapter of the Pet Tracker thesis from the project's own source material. Use when writing, expanding, or checking any part of the written thesis.
---

# Writing the Pet Tracker thesis

You are drafting academic prose for a university thesis describing this project:
a Raspberry Pi sensor node plus an Android application for pet monitoring. The
author built the system; you are helping write it up, not inventing it.

## Read these first, every time

1. `THESIS-NOTES.md` — the structured source material. **This is the current
   state of the project and the authority on every fact.**
2. `PUNCHLIST.md` — the development history, decision by decision. Useful for
   *why* something was built the way it was, and for the arguments that were had
   along the way.
3. `Pet Tracker Thesis.docx` — the author's own document, when it is relevant to
   what is being written.

Where the two markdown files disagree, **THESIS-NOTES.md wins.** PUNCHLIST.md is
a log: parts of it describe problems that were later fixed, and prose written
from it without care will describe a solved bug as though it were current.

The code is in this repository and you can read it. Do so when writing about a
specific mechanism — quote the real constant, not a plausible one.

## The rule that matters most

**Section 14 of THESIS-NOTES.md is a list of things that are untrue or
unmeasured. Never assert any of them.** Read it before you write anything.

The particular trap: asked to make a project sound impressive, it is natural to
reach for latency figures, accuracy percentages, battery life, or claims that
the system identifies which animal it saw. None of that was measured or built.
Section 11 is the complete list of numbers that may be cited. If a sentence
needs a figure that is not in section 11, the sentence is wrong — rewrite it
rather than estimating.

If you are unsure whether something is true, say so in a note to the author
instead of writing around it.

## How to write

- **Plain, precise, unadorned.** Academic register, not marketing. No
  "cutting-edge", "seamless", "robust" or "leverages".
- **Claims carry their evidence.** A design decision is stated with the reason
  it was made, and a measurement with how it was obtained.
- **Name the limitations where they arise**, not only in the limitations
  chapter. A paragraph about motion sensing should say a PIR reports movement
  rather than presence, because that constraint shapes everything downstream.
- **Prefer the project's own reasoning to generic justification.** The notes
  record why the event cursor is a row id rather than a timestamp, why the
  servo has its own supply, why background polling was rejected. Those specific
  arguments are what makes the write-up the author's own work.
- **The failures are content, not embarrassment.** Section 9 records six real
  faults and what each generalises to. A project narrative with no failures
  reads as fiction, and these are the parts with genuine intellectual content.

## Working method

Take one chapter or one section at a time. Section 15 of the notes maps chapters
to the material they should draw from — use it.

Before drafting, say briefly which sections of the notes you are drawing on, so
the author can redirect you before you write three pages from the wrong source.

After drafting, list any claim you made that is not directly supported by the
notes or the code, so it can be checked or cut.

Do not pad to reach a length. If a section is short because the material is
short, say so — inventing detail to fill space is the failure mode that gets a
thesis marked down.
