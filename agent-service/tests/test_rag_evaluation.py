from __future__ import annotations

from pathlib import Path

from ragent_agent.rag.evaluation import (
    EvaluationThresholds,
    GoldenRagObservation,
    evaluate_golden_cases,
    load_cases,
    load_observations,
)


def test_versioned_golden_rag_cases_pass_the_release_thresholds() -> None:
    project_dir = Path(__file__).parents[1]
    cases = load_cases(project_dir / "evals" / "golden-rag-v1.jsonl")
    observations = load_observations(project_dir / "tests" / "fixtures" / "golden-rag-v1-observations.jsonl")

    report = evaluate_golden_cases(
        cases,
        observations,
        EvaluationThresholds(
            minimum_document_recall=1.0,
            minimum_citation_coverage=1.0,
            minimum_answer_coverage=1.0,
        ),
    )

    assert report.passed is True
    assert report.case_count == 3
    assert report.document_recall == 1.0
    assert report.citation_coverage == 1.0
    assert report.answer_coverage == 1.0
    assert report.failed_case_ids == ()


def test_golden_rag_gate_rejects_missing_forbidden_and_invalid_evidence() -> None:
    project_dir = Path(__file__).parents[1]
    cases = load_cases(project_dir / "evals" / "golden-rag-v1.jsonl")
    observations = load_observations(project_dir / "tests" / "fixtures" / "golden-rag-v1-observations.jsonl")
    invalid_observation = GoldenRagObservation(
        case_id="finance-invoice-tax-id",
        answer="Unsupported answer. [9]",
        source_doc_ids=("it-support",),
    )

    report = evaluate_golden_cases(cases, (invalid_observation, *observations[1:]), EvaluationThresholds())

    assert report.passed is False
    assert report.failed_case_ids == ("finance-invoice-tax-id",)
    failed = report.cases[0]
    assert failed.failures == (
        "document_recall_below_threshold",
        "citation_coverage_below_threshold",
        "answer_coverage_below_threshold",
        "forbidden_document_retrieved",
        "invalid_citation_index",
    )
