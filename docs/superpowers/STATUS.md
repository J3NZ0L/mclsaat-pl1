# STATUS

_Updated: 2026-09-29 (T01)_

## Where we are
**T01 done** — plan written, branch created. Nothing implemented yet.

## Environment facts verified this session
* Java 21.0.10, Maven 3.9.11, Docker 29.3.1 + compose v5.1.1, psql client 16.13.
* The Docker daemon is **not running at container start** — start it with
  `nohup dockerd > /tmp/dockerd.log 2>&1 &` and wait ~8s.
* Maven Central reachable. Docker Hub reachable; already pulled:
  `postgres:16-alpine`, `stripe/stripe-mock:latest`, `eclipse-temurin:21-jre-jammy`.
* No Conductor/crewmarshal plugin is installed (`ListPlugins` -> empty), so the workflow
  discipline is followed manually: plan -> task -> implement -> verify by running -> commit/push.
* Sonatype `repo1.maven.org` rate-limits raw `curl` metadata queries; let Maven resolve instead.

## Task board (see docs/superpowers/PLAN.md section 4)
| Task | State |
| --- | --- |
| T01 plan, branch, draft PR | done |
| T02 Maven skeleton | todo |
| T03 catalog-service | todo |
| T04 billing-service (schema + SOAP) | todo |
| T05 stripe-sim + payment start | todo |
| T06 billing batch/EDI + ops SOAP ops | todo |
| T07 activation-service (Flowable) | todo |
| T08 subsystem-clients + mappers | todo |
| T09 ops-console | todo |
| T10 Docker compose | todo |
| T11 non-Docker local path | todo |
| T12 demo script | todo |
| T13 docs | todo |
| T14 CLAUDE.md + final pass | todo |

## Half-done
Nothing.

## Next action
T02: create the Maven multi-module skeleton (parent POM + 6 modules) and get `mvn verify` green.

## Branch note
Work happens on `feat/legacy-system` (as requested). The harness-designated branch
`claude/busy-cray-97xi8a` is kept fast-forwarded to the same head so both instructions hold.
