#!/usr/bin/env bash
set -euo pipefail

# Jackson is allowed only in the MCP HTTP/schema implementations, never in models or contracts.
core_jar="${1:?Usage: bash tools/agent-runtime/verify-core-boundary.sh <core.jar>}"
jdeps --multi-release 21 -verbose:class -filter:none "$core_jar" | awk '
  $2 == "->" && $1 ~ /^io\.seekflux\.platform\.agentruntime\./ {
    seen = 1;
    target = $3;
    if (target ~ /^(java\.|javax\.net\.|jdk\.|io\.seekflux\.platform\.agentruntime\.)/) next;
    if ($1 ~ /^io\.seekflux\.platform\.agentruntime\.mcp\.infrastructure\.(http|schema)\./ && target ~ /^com\.fasterxml\.jackson\./) next;
    print "Forbidden Core dependency: " $0 > "/dev/stderr";
    invalid = 1;
  }
  END { if (!seen) { print "No Runtime Core classes found" > "/dev/stderr"; invalid = 1 }; exit invalid }
'
