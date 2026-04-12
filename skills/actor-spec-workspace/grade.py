#!/usr/bin/env python3
"""Grade actor-spec eval outputs against assertions."""
import json, re, os, sys
from pathlib import Path

ITER = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("iteration-1")

def read_specs(outputs_dir: Path) -> list[tuple[str, str]]:
    """Return list of (filename, content) for all .md files in outputs_dir."""
    results = []
    if outputs_dir.exists():
        for f in sorted(outputs_dir.glob("*.md")):
            results.append((f.name, f.read_text()))
    return results

def find_main_spec(specs: list[tuple[str, str]]) -> tuple[str, str] | None:
    """Find the main (non-subagent) spec - typically the first or largest."""
    if not specs:
        return None
    for name, content in specs:
        if "orchestrator" in name.lower() or "review-agent" in name.lower() or "watcher" in name.lower():
            return (name, content)
    return specs[0]

def grade_assertion(text: str, specs: list[tuple[str, str]], main_spec: tuple[str, str] | None) -> tuple[bool, str]:
    if main_spec is None:
        return False, "No spec files found"

    main_name, main_content = main_spec
    all_content = "\n".join(c for _, c in specs)

    if "title heading" in text.lower() and "actor specification" in text.lower():
        match = re.search(r'^# .+ Actor Specification', main_content, re.MULTILINE)
        if match:
            return True, f"Found: {match.group(0)}"
        alt = re.search(r'^# .+ Specification', main_content, re.MULTILINE)
        if alt:
            return True, f"Found close match: {alt.group(0)}"
        return False, "No '# ... Actor Specification' heading found"

    if "'## actor purpose'" in text.lower():
        if re.search(r'^## (Actor )?Purpose', main_content, re.MULTILINE):
            return True, "Found Actor Purpose / Purpose section"
        return False, "No Actor Purpose section found"

    if "'## messaging protocol'" in text.lower():
        if re.search(r'^## Messaging Protocol', main_content, re.MULTILINE):
            has_receive = bool(re.search(r'receive|receives', main_content, re.IGNORECASE))
            has_respond = bool(re.search(r'respond|sends?|reply', main_content, re.IGNORECASE))
            if has_receive and has_respond:
                return True, "Found Messaging Protocol with receive and respond sections"
            return True, "Found Messaging Protocol section (receive/respond detection partial)"
        return False, "No Messaging Protocol section found"

    if "'## workflow'" in text.lower() and "numbered" in text.lower():
        if re.search(r'^## Workflow', main_content, re.MULTILINE):
            if re.search(r'^\d+\.', main_content, re.MULTILINE):
                return True, "Found Workflow section with numbered steps"
            return False, "Workflow section found but no numbered steps"
        return False, "No Workflow section found"

    if "second-person" in text.lower() or "second person" in text.lower():
        purpose_match = re.search(r'^## (Actor )?Purpose\s*\n+(.*?)(?=\n##|\Z)', main_content, re.MULTILINE | re.DOTALL)
        if purpose_match:
            purpose_text = purpose_match.group(2).strip()
            if re.search(r'\b(Your purpose|You\b)', purpose_text):
                return True, f"Purpose uses second-person voice"
        if re.search(r'\bYour purpose is to\b', main_content):
            return True, "Found 'Your purpose is to' in spec"
        if re.search(r'\bYou (may|will|turn|receive|respond|accept|should)\b', main_content):
            return True, "Found second-person 'You ...' in spec"
        return False, "No second-person voice detected in purpose section"

    if "italicized defined terms" in text.lower():
        italics = re.findall(r'(?<!\w)_([A-Z][A-Za-z ]+?)_(?!\w)', main_content)
        if italics:
            return True, f"Found italicized terms: {', '.join(italics[:5])}"
        return False, "No italicized defined terms (e.g. _Term Name_) found"

    if "conditional branching" in text.lower():
        cond_patterns = [r'\bif\b.*\b(proceed|continue|stop|reply|go|skip|otherwise)\b',
                         r'\bon\b.*\b(success|failure|error|ok)\b',
                         r'\bif (valid|invalid|it|the|parsing|validation)\b']
        for pat in cond_patterns:
            if re.search(pat, main_content, re.IGNORECASE):
                return True, f"Found conditional branching matching: {pat}"
        return False, "No conditional branching patterns found"

    if "under 100 lines" in text.lower():
        all_ok = True
        details = []
        for name, content in specs:
            lines = len(content.strip().split('\n'))
            if lines > 100:
                all_ok = False
                details.append(f"{name}: {lines} lines (OVER)")
            else:
                details.append(f"{name}: {lines} lines")
        return all_ok, "; ".join(details)

    if "spawn the subagent" in text.lower() and "markdown links" in text.lower():
        match = re.search(r'Spawn the Subagent \[.+?\]\(.+?\.md\)', main_content)
        if match:
            return True, f"Found: {match.group(0)[:80]}"
        alt = re.search(r'Spawn.*\[.+?\]\(.+?\.md\)', main_content, re.IGNORECASE)
        if alt:
            return True, f"Found spawn pattern variant: {alt.group(0)[:80]}"
        return False, "No 'Spawn the Subagent [Name](path.md)' pattern found"

    if "spawn the subagent" in text.lower() and "llm reviewer" in text.lower():
        match = re.search(r'Spawn the Subagent \[.+?\]\(.+?\.md\)', main_content)
        if match:
            return True, f"Found: {match.group(0)[:80]}"
        alt = re.search(r'Spawn.*\[.+?\]\(.+?\.md\)', main_content, re.IGNORECASE)
        if alt:
            return True, f"Found: {alt.group(0)[:80]}"
        return False, "No Spawn Subagent pattern for LLM reviewer found"

    if "retry bound" in text.lower() or "retry" in text.lower() and "bound" in text.lower():
        if re.search(r'(up to \d+ retries?|no more than \d+ (times|retries|attempts)|(\d+) attempts total|\d+ retries)', main_content, re.IGNORECASE):
            return True, "Found explicit retry bound"
        return False, "No explicit retry bound found"

    if "separate subagent spec files" in text.lower():
        subagent_specs = [s for s in specs if s != main_spec]
        if subagent_specs:
            return True, f"Found {len(subagent_specs)} separate subagent spec file(s): {', '.join(n for n, _ in subagent_specs)}"
        return False, "No separate subagent spec files found"

    if "llm step" in text.lower() and "delegated" in text.lower():
        if re.search(r'(Spawn|spawn|delegate).*\[.+?\]\(.+?\.md\)', main_content):
            return True, "LLM step delegated to child actor via spawn pattern"
        if re.search(r'(child|subagent|sub-agent).*LLM', main_content, re.IGNORECASE) or \
           re.search(r'LLM.*(child|subagent|sub-agent)', main_content, re.IGNORECASE):
            return True, "LLM step referenced as delegated to child"
        return False, "LLM step not clearly delegated to a child actor"

    if "separate subagent spec file for the llm" in text.lower():
        for name, content in specs:
            if name != main_name and re.search(r'(llm|review|diff)', name, re.IGNORECASE):
                return True, f"Found LLM subagent spec: {name}"
        return False, "No separate LLM subagent spec file found"

    return False, f"Unknown assertion: {text}"


def grade_run(eval_dir: Path, variant: str):
    outputs = eval_dir / variant / "outputs"
    specs = read_specs(outputs)
    main = find_main_spec(specs)
    meta_path = eval_dir / "eval_metadata.json"
    if not meta_path.exists():
        return
    meta = json.loads(meta_path.read_text())
    results = []
    for a in meta["assertions"]:
        passed, evidence = grade_assertion(a["text"], specs, main)
        results.append({"text": a["text"], "passed": passed, "evidence": evidence})
    grading = {"eval_id": meta["eval_id"], "eval_name": meta["eval_name"], "expectations": results}
    out_path = eval_dir / variant / "grading.json"
    out_path.write_text(json.dumps(grading, indent=2))
    passed = sum(1 for r in results if r["passed"])
    total = len(results)
    print(f"  {eval_dir.name}/{variant}: {passed}/{total} passed")
    for r in results:
        status = "PASS" if r["passed"] else "FAIL"
        print(f"    [{status}] {r['text']}: {r['evidence'][:100]}")


evals = ["csv-watcher", "image-orchestrator", "pr-review-agent"]
for e in evals:
    eval_dir = ITER / e
    print(f"\n=== {e} ===")
    grade_run(eval_dir, "with_skill")
    grade_run(eval_dir, "old_skill")
