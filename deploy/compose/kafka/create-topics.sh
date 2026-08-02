#!/usr/bin/env bash
set -euo pipefail

topics=(
  interaction.raw.v1
  interaction.validated.v1
  exposure.logged.v1
  content.profile.published.v1
  content.distribution.changed.v1
  feature.snapshot.updated.v1
  model.version.activated.v1
)

for topic in "${topics[@]}"; do
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:29092 \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions 3 \
    --replication-factor 1
done

