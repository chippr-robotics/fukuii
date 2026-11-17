| task | rep | type | agent | winner | hard A | hard B | F1 A | F1 B | rubric A | rubric B | grader pref | $A | $B |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| diagnose-1735 | 1 | diagnose | conduit | **A** | - | - | 1.00 | 1.00 | 8/8 | 7/8 | A | 0.21 | 0.19 |
| diagnose-1735 | 2 | diagnose | conduit | **A** | - | - | 1.00 | 1.00 | 8/8 | 8/8 | A | 0.17 | 0.17 |
| diagnose-1753 | 1 | diagnose | vault | **B** | - | - | 0.62 | 0.33 | 8/10 | 8/10 | B | 0.52 | 0.39 |
| diagnose-1753 | 2 | diagnose | vault | **A** | - | - | 0.33 | 0.36 | 9/10 | 8/10 | A | 0.57 | 0.30 |
| diagnose-1879 | 1 | diagnose | conduit | **tie** | - | - | 0.80 | 0.80 | 6/10 | 6/10 | B | 0.25 | 0.19 |
| diagnose-1879 | 2 | diagnose | conduit | **A** | - | - | 0.73 | 0.67 | 8/10 | 8/10 | A | 0.23 | 0.24 |
| review-1745 | 1 | review | herald | **B** | - | - | 0.67 | 0.67 | 0/8 | 8/8 | B | 0.29 | 0.27 |
| review-1745 | 2 | review | herald | **B** | - | - | 0.67 | 0.67 | 0/8 | 8/8 | B | 0.24 | 0.22 |
| review-1769 | 1 | review | vault | **B** | - | - | 0.86 | 0.86 | 5/10 | 5/10 | B | 0.31 | 0.29 |
| review-1769 | 2 | review | vault | **tie** | - | - | 0.86 | 0.86 | 5/10 | 5/10 | B | 0.28 | 0.25 |
| review-1782 | 1 | review | herald | **B** | - | - | 0.36 | 0.22 | 0/10 | 4/10 | B | 0.29 | 0.30 |
| review-1782 | 2 | review | herald | **B** | - | - | 0.22 | 0.67 | 0/10 | 6/10 | B | 0.36 | 0.28 |
| review-1783 | 1 | review | prism | **B** | - | - | 0.75 | 0.86 | 5/10 | 8/10 | B | 0.34 | 0.25 |
| review-1783 | 2 | review | prism | **B** | - | - | 0.50 | 0.67 | 1/10 | 8/10 | B | 0.26 | 0.30 |
| review-1787 | 1 | review | prism | **B** | - | - | 0.50 | 0.50 | 3/12 | 7/12 | B | 0.33 | 0.23 |
| review-1787 | 2 | review | prism | **B** | - | - | 0.29 | 0.50 | 3/12 | 8/12 | B | 0.36 | 0.28 |
| review-1823 | 1 | review | beacon | **B** | - | - | 0.67 | 1.00 | 3/8 | 8/8 | B | 0.76 | 0.83 |
| review-1823 | 2 | review | beacon | **B** | - | - | 0.60 | 0.89 | 3/8 | 7/8 | B | 0.76 | 0.74 |
| review-1853 | 1 | review | forge | **B** | - | - | 0.57 | 0.40 | 4/12 | 9/12 | B | 1.46 | 1.02 |
| review-1853 | 2 | review | forge | **B** | - | - | 0.33 | 0.57 | 4/12 | 12/12 | B | 1.30 | 1.50 |
| review-1870 | 1 | review | forge | **tie** | - | - | 0.73 | 0.73 | 10/10 | 10/10 | B | 1.43 | 1.45 |
| review-1870 | 2 | review | forge | **B** | - | - | 0.44 | 0.60 | 7/10 | 7/10 | A | 1.38 | 1.33 |
| route-1733 | 1 | route | router | **B** | - | - | - | - | 6/6 | 6/6 | B | 0.11 | 0.11 |
| route-1733 | 2 | route | router | **B** | - | - | - | - | 5/6 | 6/6 | B | 0.09 | 0.10 |
| route-1744 | 1 | route | router | **A** | - | - | - | - | 5/6 | 5/6 | B | 0.11 | 0.08 |
| route-1744 | 2 | route | router | **B** | route [] != ['prism'] | - | - | - | 4/6 | 5/6 | A | 0.09 | 0.08 |
| route-1757 | 1 | route | router | **B** | route ['forge', 'herald', 'vault'] != ['forge', 'herald'] | - | - | - | 6/6 | 6/6 | B | 0.15 | 0.09 |
| route-1757 | 2 | route | router | **tie** | route ['herald', 'vault'] != ['forge', 'herald'] | route ['herald'] != ['forge', 'herald'] | - | - | 5/6 | 5/6 | A | 0.14 | 0.13 |
| route-1765 | 1 | route | router | **B** | route ['conduit'] != ['beacon', 'conduit'] | - | - | - | 4/6 | 6/6 | B | 0.10 | 0.10 |
| route-1765 | 2 | route | router | **tie** | - | - | - | - | 5/6 | 5/6 | B | 0.09 | 0.09 |
| route-1770 | 1 | route | router | **A** | - | - | - | - | 6/6 | 5/6 | A | 0.10 | 0.09 |
| route-1770 | 2 | route | router | **A** | - | - | - | - | 5/6 | 4/6 | A | 0.09 | 0.09 |
| route-1806 | 1 | route | router | **tie** | - | - | - | - | 4/6 | 4/6 | A | 0.09 | 0.09 |
| route-1806 | 2 | route | router | **B** | - | - | - | - | 4/6 | 5/6 | B | 0.09 | 0.09 |
| route-1814 | 1 | route | router | **B** | - | - | - | - | 5/6 | 6/6 | B | 0.09 | 0.10 |
| route-1814 | 2 | route | router | **A** | - | - | - | - | 6/6 | 5/6 | A | 0.10 | 0.09 |

Tasks: 18  wins A=3 B=12 tie=3
- [x] margin wins(B)-wins(A)=9 >= 4
- [x] no new hard failures 
- [x] no regression-suite losses 
- [x] cost median A=$0.246 B=$0.227

VERDICT: ACCEPT B
