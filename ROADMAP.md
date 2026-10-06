# 🗺️ Project Roadmap

This document outlines the planned milestones, current progress, and future objectives.

> **Status Legend:**
> 🟢 **Completed** | 🟡 **In Progress / Current** | 🔵 **Planned**

---

### 🟢 Week 1-2
**Target Date:** `2026-09-23`
- [x] Decide on the service list
- [x] Design the high-level architecture
- [x] Draft a specification of the agentic system

### 🟢 Week 3
**Target Date:** `2026-09-30`
- [x] "vibe-code" the legacy system

### 🟡 Week 4
**Target Date:** `2026-10-07`
- [ ] fix bugs, fill gaps, decide open questions, correct mistakes, which have surfaced through the review, and which deter me from continuing my work with the next phases
- [ ] review the "vibe-coded" legacy system a bit more thoroughly (but quickly, and efficiently)

### 🔵 Week 5
**Target Date:** `2026-10-14`
- [ ] Build agents
  - [ ] Decide if I want agents to call raw tools as a baseline (HTTP, SQL, SOAP), or if I want only an in-process canonical tool selection (needs a Java agent SDK), or, both, so that the tokenomics comparison can be complete
  - [ ] Choose which ADK, aimed at which ecosystem (TypeScript, Java, etc.) should be used, detail the decision: LangGraph / Google ADK / Claude ADK 
  - [ ] Build the agents on the chosen ADK
- [ ] Testing without chat
  - The proposed way to do that is to test demo script workflows, orchestrated by agents, logged
- [ ] Build the MCP servers

### 🔵 Week 6
**Target Date:** `2026-10-21`
- [ ] Implement MCP integration in case of the created agents
- [ ] Create the chat backend and UI (terminal, simple)
- [ ] Test workflows triggerable from chat interface
  - Information-querying workflows (has X been invoiced? querying status of services and databases)
  - Complex queries! -> orchestrator / router

### 🔵 Week 7
**Target Date:** `2026-10-28`
- [ ] UCP

### 🔵 Week 8
**Target Date:** `2026-11-04`
- [ ] Tokenomics
  - For example, measure what token cost and latency the same business transaction produces via heterogeneous interfaces vs. MCP?
  - But the cost of modernization itself is also such a consideration

### 🔵 Week 9: Methodology
**Target Date:** `2026-11-11`
- [ ] Come to a general methodology conclusion, document it in great detail
  - An interesting question is that how should such modernization be automated?
