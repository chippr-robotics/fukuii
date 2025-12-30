# Research Documentation

This directory contains research documents, analysis reports, and investigation findings for the Fukuii Ethereum Classic client.

## Purpose

Research documents serve to:
- Analyze technical proposals and specifications (ECIPs, EIPs)
- Evaluate feasibility and impact of new features
- Document investigation findings
- Provide detailed technical analysis for decision-making
- Support Architecture Decision Records (ADRs)

## Document Types

### Impact Analysis
Comprehensive analysis of proposed features or protocol changes, including:
- Technical requirements and implications
- Implementation complexity assessment
- Risk analysis and mitigation strategies
- Resource estimates and timelines

### Investigation Reports
Detailed investigations of specific technical questions, such as:
- Protocol compatibility issues
- Performance bottlenecks
- Bug root cause analysis
- Alternative implementation approaches

### Feasibility Studies
Evaluation of potential features or integrations:
- Technical feasibility assessment
- Resource requirements
- Benefit-cost analysis
- Recommendations

## Research Documents

### ECIP and EIP Analysis

#### [ECIP-1120 Impact Analysis](ECIP-1120-IMPACT-ANALYSIS.md) | [Quick Summary](ECIP-1120-SUMMARY.md)
**Status:** Draft - Revised  
**Date:** 2025-12-30  
**Topic:** ECIP-1120 "Basefee Market with Miner Rewards" - EIP-1559 with basefee to miners

**Summary:** Comprehensive technical analysis of implementing ECIP-1120 in fukuii, covering:
- EIP-1559 base fee mechanism adaptation for ETC
- **All fees go to miners** (basefee + priority fee)
- Block header modifications and validation changes
- Transaction Type 2 support requirements
- **No treasury component** - simpler than alternatives
- Implementation roadmap and risk analysis

**Key Findings:**
- Implementation feasible with moderate complexity
- Requires block header extensions for base fee field
- Miner receives all transaction fees (no treasury split)
- Estimated 4-5 months for full implementation
- Simpler than treasury-based alternatives

---

## Creating New Research Documents

When creating a new research document:

1. **Choose appropriate type** (Impact Analysis, Investigation, Feasibility Study)

2. **Use consistent structure:**
   ```markdown
   # [Title]
   
   **Document Type:** [Type]
   **Status:** [Draft/Under Review/Final]
   **Date:** YYYY-MM-DD
   **Related ECIPs/EIPs:** [List]
   
   ## Executive Summary
   [Brief overview and key findings]
   
   ## Background
   [Context and motivation]
   
   ## Analysis
   [Detailed technical analysis]
   
   ## Recommendations
   [Actionable recommendations]
   
   ## References
   [Citations and links]
   
   ## Conclusion
   [Summary and next steps]
   ```

3. **Link from this README** - Add entry to appropriate section above

4. **Cross-reference with ADRs** - If research leads to architectural decisions, create or reference appropriate ADR in `/docs/adr/`

5. **Keep it current** - Update status and findings as investigation progresses

## Relationship to ADRs

Research documents support but are distinct from Architecture Decision Records (ADRs):

| Research Documents | ADRs |
|-------------------|------|
| Exploratory and analytical | Decisive and prescriptive |
| May cover multiple options | Document chosen option |
| Detailed technical deep-dives | Concise decision rationale |
| Can remain draft/tentative | Accepted/Rejected status |
| Inform decisions | Record decisions |

**Workflow:**
1. Research Document → Analysis and options
2. Discussion and review
3. ADR → Decision and rationale
4. Implementation

## Guidelines

**Research Quality:**
- Be thorough but concise
- Cite sources and references
- Consider multiple perspectives
- Identify risks and unknowns
- Provide actionable recommendations

**Technical Accuracy:**
- Verify specifications and standards
- Review reference implementations
- Consider consensus implications
- Test assumptions where possible

**Presentation:**
- Use clear, professional language
- Include code examples where helpful
- Use diagrams for complex concepts
- Structure for easy navigation

## See Also

- [Architecture Decision Records (ADRs)](../adr/README.md) - Formal architectural decisions
- [Specifications](../specifications/README.md) - Technical specifications and standards
- [Investigation Reports](../investigation/README.md) - Historical investigations (legacy location)
- [Contributing Guide](../../CONTRIBUTING.md) - Contributing to fukuii

---

**Note:** This directory was created as part of the effort to organize research and analysis documents separately from implementation-focused ADRs. It provides a home for exploratory technical analysis that informs but doesn't dictate architectural decisions.
