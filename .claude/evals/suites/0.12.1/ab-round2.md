| task | rep | type | agent | winner | hard A | hard B | F1 A | F1 B | rubric A | rubric B | grader pref | $A | $B |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| diagnose-1735 | 1 | diagnose | conduit | **A** | - | - | 1.00 | 0.67 | 8/8 | 8/8 | A | 0.21 | 0.16 |
| diagnose-1735 | 2 | diagnose | conduit | **A** | - | - | 1.00 | 1.00 | 8/8 | 8/8 | A | 0.17 | 0.15 |
| diagnose-1753 | 1 | diagnose | vault | **tie** | - | - | 0.33 | 0.36 | 8/10 | 8/10 | A | 0.39 | 0.31 |
| diagnose-1753 | 2 | diagnose | vault | **B** | - | - | 0.36 | 0.33 | 8/10 | 10/10 | B | 0.30 | 0.32 |
| diagnose-1879 | 1 | diagnose | conduit | **B** | - | - | 0.80 | 0.73 | 8/10 | 8/10 | B | 0.25 | 0.27 |
| diagnose-1879 | 2 | diagnose | conduit | **A** | - | - | 0.73 | 0.89 | 7/10 | 7/10 | A | 0.23 | 0.29 |
| review-1769 | 1 | review | vault | **A** | - | - | 0.86 | 0.50 | 6/10 | 6/10 | A | 0.29 | 0.25 |
| review-1769 | 2 | review | vault | **B** | - | - | 0.86 | 0.57 | 5/10 | 8/10 | B | 0.25 | 0.31 |
| route-1757 | 1 | route | router | **tie** | route ['herald'] != ['forge', 'herald'] | route ['herald', 'vault'] != ['forge', 'herald'] | - | - | 4/6 | 4/6 | B | 0.11 | 0.10 |
| route-1757 | 2 | route | router | **B** | route ['herald'] != ['forge', 'herald'] | - | - | - | 4/6 | 6/6 | B | 0.11 | 0.13 |
| route-1765 | 1 | route | router | **A** | - | - | - | - | 6/6 | 5/6 | A | 0.11 | 0.09 |
| route-1765 | 2 | route | router | **A** | - | - | - | - | 6/6 | 5/6 | A | 0.09 | 0.09 |
| route-1770 | 1 | route | router | **A** | - | - | - | - | 4/6 | 3/6 | B | 0.09 | 0.09 |
| route-1770 | 2 | route | router | **A** | - | route ['beacon', 'conduit'] != ['conduit'] | - | - | 4/6 | 3/6 | A | 0.09 | 0.09 |
| route-1806 | 1 | route | router | **tie** | - | - | - | - | 5/6 | 5/6 | A | 0.11 | 0.11 |
| route-1806 | 2 | route | router | **B** | - | - | - | - | 4/6 | 5/6 | B | 0.09 | 0.09 |

Tasks: 8  wins A=3 B=3 tie=2
- [ ] margin wins(B)-wins(A)=0 >= 2
- [ ] no new hard failures ['route-1770']
- [x] no regression-suite losses 
- [x] cost median A=$0.142 B=$0.137

VERDICT: KEEP A
