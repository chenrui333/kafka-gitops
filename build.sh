#!/usr/bin/env sh
set -eu

mkdir -p build/output

jar_path="$(find ./build/libs -maxdepth 1 -type f -name 'kafka-gitops-*-all.jar' | LC_ALL=C sort | tail -n 1)"

if [ -z "${jar_path}" ]; then
  echo "Expected shadow jar was not found under build/libs" >&2
  exit 1
fi

cat stub.sh "${jar_path}" > build/output/kafka-gitops
chmod +x build/output/kafka-gitops
