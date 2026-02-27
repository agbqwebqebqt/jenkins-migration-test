# Web Frontend

Sample Node.js web app with Jenkins CI pipelines.

This repo is used to test [actions-migrations-via-copilot](https://github.com/github/actions-migrations-via-copilot) — a tool that uses Copilot Custom Agents to convert Jenkins pipelines to GitHub Actions.

## Jenkins Pipelines

| File | Purpose |
|------|---------|
| `Jenkinsfile` | Main build pipeline — lint, test, SonarQube, Docker build, deploy to staging |
| `deploy/Jenkinsfile` | Deployment pipeline — parameterized, with production approval gate |
| `nightwatch/Jenkinsfile` | E2E test pipeline — parallel browser testing (Chrome, Firefox, Safari) |

## Shared Libraries

| File | Purpose |
|------|---------|
| `vars/buildDocker.groovy` | Reusable Docker build & push function |
| `vars/notifySlack.groovy` | Reusable Slack notification function |

## Testing the Migration

1. Create an issue in this repo with the Jenkins migration agent prompt
2. Assign `copilot-swe-agent` to the issue
3. Copilot reads the Jenkinsfiles, transforms them to GitHub Actions, and commits the results
