Put messages the engine has never been tuned on here, as *.jsonl (same format as ../corpus).
Best source: real messages from test phones, anonymised with ./gradlew :core:engine:anonymize -Pin=raw.tsv -Pout=new.jsonl.
HoldoutReportTest reports accuracy on them without gating it, and fails only if something that must be ignored gets captured.
Once a hold-out file has been used to fix the engine, move it into ../corpus and start a fresh one.
