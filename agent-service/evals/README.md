# Golden RAG Evaluation

`golden-rag-v1.jsonl` is the versioned release dataset for the first knowledge Agent. It uses facts from the repository's existing IT support and invoice documents.

Each case declares expected document IDs, explicitly forbidden document IDs, required answer facts and whether the Agent must refuse because no authorized evidence exists. An observation JSONL file contains the actual answer and ordered source document IDs produced by a pre-production run.

Run the release gate:

```powershell
.\.venv\Scripts\python.exe -m ragent_agent.rag.evaluation `
  --cases evals/golden-rag-v1.jsonl `
  --observations <pre-production-observations.jsonl> `
  --minimum-document-recall 1.0 `
  --minimum-citation-coverage 1.0 `
  --minimum-answer-coverage 1.0
```

The command prints a machine-readable report and exits with code 1 when any case misses a threshold, cites an invalid source index, retrieves a forbidden document, or returns sources for a refusal case. CI should retain the report as a build artifact. The example observations under `tests/fixtures` test the evaluator and are not evidence that a deployed index or provider passed.
