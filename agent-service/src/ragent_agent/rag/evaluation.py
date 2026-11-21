from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

_CITATION_PATTERN = re.compile(r"\[(\d+)]")


@dataclass(frozen=True)
class GoldenRagCase:
    id: str
    question: str
    expected_doc_ids: tuple[str, ...]
    forbidden_doc_ids: tuple[str, ...]
    answer_must_contain: tuple[str, ...]
    requires_refusal: bool


@dataclass(frozen=True)
class GoldenRagObservation:
    case_id: str
    answer: str
    source_doc_ids: tuple[str, ...]


@dataclass(frozen=True)
class EvaluationThresholds:
    minimum_document_recall: float = 1.0
    minimum_citation_coverage: float = 1.0
    minimum_answer_coverage: float = 1.0

    def __post_init__(self) -> None:
        for value in (
            self.minimum_document_recall,
            self.minimum_citation_coverage,
            self.minimum_answer_coverage,
        ):
            if not 0 <= value <= 1:
                raise ValueError("RAG evaluation thresholds must be between 0 and 1")


@dataclass(frozen=True)
class CaseEvaluation:
    case_id: str
    passed: bool
    document_recall: float
    citation_coverage: float
    answer_coverage: float
    failures: tuple[str, ...]


@dataclass(frozen=True)
class EvaluationReport:
    passed: bool
    case_count: int
    document_recall: float
    citation_coverage: float
    answer_coverage: float
    failed_case_ids: tuple[str, ...]
    cases: tuple[CaseEvaluation, ...]


def load_cases(path: Path) -> tuple[GoldenRagCase, ...]:
    cases = tuple(_case_from_json(item) for item in _load_json_lines(path))
    _require_unique((case.id for case in cases), "golden RAG case id")
    return cases


def load_observations(path: Path) -> tuple[GoldenRagObservation, ...]:
    observations = tuple(_observation_from_json(item) for item in _load_json_lines(path))
    _require_unique((observation.case_id for observation in observations), "golden RAG observation caseId")
    return observations


def evaluate_golden_cases(
    cases: tuple[GoldenRagCase, ...],
    observations: tuple[GoldenRagObservation, ...],
    thresholds: EvaluationThresholds,
) -> EvaluationReport:
    observations_by_case = {observation.case_id: observation for observation in observations}
    results = tuple(
        _evaluate_case(case, observations_by_case.get(case.id), thresholds)
        for case in cases
    )
    return EvaluationReport(
        passed=bool(results) and all(result.passed for result in results),
        case_count=len(results),
        document_recall=_mean(result.document_recall for result in results),
        citation_coverage=_mean(result.citation_coverage for result in results),
        answer_coverage=_mean(result.answer_coverage for result in results),
        failed_case_ids=tuple(result.case_id for result in results if not result.passed),
        cases=results,
    )


def _evaluate_case(
    case: GoldenRagCase,
    observation: GoldenRagObservation | None,
    thresholds: EvaluationThresholds,
) -> CaseEvaluation:
    if observation is None:
        return CaseEvaluation(case.id, False, 0.0, 0.0, 0.0, ("missing_observation",))

    sources = tuple(dict.fromkeys(observation.source_doc_ids))
    source_set = set(sources)
    expected = set(case.expected_doc_ids)
    document_recall = len(expected & source_set) / len(expected) if expected else 1.0

    citations = tuple(int(value) for value in _CITATION_PATTERN.findall(observation.answer))
    invalid_citations = tuple(index for index in citations if index < 1 or index > len(sources))
    cited_docs = {sources[index - 1] for index in citations if 1 <= index <= len(sources)}
    citation_coverage = len(expected & cited_docs) / len(expected) if expected else 1.0

    required_fragments = tuple(fragment.casefold() for fragment in case.answer_must_contain)
    answer = observation.answer.casefold()
    matched_fragments = sum(fragment in answer for fragment in required_fragments)
    answer_coverage = matched_fragments / len(required_fragments) if required_fragments else 1.0

    failures: list[str] = []
    if document_recall < thresholds.minimum_document_recall:
        failures.append("document_recall_below_threshold")
    if citation_coverage < thresholds.minimum_citation_coverage:
        failures.append("citation_coverage_below_threshold")
    if answer_coverage < thresholds.minimum_answer_coverage:
        failures.append("answer_coverage_below_threshold")
    if source_set & set(case.forbidden_doc_ids):
        failures.append("forbidden_document_retrieved")
    if invalid_citations:
        failures.append("invalid_citation_index")
    if case.requires_refusal and sources:
        failures.append("refusal_case_has_sources")

    return CaseEvaluation(
        case_id=case.id,
        passed=not failures,
        document_recall=document_recall,
        citation_coverage=citation_coverage,
        answer_coverage=answer_coverage,
        failures=tuple(failures),
    )


def _load_json_lines(path: Path) -> tuple[dict[str, Any], ...]:
    records: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise TypeError(f"{path}:{line_number} must contain a JSON object")
            records.append(value)
    return tuple(records)


def _case_from_json(value: dict[str, Any]) -> GoldenRagCase:
    return GoldenRagCase(
        id=_required_text(value, "id"),
        question=_required_text(value, "question"),
        expected_doc_ids=_text_tuple(value, "expectedDocIds"),
        forbidden_doc_ids=_text_tuple(value, "forbiddenDocIds"),
        answer_must_contain=_text_tuple(value, "answerMustContain"),
        requires_refusal=_required_bool(value, "requiresRefusal"),
    )


def _observation_from_json(value: dict[str, Any]) -> GoldenRagObservation:
    return GoldenRagObservation(
        case_id=_required_text(value, "caseId"),
        answer=_required_text(value, "answer"),
        source_doc_ids=_text_tuple(value, "sourceDocIds"),
    )


def _required_text(value: dict[str, Any], field: str) -> str:
    item = value.get(field)
    if not isinstance(item, str) or not item.strip():
        raise ValueError(f"Golden RAG field {field} must be a non-empty string")
    return item.strip()


def _required_bool(value: dict[str, Any], field: str) -> bool:
    item = value.get(field)
    if not isinstance(item, bool):
        raise TypeError(f"Golden RAG field {field} must be a boolean")
    return item


def _text_tuple(value: dict[str, Any], field: str) -> tuple[str, ...]:
    item = value.get(field)
    if not isinstance(item, list) or any(not isinstance(entry, str) or not entry.strip() for entry in item):
        raise ValueError(f"Golden RAG field {field} must be an array of non-empty strings")
    return tuple(entry.strip() for entry in item)


def _require_unique(values, label: str) -> None:
    seen: set[str] = set()
    for value in values:
        if value in seen:
            raise ValueError(f"Duplicate {label}: {value}")
        seen.add(value)


def _mean(values) -> float:
    items = tuple(values)
    return sum(items) / len(items) if items else 0.0


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate versioned RAG observations against release thresholds")
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--minimum-document-recall", type=float, default=1.0)
    parser.add_argument("--minimum-citation-coverage", type=float, default=1.0)
    parser.add_argument("--minimum-answer-coverage", type=float, default=1.0)
    args = parser.parse_args()
    report = evaluate_golden_cases(
        load_cases(args.cases),
        load_observations(args.observations),
        EvaluationThresholds(
            minimum_document_recall=args.minimum_document_recall,
            minimum_citation_coverage=args.minimum_citation_coverage,
            minimum_answer_coverage=args.minimum_answer_coverage,
        ),
    )
    print(json.dumps(asdict(report), ensure_ascii=True, separators=(",", ":")))
    return 0 if report.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
