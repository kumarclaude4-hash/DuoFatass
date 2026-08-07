# SESSION INDEX

The remediation program is executed in **three fixed rounds**. Each has one session log. There is
no fourth session — Round 3 is the hard stop.

| # | Round | Priority | Log | Status | Findings |
|---|---|---|---|---|---|
| 01 | Stop the bleeding | P0 | [`sessions/SESSION-01.md`](./sessions/SESSION-01.md) | DONE | S08-C1, SC-02, S08-H1, S03-L1, S07-C1, S07-H1, S02-L1, S06-H1, S02-M1, SC-12, S02-I3(partial) |
| 02 | Advertised guarantees | P1 | [`sessions/SESSION-02.md`](./sessions/SESSION-02.md) | IN-PROGRESS (Tasks 1–3 complete) | S06-H2, S06-H3, S08-H5, S07-M1, S08-H2, S10-N2, S05-H1, S05-H3, S04-I2; remaining Round 2 findings tracked in session log |
| 03 | P2 batch + HARD STOP | P2 | [`sessions/SESSION-03.md`](./sessions/SESSION-03.md) | DONE | all remaining (see log) |

Relationship to audit sessions: the audit's `SESSION-00…10` are **discovery** sessions (frozen,
immutable). These remediation `SESSION-01…03` are **fix** sessions and are the only ones this
program writes to. They are numbered independently and must not be confused with the audit's.
