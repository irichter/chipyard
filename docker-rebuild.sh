#!/bin/bash
__DIR="$(dirname "$(readlink -f "${BASH_SOURCE[0]:-${(%):-%x}}")")"

cd "$__DIR" && docker-compose up --build -d
