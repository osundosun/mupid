#!/usr/bin/env bash
# Exercise only the same-version branch, with no Docker/network access.
set -euo pipefail
cd "${BASH_SOURCE[0]%/*}/../.."
block=''
collect=false
while IFS= read -r line; do
  [[ $line == 'if [[ $previous == "$image" ]]; then' ]] && collect=true
  if $collect; then
    block+="$line"$'\n'
    [[ $line == fi ]] && break
  fi
done < infra/deploy/receive.sh
[[ -n $block ]]
check() {
  local state=$1 http=$2 expected=$3 result=0
  (
    image=mupid-api:fixture
    previous=$image
    docker() { printf '%s\n' "$state"; }
    curl() { [[ $http == ok ]] || return 22; printf '%s\n' '{"status":"UP"}'; }
    grep() { local input; IFS= read -r input || return 1; [[ $input == *'"status":"UP"'* ]]; }
    eval "$block"
  ) >/dev/null 2>&1 || result=$?
  [[ $result == "$expected" ]] || { echo 'Same-release check failed'; exit 1; }
}
check 'mupid-api:fixture running healthy' ok 0
check 'mupid-api:fixture restarting unhealthy' ok 1
check 'mupid-api:other running healthy' ok 1
check 'mupid-api:fixture running healthy' failed 1
echo 'Same-release checks: 4 passed'
