#!/bin/bash
__DIR="$(dirname "$(readlink -f "${BASH_SOURCE[0]:-${(%):-%x}}")")"

export HOST=$(hostname)

cd "$__DIR" && docker compose down
cd "$__DIR" && docker compose rm

