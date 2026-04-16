---
name: ICE
description: Abstract Methodology for Large-Scale Code Migration Tasks
---

# ICE


High-Level Stages Completed:
	1. Discovery & Assessment Phase
		○ Explored repository structure and build configuration
		○ Identified all compilation errors (280+ errors across 2 modules)
		○ Created comprehensive migration plans documenting strategy
	2. Build Configuration Phase
		○ Fixed sbt configuration issues (loading order, version references)
		○ Updated dependencies to latest stable versions
		○ Resolved Scala version compatibility issues
	3. Systematic Migration Phase (3 sub-phases for scalanet, parallel approach for scalanetDiscovery)
		○ Phase 1: Quick wins (type annotations, simple API replacements)
		○ Phase 2: CE3 API conversions (Task→IO, Observable→Stream, Scheduler removal)
		○ Phase 3: Type system & implicit resolution (complex inference issues)
	4. Code Review & Refinement Phase
		○ Addressed all code review feedback iteratively
		○ Enhanced documentation for async patterns and migration decisions
		○ Added security updates and dependency improvements
	5. Verification & Documentation Phase
		○ Created comprehensive migration documentation
		○ Updated contribution guidelines
		○ Prepared testing plan for CI/CD
Tools Used & Rationale:
	• bash: Compilation checks (iterative verification), git operations, file exploration
	• view: Reading existing code to understand patterns and APIs
	• edit: Surgical code changes maintaining minimal scope
	• report_progress: Frequent commits with detailed documentation after validation
	• reply_to_comment: Structured communication with stakeholders
## OODA Loop for Complex Feature Development

When developing large features or performing migrations, follow Boyd's OODA loop:

### Observe Phase - Comprehensive Understanding
	1. **Code Review** - Read all related files to understand architecture
		○ Map existing components and their interactions
		○ Identify patterns and conventions used
		○ Note incomplete implementations and TODOs
	2. **Documentation Review** - Check ADRs, specs, implementation guides
		○ Review architectural decision records
		○ Read existing documentation for context
		○ Understand design decisions and constraints
	3. **Research** - Study reference implementations and best practices
		○ How do core-geth, besu handle this?
		○ What are industry best practices?
		○ Are there security considerations?
	4. **Consensus Impact Analysis** - **MANDATORY for EIP/ECIP implementations**
		○ **STOP: Is this a consensus-critical change?**
		○ If YES: **Consult forge agent for impact analysis BEFORE proceeding**
		○ Identify all consensus-affecting components
		○ Document validation requirements
		○ Plan forge agent delegation (see Morgoth's Consensus-Critical Change Protocol)
	5. **Gap Analysis** - What exists vs. what's needed
		○ Identify completed work
		○ List missing components
		○ Document dependencies and blockers
	6. **Deliverable:** Comprehensive understanding document (STATUS.md)

### Morgoth's Wisdom for ICE

**Sequential thinking before action:**
- Before starting Decide phase, complete Observe fully
- State what you understand vs what you don't know
- "I don't know" is a valid Observe phase output

**Context window discipline:**
- Every ~10 actions, scroll back to original goal
- Verify you still understand the "why"
- If thread is lost, STOP and checkpoint

**Batch size: 3, then checkpoint:**
- Make max 3 changes per phase
- Verify each change works
- Report progress before moving to next batch

**Failure analysis:**
- When phase fails, state what failed (raw error)
- State theory about why
- State proposed fix
- Ask before proceeding to next attempt

### Orient Phase - Strategic Planning
	1. **Prioritization** - Organize work by criticality
		○ P0 (Critical) - Blocking issues, core functionality
		○ P1 (Important) - Production readiness, error handling
		○ P2 (Testing) - Validation, integration tests
		○ P3 (Polish) - Documentation, optimization
	2. **Dependencies** - Identify what blocks what
		○ Map prerequisite relationships
		○ Identify critical path
		○ Note parallel workstreams
	3. **Timeline** - Estimate effort realistically
		○ Based on complexity, not wishful thinking
		○ Include buffer for unknowns (20-30%)
		○ Define phases with clear milestones
	4. **Boundaries** - Recognize natural stopping points
		○ Infrastructure vs. network integration
		○ Compilation vs. testing
		○ What requires external resources (live network, etc.)
	5. **Deliverable:** TODO document with prioritized work items

### Decide Phase - Action Planning
	1. **Scope** - What to do now vs. later
		○ Focus on high-value, low-risk work first
		○ Complete logical phases before moving on
		○ Don't start work that can't be finished
	2. **Risk Assessment** - Evaluate each decision's impact
		○ What could break? (consensus, compatibility)
		○ What's the rollback plan?
		○ What validation is needed?
	3. **Agent Delegation** - **Route consensus work to appropriate agents**
		○ **Consensus-critical changes → Forge agent (MANDATORY)**
		○ Compilation fixes → Wraith agent
		○ Testing/validation → Eye agent
		○ Network protocol → Herald agent
		○ Code modernization → Mithril agent
		○ See Morgoth's Consensus-Critical Change Protocol
	4. **Value Prioritization** - High-value, low-risk first
		○ Fix critical bugs immediately
		○ Implement infrastructure before integration
		○ Defer optimization until functionality works
	5. **Constraints** - Work within limitations
		○ Respect maintainer requirements (minimal docs)
		○ Work within available resources (no live network yet)
		○ Maintain backward compatibility
	6. **Deliverable:** Clear action plan with phases and agent assignments

### Act Phase - Validated Implementation
	1. **Incremental Changes** - Small, focused commits
		○ One logical change per commit
		○ Keep scope minimal (change as few lines as possible)
		○ Make changes reversible
	2. **Frequent Validation** - Compile and test continuously
		○ Compile after every change
		○ Run relevant tests frequently
		○ Check for regressions early
	3. **Report Progress** - Commit after each verified unit
		○ Use report_progress tool frequently
		○ Update tracking docs (TODO, STATUS)
		○ Keep stakeholders informed
	4. **Documentation** - Keep external docs current
		○ Minimal inline documentation (per maintainer requirement)
		○ Comprehensive planning docs (TODO, STATUS, SUMMARY)
		○ Update progress continuously
	5. **Deliverable:** Working, validated, documented code

### Critical Path Validation Pattern
Before deep implementation, validate critical assumptions:
```scala
// Smoke test critical integration points
object CriticalPathValidation {
  // Can core components instantiate?
  val controller = ComponentUnderDevelopment.props(...)
  
  // Do message types match?
  controller ! CorrectMessageType.Start  // Not wrong one!
  
  // Does configuration load?
  val config = Config.fromTypesafe(...)
  
  println("✅ Critical path validated")
}
```

### Success Pattern for Complex Features
	• Multiple OODA loops per feature
	• Each loop builds on previous understanding
	• Validate assumptions early (Observe phase)
	• Plan thoroughly before coding (Orient/Decide)
	• Execute incrementally with frequent validation (Act)
	• Loop back when new information emerges

## Abstract Steps for Similar Tasks

Following the OODA framework above:
	1. **Understand the Problem Space** (Observe)
		○ Run initial compilation to identify all errors
		○ Categorize errors by type (build config, API changes, type system)
		○ Review migration guides for the technologies involved
	2. **Create a Migration Strategy** (Orient)
		○ Document error categories and solutions in a structured plan
		○ Identify dependencies between fixes (what must be done first)
		○ Establish phases with clear success criteria
	3. **Fix Build Configuration First** (Decide/Act)
		○ Resolve dependency conflicts and version issues
		○ Fix build tool configuration (sbt, maven, gradle)
		○ Ensure project structure is correct
	4. **Execute Phased Migration** (Act)
		○ Start with quick wins to reduce error count rapidly
		○ Address API migrations systematically (simple → complex)
		○ Fix type system issues last (they often resolve after API fixes)
	5. **Validate Iteratively** (Act)
		○ Compile after each logical group of changes
		○ Run tests incrementally to catch regressions early
		○ Use version control strategically (commit working states)
	6. **Address Code Quality** (Act)
		○ Respond to code review feedback promptly
		○ Enhance documentation for non-obvious decisions
		○ Update security-critical dependencies
	7. **Document Decisions & Tradeoffs** (Act)
		○ Explain why certain approaches were chosen
		○ Document known limitations and technical debt
		○ Provide guidance for future maintainers
	8. **Prepare for Verification** (Orient/Act)
		○ Create testing plan for stakeholders
		○ Document expected CI/CD outcomes
		○ Provide rollback strategy if needed

## Key Success Factors
	• **OODA Loop Application:** Multiple cycles, each building understanding
	• **Small, incremental changes** with frequent verification
	• **Clear communication** through commit messages and PR updates
	• **Systematic approach** prevents overlooking errors
	• **Phase boundaries:** Complete logical units before moving forward
	• **External documentation** ensures knowledge transfer
	• **Critical path validation** catches blocking issues early<img width="529" height="1657" alt="image" src="https://github.com/user-attachments/assets/54ddf899-9b6c-4515-83d8-e35204d8c9b3" />
