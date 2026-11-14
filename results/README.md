# results/

Raw experiment data. **Everything here come from a real run on AWS.** No example or synthetic numbers here.

```
results/
├── exp1-coldstart/<variant>/probe.csv, notes.json          (snapstart-off, snapstart-on, snapstart-primed, mem-512 ...)
├── exp2-latency/vus-<N>/run<K>/k6.csv.gz + cloudwatch export + notes.txt
├── exp3-scaling/<ramp|spike|capped>/run<K>/k6.csv.gz + metrics.csv + ...
├── exp4-race/<transactional|naive>/run<K>/k6.csv.gz + consistency.json
└── <any run folder>/cost.json, cost.csv                     (Experiment 5, from cost_calc.py)
```

Each run folder gets a `notes.txt`: date and time (UTC), stack parameters (memory, SnapStart, priming,
booking mode), where k6 ran (CloudShell ap-south-1 or laptop), and anything unusual.

`python analysis/charts.py <chart> <folder>` writes summaries and PNGs into a `summary/` subfolder.
