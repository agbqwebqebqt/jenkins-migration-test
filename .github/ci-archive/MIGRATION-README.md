# Jenkins → GitHub Actions Migration README

> **Migration completed:** All Jenkins pipeline files have been migrated to GitHub Actions
> and moved to this archive directory. The original files are preserved below for reference.

---

## Table of Contents

1. [Migration Summary](#1-migration-summary)
2. [File Mapping](#2-file-mapping)
3. [Architecture Decisions](#3-architecture-decisions)
4. [Shared Library Expansion](#4-shared-library-expansion)
5. [Required Secrets & Variables](#5-required-secrets--variables)
6. [Environment Setup (Production Approval Gate)](#6-environment-setup-production-approval-gate)
7. [actionlint Validation Output](#7-actionlint-validation-output)
8. [Functional Equivalence Notes](#8-functional-equivalence-notes)
9. [Security Notes & SHA Pinning](#9-security-notes--sha-pinning)
10. [Manual Steps Required](#10-manual-steps-required)

---

## 1. Migration Summary

| Property | Value |
|---|---|
| **Migration date** | 2025 |
| **Source system** | Jenkins (Declarative Pipeline) |
| **Target system** | GitHub Actions |
| **Pipelines migrated** | 3 Jenkinsfiles + 2 shared library files |
| **Workflows created** | 3 GitHub Actions workflow files |
| **Shared libraries expanded** | `vars/buildDocker.groovy`, `vars/notifySlack.groovy` (both inlined) |
| **actionlint result** | ✅ 0 errors across all 3 workflow files |

### Pipelines migrated

| # | Jenkins file | GitHub Actions workflow | Type |
|---|---|---|---|
| 1 | `Jenkinsfile` | `.github/workflows/ci.yml` | Push/PR — Lint, Test, SonarQube, Build, Docker, Deploy Staging |
| 2 | `deploy/Jenkinsfile` | `.github/workflows/deploy.yml` | Manual dispatch — Parameterized deployment with production gate |
| 3 | `nightwatch/Jenkinsfile` | `.github/workflows/e2e-tests.yml` | Scheduled — Parallel E2E browser tests |

### Shared libraries inlined

| Groovy file | Inlined into workflow(s) | Notes |
|---|---|---|
| `vars/buildDocker.groovy` | `ci.yml` | Docker login + build-push steps with `docker/login-action` + `docker/build-push-action` |
| `vars/notifySlack.groovy` | `ci.yml`, `deploy.yml`, `e2e-tests.yml` | Slack notification steps with `slackapi/slack-github-action` |

---

## 2. File Mapping

### Archived originals (this directory)

```
.github/ci-archive/
├── Jenkinsfile                    ← was: ./Jenkinsfile
├── deploy/
│   └── Jenkinsfile                ← was: ./deploy/Jenkinsfile
├── nightwatch/
│   └── Jenkinsfile                ← was: ./nightwatch/Jenkinsfile
├── vars/
│   ├── buildDocker.groovy         ← was: ./vars/buildDocker.groovy
│   └── notifySlack.groovy         ← was: ./vars/notifySlack.groovy
└── MIGRATION-README.md            ← this file
```

### New GitHub Actions workflows

```
.github/workflows/
├── ci.yml           ← replaces Jenkinsfile
├── deploy.yml       ← replaces deploy/Jenkinsfile
└── e2e-tests.yml    ← replaces nightwatch/Jenkinsfile
```

---

## 3. Architecture Decisions

### ci.yml — Main CI Pipeline

| Jenkins | GitHub Actions | Reason |
|---|---|---|
| Single declarative pipeline | 4 parallel jobs (`lint`, `test`, `sonarqube`, `build`) + 1 gated job | Better parallelism; lint and test run concurrently |
| `agent { docker { image 'node:18-alpine' } }` | `runs-on: ubuntu-latest` + `actions/setup-node@v4` (node 18) | Node 18 via setup-node; Docker daemon needed for build stage |
| `disableConcurrentBuilds()` | `concurrency: group: …, cancel-in-progress: false` | Exact semantic match — queues builds rather than cancelling |
| `options { timeout(time: 30) }` | `timeout-minutes: 30` per job | Same 30-minute cap |
| `triggers { pollSCM('H/5 * * * *') }` | `on: push` + `on: pull_request` | Push/PR triggers are real-time and preferred over polling |
| `when { branch 'main' }` (Docker + Staging stages) | `if: github.ref == 'refs/heads/main'` on the Docker+Deploy job | Exact functional equivalent |
| `archiveArtifacts artifacts: 'dist/**/*'` | `actions/upload-artifact@v4` (dist) | Equivalent artifact storage |
| `junit 'test-results/**/*.xml'` | `actions/upload-artifact@v4` (test-results) | XML files uploaded; use a test reporter action for in-UI display |
| `publishHTML(target: [...])` | `actions/upload-artifact@v4` (coverage-report) | Downloadable from Actions run summary |

### deploy.yml — Deployment Pipeline

| Jenkins | GitHub Actions | Reason |
|---|---|---|
| `parameters { choice/string/booleanParam }` | `on: workflow_dispatch: inputs:` | Direct equivalent; type-safe inputs |
| `input(message: "…", submitter: 'ops-team,lead-devs')` | `environment: production` with required reviewers | GitHub Environment protection rules implement the approval gate |
| `when { expression { params.ENVIRONMENT == 'production' && !params.DRY_RUN } }` | Environment `production` requires approval; staging has no protection | Approval only fires for production environment |
| `withCredentials([file(credentialsId: "kubeconfig-${params.ENVIRONMENT}")])` | Two conditional steps (staging/production) selecting the correct secret | Secret names must be static in GitHub Actions |

### e2e-tests.yml — E2E Test Pipeline

| Jenkins | GitHub Actions | Reason |
|---|---|---|
| `agent { docker { image 'node:18-alpine' } }` | `runs-on: ubuntu-latest` + `actions/setup-node@v4` | ubuntu-latest has Chrome and Firefox pre-installed |
| `triggers { cron('0 6 * * 1-5') }` | `schedule: - cron: '0 6 * * 1-5'` | Identical cron expression (weekdays 6 AM UTC) |
| `parallel { stage('Chrome') … stage('Firefox') … stage('Safari') }` | `strategy: matrix: include: [chrome, firefox, safari]` + `fail-fast: false` | Matrix replaces parallel stages; browsers don't block each other |
| `publishHTML(…)` | `actions/upload-artifact@v4` (e2e-html-report) | Downloadable HTML report from the run summary |
| `archiveArtifacts 'reports/**/*'` | `actions/upload-artifact@v4` (all-e2e-reports) | All report artifacts preserved |
| `cleanWs()` | _(not needed)_ | GitHub Actions runners are ephemeral — workspace is clean per job |

---

## 4. Shared Library Expansion

### vars/buildDocker.groovy → Inlined in ci.yml

The shared library function `buildDocker(registry, imageName, tag, credentialsId, dockerfile, buildArgs)` has been expanded into two steps in the `docker-build-push-and-deploy-staging` job:

**Original Groovy logic:**
```groovy
def fullImage   = "${registry}/${imageName}:${tag}"   // :BUILD_NUMBER
def latestImage = "${registry}/${imageName}:latest"
sh "docker build -t ${fullImage} -t ${latestImage} -f ${dockerfile} ${buildArgsStr} ${context}"
withCredentials([usernamePassword(credentialsId: credentialsId, …)]) {
    sh "echo ${DOCKER_PASS} | docker login ${registry} -u ${DOCKER_USER} --password-stdin"
    sh "docker push ${fullImage}"
    sh "docker push ${latestImage}"
}
```

**Expanded GitHub Actions equivalent (ci.yml):**
```yaml
- name: Log in to Docker Registry
  uses: docker/login-action@v3
  with:
    registry: ${{ env.DOCKER_REGISTRY }}
    username: ${{ secrets.DOCKER_REGISTRY_USER }}
    password: ${{ secrets.DOCKER_REGISTRY_PASS }}

- name: Build and push Docker image
  uses: docker/build-push-action@v6
  with:
    context: .
    file: Dockerfile
    push: true
    tags: |
      ${{ env.DOCKER_REGISTRY }}/${{ env.APP_NAME }}:${{ github.run_number }}
      ${{ env.DOCKER_REGISTRY }}/${{ env.APP_NAME }}:latest
```

**Mapping notes:**
- `tag: env.BUILD_NUMBER` → `github.run_number` (GitHub Actions run counter)
- `docker push fullImage` + `docker push latestImage` → both tags in `tags:` block
- `withCredentials([usernamePassword(…)])` → `docker/login-action` with repository secrets

---

### vars/notifySlack.groovy → Inlined in all three workflows

The shared library function `notifySlack(status, channel, message)` determines color/emoji and sends via `slackSend()`. It is expanded into conditional `Notify Slack — success` / `Notify Slack — failure` steps in each workflow.

**Original Groovy logic:**
```groovy
def color = status == 'SUCCESS' ? 'good'   : status == 'FAILURE' ? 'danger' : 'warning'
def emoji = status == 'SUCCESS' ? ':white_check_mark:' : status == 'FAILURE' ? ':x:' : ':warning:'
def msg = "${emoji} *${JOB_NAME}* #${BUILD_NUMBER} - *${status}*\nBranch: ${BRANCH_NAME}\n<${BUILD_URL}|View Build>"
slackSend(channel: channel, color: color, message: msg)
```

**Expanded GitHub Actions equivalent (all workflows):**
```yaml
- name: Notify Slack — success
  if: success()
  uses: slackapi/slack-github-action@v1.27.0
  env:
    SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK_DEPLOYMENTS }}
  with:
    payload: |
      {
        "text": ":white_check_mark: *${{ github.workflow }}* #${{ github.run_number }} - *SUCCESS*\nBranch: ${{ github.ref_name }}\n<...|View Run>"
      }

- name: Notify Slack — failure
  if: failure()
  uses: slackapi/slack-github-action@v1.27.0
  env:
    SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK_DEPLOYMENTS }}
  with:
    payload: |
      { "text": ":x: *${{ github.workflow }}* #${{ github.run_number }} - *FAILURE*\n..." }
```

**Mapping notes:**
- `env.JOB_NAME` → `github.workflow`
- `env.BUILD_NUMBER` → `github.run_number`
- `env.BRANCH_NAME` → `github.ref_name`
- `env.BUILD_URL` → `${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}`
- `color: 'good'` / `color: 'danger'` → `:white_check_mark:` / `:x:` emoji in message text
- `UNSTABLE` status has no direct GitHub Actions equivalent (omitted — jobs are pass/fail only)
- Channel targeting is now done via separate webhook URLs per channel (see secrets table)

---

## 5. Required Secrets & Variables

Configure these in **Settings → Secrets and variables → Actions** in the repository.

### Repository Secrets

| Secret name | Description | Source (Jenkins credential) | Used in |
|---|---|---|---|
| `NPM_TOKEN` | npm registry authentication token | `npm-token` (Secret text) | `ci.yml`, `deploy.yml`, `e2e-tests.yml` |
| `DOCKER_REGISTRY_USER` | Docker registry username | `docker-registry-creds` (username part) | `ci.yml` |
| `DOCKER_REGISTRY_PASS` | Docker registry password | `docker-registry-creds` (password part) | `ci.yml` |
| `KUBECONFIG_STAGING` | Base64-encoded kubeconfig for staging cluster | `kubeconfig-staging` (File credential) | `ci.yml`, `deploy.yml` |
| `KUBECONFIG_PRODUCTION` | Base64-encoded kubeconfig for production cluster | `kubeconfig-production` (File credential) | `deploy.yml` |
| `SONAR_TOKEN` | SonarQube authentication token | SonarQube user token | `ci.yml` |
| `BROWSERSTACK_USER` | BrowserStack username | `browserstack-user` (Secret text) | `e2e-tests.yml` |
| `BROWSERSTACK_KEY` | BrowserStack access key | `browserstack-key` (Secret text) | `e2e-tests.yml` |
| `SLACK_WEBHOOK_DEPLOYMENTS` | Incoming webhook URL for `#deployments` channel | Slack App webhook | `ci.yml`, `deploy.yml` |
| `SLACK_WEBHOOK_QA_ALERTS` | Incoming webhook URL for `#qa-alerts` channel | Slack App webhook | `e2e-tests.yml` |

> **Preparing kubeconfig secrets:**
> ```bash
> # Encode the kubeconfig file as base64 and store as the secret value:
> base64 -w 0 ~/.kube/staging-config   # paste output as KUBECONFIG_STAGING
> base64 -w 0 ~/.kube/prod-config      # paste output as KUBECONFIG_PRODUCTION
> ```

> **Slack webhook split:** The original Jenkins pipeline used a single `slackSend()` plugin with
> per-channel arguments. GitHub Actions uses incoming webhook URLs, which are channel-specific.
> Create one webhook URL per Slack channel and store each as a separate secret.

### Repository Variables (non-sensitive)

Configure in **Settings → Secrets and variables → Actions → Variables tab**:

| Variable name | Value | Used in |
|---|---|---|
| `SONAR_HOST_URL` | `https://sonar.company.com` | `ci.yml` |

---

## 6. Environment Setup (Production Approval Gate)

The Jenkins `input(message: "Deploy to PRODUCTION?", submitter: 'ops-team,lead-devs')` gate
is implemented via **GitHub Environments**. Create the following environments:

### Required environments

| Environment name | Protection rules | Used in |
|---|---|---|
| `staging` | None (auto-deploy) | `ci.yml`, `deploy.yml` |
| `production` | Required reviewers: `ops-team`, `lead-devs` | `deploy.yml` |

### Setup steps

1. Go to **Settings → Environments → New environment**
2. Create `staging` — no protection rules needed
3. Create `production`:
   - Check **Required reviewers**
   - Add teams/individuals: `ops-team`, `lead-devs`
   - Optionally set **Wait timer** (e.g., 10 minutes) for a deployment freeze window
   - Optionally restrict to the `main` branch only
4. Add environment-scoped secrets if kubeconfig credentials differ per environment
   (alternative to repository-level `KUBECONFIG_STAGING` / `KUBECONFIG_PRODUCTION`)

---

## 7. actionlint Validation Output

The following is the **actual output** from running `actionlint v1.7.7` against all three
migrated workflow files immediately after creation:

```
$ actionlint -verbose \
    .github/workflows/ci.yml \
    .github/workflows/deploy.yml \
    .github/workflows/e2e-tests.yml

verbose: Linting 3 files
verbose: Linting .github/workflows/e2e-tests.yml
verbose: Using project at /home/runner/work/jenkins-migration-test/jenkins-migration-test
verbose: Linting .github/workflows/ci.yml
verbose: Using project at /home/runner/work/jenkins-migration-test/jenkins-migration-test
verbose: Linting .github/workflows/deploy.yml
verbose: Using project at /home/runner/work/jenkins-migration-test/jenkins-migration-test
verbose: Found 0 parse errors in 0 ms for .github/workflows/e2e-tests.yml
verbose: Rule "pyflakes" was disabled: exec: "pyflakes": executable file not found in $PATH
verbose: Found 0 parse errors in 0 ms for .github/workflows/deploy.yml
verbose: Rule "pyflakes" was disabled: exec: "pyflakes": executable file not found in $PATH
verbose: Found 0 parse errors in 0 ms for .github/workflows/ci.yml
verbose: Rule "pyflakes" was disabled: exec: "pyflakes": executable file not found in $PATH
verbose: Found total 0 errors in 14 ms for .github/workflows/e2e-tests.yml
verbose: Found total 0 errors in 28 ms for .github/workflows/ci.yml
verbose: Found total 0 errors in 40 ms for .github/workflows/deploy.yml
verbose: Found 0 errors in 3 files

=== EXIT CODE: 0 ===
```

**Result: ✅ 0 errors, 0 warnings across all 3 workflow files.**

> The `pyflakes` note is informational only — the rule is disabled when `pyflakes` is
> not installed; it only applies to Python `run:` scripts. None of the migrated workflows
> contain Python scripts, so this has no impact.

---

## 8. Functional Equivalence Notes

### Jenkins variables → GitHub Actions context equivalents

| Jenkins env var | GitHub Actions equivalent |
|---|---|
| `env.BUILD_NUMBER` | `github.run_number` |
| `env.JOB_NAME` | `github.workflow` |
| `env.BRANCH_NAME` | `github.ref_name` |
| `env.BUILD_URL` | `${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}` |
| `params.ENVIRONMENT` | `inputs.environment` |
| `params.IMAGE_TAG` | `inputs.image_tag` |
| `params.DRY_RUN` | `inputs.dry_run` |

### Feature mapping

| Jenkins feature | GitHub Actions equivalent | Notes |
|---|---|---|
| `pollSCM('H/5 * * * *')` | `on: push` + `on: pull_request` | Push triggers are real-time; polling removed |
| `triggers { cron('0 6 * * 1-5') }` | `schedule: - cron: '0 6 * * 1-5'` | Identical cron expression |
| `disableConcurrentBuilds()` | `concurrency: cancel-in-progress: false` | Queues instead of cancels |
| `options { timeout(30, MINUTES) }` | `timeout-minutes: 30` | Per-job |
| `buildDiscarder(logRotator(numToKeepStr: '10'))` | Automatic (GitHub retains 90 days by default) | Configure in repo settings |
| `timestamps()` | Built-in to all GitHub Actions logs | No action needed |
| `agent { docker { image 'node:18-alpine' } }` | `runs-on: ubuntu-latest` + `actions/setup-node@v4` | Node 18 via setup-node |
| `withSonarQubeEnv('SonarQube')` | `SONAR_TOKEN` + `SONAR_HOST_URL` env vars | Via `SonarSource/sonarqube-scan-action` |
| `waitForQualityGate abortPipeline: true` | `SonarSource/sonarqube-quality-gate-action` | 5-min timeout preserved |
| `junit 'test-results/**/*.xml'` | `actions/upload-artifact@v4` | Upload XMLs; add test-reporter action for inline display |
| `publishHTML(…)` | `actions/upload-artifact@v4` | Download from run summary |
| `archiveArtifacts` | `actions/upload-artifact@v4` | Equivalent artifact storage |
| `withCredentials([file(…)])` | Repository secret (base64-encoded file content) | Decoded at runtime via `base64 -d` |
| `withCredentials([usernamePassword(…)])` | Repository secrets (separate user/pass) | Direct env var injection |
| `input(message, submitter)` | GitHub Environment + required reviewers | Native approval gate |
| `parallel { stage … }` | `strategy: matrix:` | `fail-fast: false` preserves non-blocking behavior |
| `cleanWs()` | _(not needed)_ | GitHub runners are ephemeral per job |
| `slackSend(channel, color, message)` | `slackapi/slack-github-action@v1.27.0` | Expanded from `notifySlack.groovy` |

### Known differences

1. **`junit` plugin UI**: Jenkins renders JUnit XML results in the build UI. GitHub Actions does
   not natively render JUnit XML — the files are uploaded as artifacts. To get inline test
   result display, add a test reporter action such as `dorny/test-reporter@v1`.

2. **`publishHTML` plugin**: Jenkins renders the HTML report directly in the Jenkins UI.
   GitHub Actions uploads it as a downloadable artifact. Use GitHub Pages or a dedicated
   action for live HTML report hosting.

3. **`UNSTABLE` build result**: Jenkins has a 3-state result (SUCCESS/UNSTABLE/FAILURE).
   GitHub Actions jobs are binary (success/failure). The `notifySlack.groovy` UNSTABLE
   branch has no equivalent and is omitted.

4. **`buildDiscarder`**: GitHub Actions retains logs and artifacts for 90 days by default.
   Configure retention at the organization or repository level in Settings.

5. **`timestamps()`**: GitHub Actions logs always include timestamps — no configuration needed.

---

## 9. Security Notes & SHA Pinning

### Action pinning

All actions in the generated workflows use **mutable version tags** (e.g., `@v4`, `@v1.27.0`).
Before merging these workflows to a production branch, **pin every action to its full commit SHA**
to prevent supply-chain attacks:

```bash
# Example: Get the SHA for actions/checkout@v4
gh api repos/actions/checkout/git/ref/tags/v4.2.2

# Then replace in workflow:
# uses: actions/checkout@v4
# becomes:
# uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683  # v4.2.2
```

Actions used and their recommended pinning targets:

| Action | Tag used | Pin to tag version |
|---|---|---|
| `actions/checkout` | `@v4` | `v4.2.2` |
| `actions/setup-node` | `@v4` | `v4.2.0` |
| `actions/upload-artifact` | `@v4` | `v4.6.2` |
| `actions/download-artifact` | `@v4` | `v4.2.1` |
| `docker/login-action` | `@v3` | `v3.3.0` |
| `docker/build-push-action` | `@v6` | `v6.10.0` |
| `SonarSource/sonarqube-scan-action` | `@v4` | `v4.2.1` |
| `SonarSource/sonarqube-quality-gate-action` | `@v1.1.0` | `v1.1.0` |
| `slackapi/slack-github-action` | `@v1.27.0` | `v1.27.0` |

### Secrets security

- All credentials are stored as **repository secrets**, never hardcoded
- Kubeconfig files are base64-encoded before storage (never stored as plaintext YAML)
- Docker registry credentials use the official `docker/login-action` which handles
  credential masking and token revocation
- No secrets are echoed in `run:` scripts

---

## 10. Manual Steps Required

Complete these steps before the migrated workflows are production-ready:

### Immediate (before first run)

- [ ] **Configure repository secrets** — add all 10 secrets listed in Section 5
- [ ] **Configure repository variables** — add `SONAR_HOST_URL` variable
- [ ] **Create GitHub Environments** — create `staging` and `production` environments (Section 6)
- [ ] **Configure production reviewers** — add `ops-team` and `lead-devs` as required reviewers
      on the `production` environment

### Before merging to production

- [ ] **Pin all actions to commit SHAs** — replace mutable version tags with SHA pins (Section 9)
- [ ] **Create Slack webhook URLs** — one per channel (`#deployments` → `SLACK_WEBHOOK_DEPLOYMENTS`,
      `#qa-alerts` → `SLACK_WEBHOOK_QA_ALERTS`)
- [ ] **Create SonarQube project** — ensure `sonar-project.properties` exists with correct
      `sonar.projectKey` and `sonar.sources` settings
- [ ] **Verify nightwatch browser config** — ensure `nightwatch.conf.js` has `chrome`, `firefox`,
      and `safari.browserstack` environments defined; update paths for GH runner if needed
- [ ] **Test kubeconfig base64 encoding** — verify secrets decode correctly:
      `echo "$KUBECONFIG_STAGING" | base64 -d | kubectl --kubeconfig /dev/stdin get nodes`

### Optional enhancements

- [ ] **Add JUnit test reporter** — add `dorny/test-reporter@v1` to `ci.yml` for inline test
      result display in pull requests
- [ ] **Add Docker layer caching** — add `cache-from`/`cache-to` inputs to `docker/build-push-action`
      using GitHub Actions cache for faster builds
- [ ] **Add branch protection rules** — require the `CI` workflow to pass before merging PRs
- [ ] **Configure `CODEOWNERS`** — add `ops-team` as required reviewers for production deploy workflow

---

*Migration performed by Jenkins to GitHub Actions migration agent.*
