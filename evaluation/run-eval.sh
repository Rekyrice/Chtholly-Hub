#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../apps/server"
mvn test -Dtest=EvaluationRunnerTest#runEvaluationFromSystemProperties -Deval.mode=full -Deval.output=reports/
